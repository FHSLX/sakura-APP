package com.sakura.remote;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 截屏用的前台服务。
 *
 * Android 14（API 34）起，MediaProjection 只能在
 * foregroundServiceType="mediaProjection" 的前台服务里使用。
 *
 * 本轮（RV-07 / RV-09 / INT-02）修正：
 *   1. 先读并校验 requestId / grant，再在系统期限内合法启动前台服务；
 *      FGS 启动失败立即按匹配 request 终结，不静默继续；
 *   2. 重复 start 幂等（同 requestId 不重建资源）；旧 run 已被取消而新请求有效时
 *      显式替换旧 run；当前 run 仍对应活动请求时拒绝覆盖其 requestId/resources；
 *      陈旧启动只做最小的 start/stop，绝不释放新任务的隐藏理由；
 *   3. **拒绝分支不再无条件 stopSelf**（INT-02）：有活动 run 时只丢弃被拒的那次
 *      start，当前 projection / 通知 / 隐藏令牌原样保留；只有没有任何活动 run 的
 *      空启动才按 startId 清理，并先满足前台期限；
 *   4. 取消/超时通过 {@link #abort} 主动终结服务与资源，而不是等 8s 帧超时；
 *   5. 隐藏理由按 requestId 归属：先建立 captureHidden，再释放授权隐藏，
 *      两段隐藏无缝衔接，悬浮窗不会在两个理由之间闪回；
 *   6. 首帧在「授权页已让位且悬浮窗已 detach」之后才创建 VirtualDisplay，
 *      不会把授权页或悬浮窗拍进画面。
 *
 * 本轮（INT-05 / INT-06）修正：
 *   7. 让位预算耗尽时**明确失败**（绝不超时后照样建投影，也就不会截到授权页/
 *      悬浮窗）；registerCallback 抛异常立即终结并释放 grant/隐藏令牌；
 *   8. 所有异步事件（abort / projection.onStop / 帧 / 帧超时 / 让位等待）
 *      在入队时捕获不可变的 owner(requestId) + runGeneration，并在主线程
 *      真正执行的那一瞬间复核；陈旧事件直接忽略，不得终结替换后的新 run。
 */
public class CaptureService extends Service {

    public static final String EXTRA_REQUEST_ID = "requestId";
    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String TAG = "SakuraCapture";
    private static final String CHANNEL_ID = "sakura_capture";
    private static final int NOTIFICATION_ID = 0x5342;
    /** 等首帧的超时上限：超时算失败并清理，不无限等。 */
    private static final long FRAME_TIMEOUT_MS = 8000L;
    /** 等「授权页让位 + 悬浮窗 detach」的上限：50ms × 60 = 3s。 */
    private static final int MAX_YIELD_ATTEMPTS = 60;
    private static final long YIELD_STEP_MS = 50L;

    /** 当前存活实例，供协调器主动 abort（RV-07）。 */
    private static volatile CaptureService current;

    /** 协调器取消/超时：匹配 requestId 时主动终结服务与资源。
     *
     * INT-06：调用线程只做一次「现在是否还是这个 run」的快速判断并捕获
     * owner + runGeneration；真正的终结推迟到主线程执行时**再次复核**。
     * 旧实现只检查一次 requestId 就 post 一个不带 owner 复核的 finishOnce，
     * 若队列里先处理了新请求 B，旧的 abort(A) 会把 B 一并结束。
     */
    public static void abort(String requestId, String reason) {
        CaptureService service = current;
        if (service == null) {
            return;
        }
        if (requestId == null || requestId.isEmpty()
                || !requestId.equals(service.requestId)) {
            return;
        }
        final String owner = requestId;
        final long generation = service.runGeneration;
        final String message = reason == null ? "已取消" : reason;
        service.handler.post(() -> service.finishForOwner(owner, generation, "", message));
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** true = 当前实例没有正在进行的 run。 */
    private final AtomicBoolean finished = new AtomicBoolean(true);

    private volatile String requestId = "";
    /**
     * 当前 run 的代际（INT-06）：每次接受一个新 run 自增。
     *
     * 异步事件（abort / projection.onStop / 帧 / 超时）在入队时捕获
     * owner + runGeneration，执行瞬间两者都要仍然匹配才允许终结当前 run，
     * 否则一律忽略 —— 陈旧的终结绝不能杀掉替换后的新请求。
     */
    private volatile long runGeneration;
    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay virtualDisplay;
    private ImageReader reader;
    private int captureWidth;
    private int captureHeight;
    private boolean foregroundStarted;

    /**
     * 启动一次截屏。
     *
     * @param requestId  协调器分配的请求 id（迟到帧靠它隔离）
     * @param resultCode 授权结果码（必须是 RESULT_OK）
     * @param grantData  用户刚授权的 Intent（一次性，不缓存复用）
     */
    public static void start(Context context, String requestId, int resultCode, Intent grantData) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.putExtra(EXTRA_REQUEST_ID, requestId);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, grantData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 1) 先读参数（不碰资源），才能按 requestId 做幂等/覆盖/拒绝裁决
        String newId = intent != null ? intent.getStringExtra(EXTRA_REQUEST_ID) : null;
        if (newId == null) {
            newId = "";
        }
        int resultCode = intent != null
                ? intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                : Activity.RESULT_CANCELED;
        Intent resultData = intent != null ? intent.getParcelableExtra(EXTRA_RESULT_DATA) : null;

        CaptureCoordinator coordinator = CaptureCoordinator.get();
        boolean newRequestActive = coordinator.isActive(newId);
        boolean currentRequestActive = !requestId.isEmpty() && coordinator.isActive(requestId);
        boolean runActive = !finished.get();

        CaptureStartPolicy.Decision decision = CaptureStartPolicy.decide(
                runActive, requestId, newId, newRequestActive, currentRequestActive);
        switch (decision) {
            case REJECT_INVALID:
                // 参数缺失：只处理这次被拒的 start，不动任何活动截图
                rejectStart(startId, runActive, "参数缺失");
                return START_NOT_STICKY;
            case REJECT_STALE:
                // 陈旧启动：不得释放新任务的隐藏状态，也不消耗 grant
                rejectStart(startId, runActive, "请求已失效");
                return START_NOT_STICKY;
            case REJECT_BUSY:
                // 当前 run 仍对应活动请求：拒绝覆盖它的 requestId/resources
                rejectStart(startId, runActive, "已有正在执行的截图");
                return START_NOT_STICKY;
            case ACCEPT_IDEMPOTENT:
                // 同一请求被重复 start：资源已在建，确认前台后直接返回
                startForegroundCompat();
                return START_NOT_STICKY;
            case REPLACE_STALE:
                // 旧 run 已被取消（registry 不再活动）而新请求有效：显式替换旧 run
                abandonRun("被新的截图请求替换");
                break;
            case ACCEPT:
            default:
                break;
        }

        // 2) 接收新 run（代际自增：旧 run 的所有在途事件自此失效）
        if (finished.get()) {
            finished.set(false);
        }
        requestId = newId;
        current = this;
        runGeneration++;

        // 3) 校验 grant
        if (resultData == null || resultCode != Activity.RESULT_OK) {
            finishOnce("", "没有拿到截屏授权");
            return START_NOT_STICKY;
        }

        // 4) 在系统期限内合法启动前台服务；失败立即按匹配 request 终结
        if (!startForegroundCompat()) {
            finishOnce("", "启动截屏前台服务失败");
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            finishOnce("", "系统不支持屏幕截图");
            return START_NOT_STICKY;
        }

        MediaProjection activeProjection;
        try {
            activeProjection = manager.getMediaProjection(resultCode, resultData);
        } catch (Exception error) {
            finishOnce("", "获取截屏通道失败：" + error.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
        if (activeProjection == null) {
            finishOnce("", "获取截屏通道失败");
            return START_NOT_STICKY;
        }
        projection = activeProjection;

        // 5) 先注册回调，再创建 VirtualDisplay（官方明确要求）。
        //    INT-05：注册失败必须立即终结并释放 grant/隐藏令牌，绝不能
        //    「记一条日志然后继续建 VirtualDisplay」。
        final String owner = requestId;
        final long generation = runGeneration;
        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                // INT-06：旧 projection 的 onStop 不得终结替换后的新 run
                finishForOwner(owner, generation, "", "系统停止了屏幕共享");
            }
        };
        try {
            projection.registerCallback(projectionCallback, handler);
        } catch (Exception error) {
            Log.w(TAG, "registerCallback failed: " + error.getClass().getSimpleName());
            finishForOwner(owner, generation, "", "注册截屏回调失败");
            return START_NOT_STICKY;
        }

        // 6) 隐藏理由两阶段交接：先建立 captureHidden，再释放授权隐藏 token
        OverlayService.setHiddenForCapture(requestId, true);
        OverlayService.releasePermissionHidden(requestId);

        // 7) 等授权页让位 + 悬浮窗 detach，再建首帧
        scheduleYieldWait(owner, generation);
        return START_NOT_STICKY;
    }

    /** 首帧让位门槛（RV-09）：授权页不可见且悬浮窗已 detach。 */
    private boolean yieldOpen() {
        return !CapturePermissionActivity.isAnyVisible()
                && !OverlayService.isOverlayWindowAttached();
    }

    /**
     * 等让位（INT-05 / INT-06）。
     *
     * 每个 run 一个独立的排队任务，入队时捕获 owner + generation：
     *   - 执行瞬间若已不属于当前 run，直接放弃（旧 run 的等待不得动新 run）；
     *   - 让位成立才建 surface；
     *   - 预算耗尽时**明确失败**并释放资源 —— 旧实现写的是
     *     {@code yieldOpen() || yieldAttempts >= MAX_YIELD_ATTEMPTS}，
     *     超时后照样 createVirtualDisplay，会把授权页/悬浮窗拍进画面。
     */
    private void scheduleYieldWait(final String owner, final long generation) {
        Runnable wait = new Runnable() {
            private int attempts;

            @Override
            public void run() {
                if (!isCurrentRun(owner, generation)) {
                    return;
                }
                if (yieldOpen()) {
                    startCaptureSurfaceNow(owner, generation);
                    return;
                }
                if (attempts >= MAX_YIELD_ATTEMPTS) {
                    // 让位不成立且预算耗尽：明确失败，绝不创建 surface
                    finishForOwner(owner, generation, "",
                            "授权页或悬浮窗未让位，已取消截图");
                    return;
                }
                attempts++;
                handler.postDelayed(this, YIELD_STEP_MS);
            }
        };
        handler.post(wait);
    }

    private void startCaptureSurfaceNow(String owner, long generation) {
        if (!isCurrentRun(owner, generation)) {
            return;
        }
        if (!createCaptureSurface(owner, generation)) {
            return;
        }
        CaptureCoordinator.get().onServiceWaitingFrame(owner);
        scheduleFrameTimeout(owner, generation);
    }

    /**
     * 创建 ImageReader 与 VirtualDisplay；失败时终结并返回 false。
     *
     * 尺寸与回调都绑定到**本 run**：旧资源即便迟到也不会用当前请求的尺寸解码。
     */
    private boolean createCaptureSurface(String owner, long generation) {
        DisplayMetrics metrics = new DisplayMetrics();
        android.view.WindowManager windowManager =
                (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) {
            finishForOwner(owner, generation, "", "取不到屏幕尺寸");
            return false;
        }
        try {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Exception error) {
            finishForOwner(owner, generation, "", "取不到屏幕尺寸");
            return false;
        }
        captureWidth = metrics.widthPixels;
        captureHeight = metrics.heightPixels;
        final int width = captureWidth;
        final int height = captureHeight;
        int density = metrics.densityDpi > 0 ? metrics.densityDpi : DisplayMetrics.DENSITY_DEFAULT;

        MediaProjection activeProjection = projection;
        if (activeProjection == null) {
            finishForOwner(owner, generation, "", "截图通道已释放");
            return false;
        }
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            reader.setOnImageAvailableListener(
                    source -> onImageAvailableFor(owner, generation, width, height, source),
                    handler);
            virtualDisplay = activeProjection.createVirtualDisplay(
                    "sakura-capture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, handler);
        } catch (Exception error) {
            finishForOwner(owner, generation, "",
                    "创建截图画面失败：" + error.getClass().getSimpleName());
            return false;
        }
        if (virtualDisplay == null) {
            finishForOwner(owner, generation, "", "创建截图画面失败");
            return false;
        }
        return true;
    }

    /** 排入本 run 的帧超时；执行时复核 owner + generation（INT-06）。 */
    private void scheduleFrameTimeout(final String owner, final long generation) {
        handler.postDelayed(
                () -> finishForOwner(owner, generation, "", "等待画面超时，请重试"),
                FRAME_TIMEOUT_MS);
    }

    /**
     * 首帧到达：读到就立刻取图并终结，不必再等定时器。
     *
     * INT-06：只接受**本 run 当前 reader** 的帧；旧 reader 的迟到帧直接忽略，
     * 也不会拿它的 buffer 去按当前请求的尺寸解码。
     */
    private void onImageAvailableFor(String owner, long generation, int width, int height,
                                     ImageReader source) {
        if (!isCurrentRun(owner, generation)) {
            return;
        }
        if (source == null || source != reader) {
            return;   // 旧资源的迟到帧
        }
        Image image = null;
        try {
            image = source.acquireLatestImage();
        } catch (Exception error) {
            Log.w(TAG, "acquireLatestImage failed: " + error.getClass().getSimpleName());
        }
        if (image == null) {
            // 该帧还没准备好；listener 会再次触发，等超时兜底
            return;
        }
        String dataUrl = "";
        String errorText = "";
        try {
            Bitmap bitmap = ScreenCapturer.toBitmap(image, width, height);
            if (bitmap == null) {
                errorText = "截图解码失败";
            } else {
                dataUrl = ScreenCapturer.compressToDataUrl(bitmap);
                bitmap.recycle();
                if (dataUrl.isEmpty()) {
                    errorText = "截图压缩失败";
                }
            }
        } catch (Exception error) {
            errorText = error.getClass().getSimpleName();
        } finally {
            image.close();
        }
        finishForOwner(owner, generation, dataUrl, errorText);
    }

    /** 当前排队的事件是否仍属于这个 run（owner + 代际 + 未终结）。 */
    private boolean isCurrentRun(String owner, long generation) {
        if (finished.get()) {
            return false;
        }
        if (generation != runGeneration) {
            return false;
        }
        String id = requestId;
        return owner != null && owner.equals(id);
    }

    /**
     * 带归属复核的终结（INT-06）：执行瞬间确认仍属于该 owner/run，
     * 陈旧事件直接忽略；只有真正当前的 run 才走 {@link #finishOnce}。
     */
    private void finishForOwner(String owner, long generation, String dataUrl, String error) {
        if (!isCurrentRun(owner, generation)) {
            return;
        }
        finishOnce(dataUrl, error);
    }

    /**
     * 唯一终结路径：成功/失败/超时/onDestroy/系统停止/主动取消都走这里，
     * 恰好执行一次。只释放属于本 requestId token 的隐藏理由。
     */
    private void finishOnce(String dataUrl, String error) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        releaseResources();
        // 只释放本次请求的隐藏理由；captureHidden 与授权隐藏都是按 requestId 归属
        OverlayService.setHiddenForCapture(requestId, false);
        OverlayService.releasePermissionHidden(requestId);
        CaptureCoordinator.get().onServiceResult(requestId, dataUrl, error);
        stopForegroundCompat();
        if (current == this) {
            current = null;
        }
        requestId = "";
        stopSelf();
    }

    /**
     * 被新请求替换：释放旧 run 的资源与隐藏理由，但不 stopSelf，
     * 以便同一实例接着服务新请求。
     */
    private void abandonRun(String reason) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        releaseResources();
        OverlayService.setHiddenForCapture(requestId, false);
        OverlayService.releasePermissionHidden(requestId);
        CaptureCoordinator.get().onServiceResult(requestId, "", reason);
        if (current == this) {
            current = null;
        }
        requestId = "";
    }

    /** 幂等释放所有资源。 */
    private void releaseResources() {
        ImageReader activeReader = reader;
        reader = null;
        if (activeReader != null) {
            try {
                activeReader.setOnImageAvailableListener(null, null);
            } catch (Exception ignored) {
                // 已释放
            }
            try {
                activeReader.close();
            } catch (Exception ignored) {
                // 已释放
            }
        }
        VirtualDisplay activeDisplay = virtualDisplay;
        virtualDisplay = null;
        if (activeDisplay != null) {
            try {
                activeDisplay.release();
            } catch (Exception ignored) {
                // 已释放
            }
        }
        MediaProjection activeProjection = projection;
        MediaProjection.Callback activeCallback = projectionCallback;
        projection = null;
        projectionCallback = null;
        if (activeProjection != null) {
            if (activeCallback != null) {
                try {
                    activeProjection.unregisterCallback(activeCallback);
                } catch (Exception ignored) {
                    // 未注册/已注销
                }
            }
            try {
                activeProjection.stop();
            } catch (Exception ignored) {
                // 系统已停止
            }
        }
    }

    @Override
    public void onDestroy() {
        // 用户停止/服务被回收：与其它路径同一套清理
        finishOnce("", "截屏服务已停止");
        super.onDestroy();
    }

    // ---- 前台通知 --------------------------------------------------

    /** 进入前台；失败返回 false，由调用方按匹配 request 终结。 */
    private boolean startForegroundCompat() {
        if (foregroundStarted) {
            return true;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.capture_channel_name),
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.capture_notification_title))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        Notification notification = builder.build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            foregroundStarted = true;
            return true;
        } catch (Exception error) {
            // Android 14+ 缺 FOREGROUND_SERVICE_MEDIA_PROJECTION 权限时会抛；
            // 不静默吞掉，交给 onStartCommand 按匹配 request 终结。
            Log.w(TAG, "startForeground failed: " + error.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * 拒绝一次 start 时的处置（INT-02）。
     *
     * 旧实现无论有没有活动请求都走 {@code startForegroundBestEffortAndStop()}，
     * 那个方法里有 stopForeground + stopSelf：一次陈旧的启动请求就能把**正在执行的
     * 合法截图**连同 projection、通知、隐藏令牌一起停掉。
     *
     * 现在的规则：
     *   - 有活动 run：只是拒绝这一次 start，绝不 stopSelf、不 stopForeground、
     *     不碰当前 projection / 隐藏令牌；重复的当前请求保持幂等；
     *   - 没有活动 run（纯空启动）：按 startId 清理 —— 先满足前台期限（系统要求
     *     startForegroundService 后必须尽快 startForeground），再用 stopSelf(startId)
     *     只结束这一次 start，而不是无条件 stopSelf()。
     */
    private void rejectStart(int startId, boolean runActive, String reason) {
        if (CaptureStartPolicy.onRejected(runActive)
                == CaptureStartPolicy.RejectAction.DROP_START_ONLY) {
            Log.i(TAG, "reject start " + startId + " while run active: " + reason);
            return;
        }
        // 没有任何活动 run 的空启动：满足前台期限后只清掉这一次 start
        startForegroundCompat();
        stopForegroundCompat();
        stopSelf(startId);
    }

    private void stopForegroundCompat() {
        if (!foregroundStarted) {
            return;
        }
        foregroundStarted = false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
        } catch (Exception ignored) {
            // 已停止
        }
    }
}
