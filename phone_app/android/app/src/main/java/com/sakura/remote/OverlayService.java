package com.sakura.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * 桌面立绘悬浮窗（带内嵌对话）。
 *
 * Android 不允许应用把内容画到桌面上，唯一可行的是悬浮窗
 * （SYSTEM_ALERT_WINDOW / 「显示在其他应用上层」）。配合前台服务 + 常驻通知，
 * 返回桌面或切到别的应用后依然存活。
 *
 * 窗口构成：
 *   ┌──────────────────────────┐
 *   │ ⠿ 拖动        [对话][收起] │  ← 原生标题栏（WebView 会吃掉触摸，所以独立出来）
 *   ├──────────────────────────┤
 *   │                          │
 *   │   立绘 + 对话气泡 + 输入栏   │  ← WebView 加载插件的 ?mode=overlay 页面
 *   │                          │
 *   └──────────────────────────┘ ◢ 右下角原生缩放手柄
 *
 * 复用插件页面意味着「语气表情、语音播放、图片上传、缩放」都不用再实现一遍。
 */
public class OverlayService extends Service implements CaptureCoordinator.Target {

    public static final String EXTRA_REFRESH = "refresh";
    public static final String EXTRA_RESCALE = "rescale";
    /** 用户主动关闭桌面立绘（通知栏按钮）。 */
    public static final String EXTRA_STOP = "stop";
    /** 进入时加载的页面地址。 */
    public static final String EXTRA_URL = "url";

    private static final String CHANNEL_ID = "sakura_pet";
    private static final int NOTIFICATION_ID = 0x5341;
    private static final long PORTRAIT_REFRESH_MS = 45_000L;

    /** 供网页查询桌面立绘是否在运行；服务进程内单实例，用静态标记最简单可靠。 */
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    /**
     * 窗口当前是否真的挂在 WindowManager 上。
     *
     * 授权 Activity 用它做「有上限的等待」：先确认悬浮窗移除，再申请系统授权，
     * 而不是靠固定延时猜。
     */
    public static boolean isOverlayWindowAttached() {
        OverlayService instance = current;
        if (instance == null) {
            return false;
        }
        if (instance.visibility != null) {
            return instance.visibility.isWindowAttached();
        }
        return instance.rootView != null && instance.rootView.isAttachedToWindow();
    }

    /**
     * 截屏期间临时隐藏悬浮窗（RV-09：隐藏理由归属发起它的 requestId）。
     *
     * 为什么必须隐藏：MediaProjection 拍的是整块屏幕，悬浮窗也在上面，
     * 不隐藏就会把学姐和对话框一起截进去。用 static 是因为
     * CaptureService 拿不到 OverlayService 实例。
     *
     * 这是**独立于授权隐藏**的一个理由：结束截图只释放它自己，不会顺带
     * 撤销或触发另一个理由的窗口状态。token 不匹配时释放无效 —— 旧任务
     * 结束时不会把新任务刚建立的隐藏理由清掉。
     */
    public static void setHiddenForCapture(String requestId, boolean hidden) {
        OverlayService instance = current;
        if (instance != null && instance.visibility != null) {
            if (hidden) {
                instance.visibility.acquireCaptureHidden(requestId);
            } else {
                instance.visibility.releaseCaptureHidden(requestId);
            }
        }
    }

    /** 由协调器在请求终结时释放属于该 requestId 的截图隐藏理由。 */
    public static void releaseCaptureHidden(String requestId) {
        OverlayService instance = current;
        if (instance != null && instance.visibility != null) {
            instance.visibility.releaseCaptureHidden(requestId);
        }
    }

    /** 当前存活的实例，供静态方法回调（截屏服务拿不到实例）。 */
    private static volatile OverlayService current;

    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private FrameLayout rootView;
    private PetWindow petWindow;
    /** 内容形态切换器（完整页面 / 有界状态卡片）。 */
    private OverlaySurface overlaySurface;
    /** 渲染可用性 → 窗口挂载的协调器（FIX-BLANK-01）。 */
    private OverlaySurfaceHost overlayHost;
    /** 当前窗口形态；DETACHED 用 null 表示。 */
    private OverlayVisibilityController.WindowPlan currentPlan;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * 网页渲染信号的主线程路由器（FIX-RUNTIME-01）。
     *
     * 桥线程调用 → 本路由器 post 主线程 → {@link RenderSink} 落地。
     * 曾经这里没有路由器：RemoteBridge 同步直调 ServiceBridgeHost，
     * 于是在桥线程上改 WebView Visibility + WindowManager，真机上信号全丢。
     */
    private RenderSignalRouter renderRouter;

    /** 去敏限频 Log 诊断（release 也保留；不开 WebView debug）。 */
    private final RuntimeDiagnostics diagnostics = new RuntimeDiagnostics();

    /** 上一次已记入诊断的渲染阶段（避免重复输出同一阶段）。 */
    private String lastLoggedPhase = "";
    private boolean lastLoggedAttached;
    private boolean lastLoggedCardAllowed;
    private boolean lastLoggedHeartbeat;

    /**
     * 窗口显示状态的唯一控制器。
     *
     * shouldAttach = 用户启用 && 服务存活 && !授权隐藏 && !截图隐藏。
     * 所有 addView/removeView 都经过它，不再有手写的 attached 判断。
     */
    private OverlayVisibilityController visibility;

    /** 悬浮窗入口发起的截图请求 id / 页面会话，用于结果路由。 */
    private volatile String captureRequestId = "";
    private volatile String capturePageSession = "";

    /** 悬浮窗页面在会话登记表里的宿主 id。 */
    private static final String PAGE_ID_OVERLAY = "overlay";

    // ---- 精确局部穿透（API33+ AttachedSurfaceControl）----
    /** 原生输入区域控制器；null 表示窗口还没建好。 */
    private OverlayTouchRegionController touchRegion;
    private boolean accessibilityWindow;

    /** System/user connect or revoke; reuse the existing WebView and all state. */
    static void onPortraitAccessChanged() {
        OverlayService service = current;
        if (service == null) return;
        service.handler.post(service::refreshPortraitWindowOwner);
    }

    private void refreshPortraitWindowOwner() {
        if (rootView == null || layoutParams == null || windowManager == null) return;
        WindowManager access = PortraitOverlayAccess.windowManager();
        boolean wantAccess = access != null;
        if (accessibilityWindow == wantAccess) return;
        boolean attached = rootView.isAttachedToWindow();
        OverlayVisibilityController.WindowPlan plan = currentPlan;
        if (attached) {
            try { windowManager.removeViewImmediate(rootView); }
            catch (RuntimeException ignored) { }
        }
        accessibilityWindow = wantAccess;
        windowManager = wantAccess ? access
                : (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        layoutParams.type = wantAccess ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        // A service token must never leak into the ordinary WindowManager.
        layoutParams.token = null;
        layoutParams.alpha = 1f;
        passThroughAlpha.reset();
        if (attached && plan != null && !attachWindowNow(plan)) {
            // Token revoked during migration, or ROM rejected this window type.
            // Keep the old safe path usable rather than losing the portrait.
            accessibilityWindow = false;
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
            layoutParams.token = null;
            attachWindowNow(plan);
        }
        applyPassThrough(passThroughRequested);
    }
    /** 精确穿透当前是否启用（由网页开关驱动）。 */
    private volatile boolean passThroughRequested;
    /**
     * 网页侧当前已知的 desired；用于避免 App↔浮窗偏好同步时把同一状态来回推。
     * 由网页经桥调用 {@link #setPassThroughEnabled(boolean)} 时更新。
     */
    private volatile Boolean pagePassThroughDesired;
    /**
     * 穿透的窗口 alpha 安全阈值计算（幂等：原始 alpha 只保存一次）。
     */
    private final PassThroughAlpha passThroughAlpha = new PassThroughAlpha();
    /** 最近一次安全上限与是否真的降低了 alpha（诊断/提示用）。 */
    private volatile float lastSafeAlpha = 1f;
    private volatile boolean lastAlphaReduced;
    /** 最近一次 alpha 恢复/降级结果（稳定错误码，仅诊断）。 */
    private volatile String lastRestoreResult = "init";
    /** 上一次已写入诊断的 alpha 状态签名（避免重复刷日志）。 */
    private String lastAlphaLogged = "";
    /** 最近一次区域应用/拒绝的结果，供网页显示状态。 */
    private volatile long regionRevision = -1L;
    private volatile int regionRectCount = 0;
    private volatile boolean regionFallbackFull = false;
    private volatile String regionLastError = "";
    /** 区域 ACK 的最近结果（去敏诊断：是否应用、revision、矩形数、回退、错误码）。 */
    private volatile boolean regionAckApplied;
    private volatile long regionAckRevision = -1L;
    private volatile int regionAckRects;
    private volatile boolean regionAckFallback;
    private volatile String regionAckError = "";
    /** 偏好同步次数（去敏计数：App/浮窗写入成功几次）。 */
    private volatile int prefsSyncCount;
    /** 上一次已写入诊断的区域结果签名（避免每秒重复刷 disabled/null-region 日志）。 */
    private String lastRegionLogged = "";

    private int screenWidth;
    private int screenHeight;
    private float windowWidthRatio;
    private float windowHeightRatio;
    private String pendingUrl = "";

    /*
     * 窗口位置与尺寸的快照（设备像素）。
     *
     * overlayLayoutInfo() 会被 JS 桥线程直接调用，而 layoutParams 是 UI 对象、
     * 只能在主线程读 —— 所以每次 updateLayout() 顺手把值抄一份到这里。
     * volatile 保证桥线程能看到最新值。
     */
    private volatile int lastX;
    private volatile int lastY;
    private volatile int lastW;
    private volatile int lastH;

    /*
     * 拖动时攒下的亚像素余量。
     *
     * WindowManager 的 x/y 必须是整数，而手指每帧的位移常常是小数 ——
     * 直接取整会把不足 1px 的部分丢掉，累积起来就是「跟不上手」。
     * 把余量留在这里，凑够 1px 再进位。
     */
    private float moveRemainX;
    private float moveRemainY;

    /**
     * 保护 moveRemainX/Y：网页（JavaBridge 线程）累加，主线程的帧回调读取。
     */
    private final Object moveLock = new Object();

    /**
     * 是否已有一次「刷位移」任务排在主线程队列里。
     *
     * 用 AtomicBoolean 而不是普通 boolean：它是**桥线程写、主线程清**，
     * 普通 boolean 的可见性与竞态会让标志永久卡住（之前 Choreographer 那版
     * 就是这么坏掉的，表现为窗口一顿一顿地跳）。
     * getAndSet/compareAndSet 是原子的，不存在这个窗口。
     */
    private final java.util.concurrent.atomic.AtomicBoolean moveFlushPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** 拖动位移是否已排入下一帧（避免同一帧重复排）。 */
    /**
     * 是否正在拖动窗口（由网页在拖动开始/结束时告知）。
     *
     * 为什么需要：窗口尺寸随时可能变（网页每 400ms 会按立绘和对话框内容重算一次），
     * 而 resize 之后必须把窗口夹回屏幕内，否则会跑到屏幕外看不见。
     * 但拖动过程中这个夹取会把用户刚拖出来的位移**整个抵消**掉 ——
     * 表现就是「拖不动，得先点一下再拖」。
     * 所以拖动期间跳过位置夹取，松手后再由下一次 resize 正常校正。
     */
    private volatile boolean dragInProgress;

    /** 网页在拖动开始/结束时调用。 */
    public void setDragging(boolean dragging) {
        dragInProgress = dragging;
    }


    /** 迷你图标（小圆头像）的直径。 */
    private int bubbleSize;

    /**
     * 小球圆图标的边长，单位是**设备像素**。
     *
     * 刻意不用 dp()：dp 会随屏幕密度变化 —— 手机上密度 2.75 时 dp(56) 得到
     * 154 设备像素，而低密度的平板上同样的 56dp 会得到完全不同的物理大小，
     * 实测会变成一个巨大的头像。固定设备像素后，任何密度下图标都一样大。
     *
     * 数值取 154：与手机上 dp(56) 在密度 2.75 下的观感一致。
     */
    private static final int BUBBLE_ICON_PX = 154;

    /**
     * 授权流程期间临时隐藏悬浮窗（token = requestId）。
     *
     * MIUI 会在「本应用正在显示系统悬浮窗」时压掉系统的授权确认框。
     * 这是**独立于截图隐藏**的一个理由，由 OverlayVisibilityController 统一裁决；
     * 结束授权只释放自己这个 token，不会撤销截图隐藏，也不会把用户关掉的桌宠重开。
     */
    public static void acquirePermissionHidden(String requestId) {
        OverlayService service = current;
        if (service != null && service.visibility != null) {
            service.visibility.acquirePermissionHidden(requestId);
        }
    }

    /** 只有持有该 token 的流程才能释放授权隐藏（RV-08：取消者不清新任务的隐藏理由）。 */
    public static void releasePermissionHidden(String requestId) {
        OverlayService service = current;
        if (service != null && service.visibility != null) {
            service.visibility.releasePermissionHidden(requestId);
        }
    }

    /** 是否处于迷你图标模式。 */
    private boolean bubbleMode;
    /** 缩成图标前的窗口尺寸与位置，用于恢复。 */
    private int expandedWidth;
    private int expandedHeight;
    private int expandedX;
    private int expandedY;

    /** 完整窗口的页面地址（不含 mode 参数）。 */

    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            // 页面自己会按需刷新，这里只做保活心跳
            handler.postDelayed(this, PORTRAIT_REFRESH_MS);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        current = this;
        // 注意：这里不改 PetConfig.overlayEnabled。
        // 那是「用户意图」，只由用户操作（开关按钮 / 通知栏关闭）变更；
        // 系统回收进程时不应改掉它，否则下次打开 App 就不会自动拉起悬浮窗了。
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        measureScreen();
        windowWidthRatio = PetConfig.windowWidthRatio(this);
        windowHeightRatio = PetConfig.windowHeightRatio(this);
        bubbleSize = BUBBLE_ICON_PX;   // 固定设备像素，不用 dp（见常量说明）
        createNotificationChannel();
        // 显示状态的唯一裁决者：所有 attach/detach 都经过它
        visibility = new OverlayVisibilityController(new OverlayVisibilityController.Host() {
            @Override
            public boolean isWindowAttached() {
                return rootView != null && rootView.isAttachedToWindow();
            }

            @Override
            public OverlayVisibilityController.WindowPlan attachedPlan() {
                return rootView != null && rootView.isAttachedToWindow() ? currentPlan : null;
            }

            @Override
            public boolean attachWindow(OverlayVisibilityController.WindowPlan plan) {
                return attachWindowNow(plan);
            }

            @Override
            public void detachWindow() {
                detachWindowNow();
            }
        });
        visibility.setUserEnabled(PetConfig.overlayEnabled(this));
        visibility.setServiceAlive(true);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {
            PetConfig.setOverlayEnabled(this, false);
            if (visibility != null) {
                visibility.setUserEnabled(false);
            }
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            String url = intent.getStringExtra(EXTRA_URL);
            if (url != null && !url.isEmpty()) {
                pendingUrl = url;
            }
        }
        startAsForeground();
        if (rootView == null) {
            if (!attachOverlay()) {
                PetConfig.setOverlayEnabled(this, false);
                stopSelf();
                return START_NOT_STICKY;
            }
            handler.post(refreshTask);
        } else {
            if (intent != null && intent.getBooleanExtra(EXTRA_RESCALE, false)) {
                windowWidthRatio = PetConfig.windowWidthRatio(this);
                windowHeightRatio = PetConfig.windowHeightRatio(this);
                applyWindowSize();
            }
            // 每次都重新加载：悬浮窗的 WebView 是长驻的，
            // 只创建一次的话改了页面（CSS/JS）它还会一直跑旧版，
            // 排查时极易误判成「改动没生效」。
            if (!pendingUrl.isEmpty() && petWindow != null) {
                final String url = withMode(pendingUrl, "overlay");
                pendingUrl = "";
                handler.post(() -> petWindow.load(url));
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        currentPlan = null;
        // 悬浮窗页面结束：登记会话失效，在途请求不再投递到已消失的页面
        CaptureCoordinator.get().sessions().invalidate(PAGE_ID_OVERLAY);
        // 先解除 surface 监听，避免继续引用失效的窗口
        if (touchRegion != null) {
            touchRegion.destroy();
            touchRegion = null;
        }
        passThroughRequested = false;
        // FIX-RUNTIME-01/04：销毁后不再接纳任何桥线程封送过来的渲染信号。
        renderRouter = null;
        if (petWindow != null) {
            // destroy 内部会停掉心跳与已排期的重试，销毁后不得再有 loadUrl/挂窗
            petWindow.destroy();
            petWindow = null;
        }
        overlayHost = null;
        overlaySurface = null;
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
                // 视图可能已被系统移除
            }
        }
        rootView = null;
        running = false;
        if (visibility != null) {
            // 销毁后任何迟到回调都不得再重加窗口
            visibility.destroy();
        }
        current = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 屏幕旋转 / 尺寸变化：重新量屏幕并把窗口夹回可见范围内。
     *
     * 悬浮窗服务不会因为旋转被重建，所以必须在配置变化时主动同步物理边界，
     * 否则横屏后竖屏坐标会把窗口留在屏幕外（用户以为桌宠丢了）。
     */
    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        handler.post(() -> {
            if (windowManager == null) {
                return;
            }
            measureScreen();
            if (layoutParams != null && rootView != null) {
                clampWindowPosition();
                updateLayout();
            }
            // 旋转后视口与内容几何都变了：通知前端重算输入区域/尺寸
            if (petWindow != null) {
                try {
                    petWindow.webView().evaluateJavascript(
                            "if (window.SakuraTouchRegion && window.SakuraTouchRegion.notify)"
                                    + " { window.SakuraTouchRegion.notify('rotation'); }",
                            null);
                } catch (Exception ignored) {
                    // 网页可能已销毁
                }
            }
        });
    }

    // ---- 窗口 attach/detach（只由 OverlayVisibilityController 调用）----

    /**
     * 把窗口挂回 WindowManager（幂等），并按目标形态设定尺寸与内容。
     *
     * 形态由 {@link OverlayVisibilityController} 裁决：
     *   - {@link OverlayVisibilityController.WindowPlan#FULL}：只在确认有可见立绘时出现，
     *     用网页上报/保存的尺寸；
     *   - {@link OverlayVisibilityController.WindowPlan#CARD}：有界可见的加载/恢复卡片，
     *     固定尺寸，绝不可能是「透明大矩形」；
     *   - {@link OverlayVisibilityController.WindowPlan#DETACHED} 由 detachWindowNow 处理。
     */
    private boolean attachWindowNow(OverlayVisibilityController.WindowPlan plan) {
        if (windowManager == null || rootView == null || layoutParams == null
                || plan == null || plan == OverlayVisibilityController.WindowPlan.DETACHED) {
            return false;
        }
        applyWindowShape(plan);
        if (!rootView.isAttachedToWindow()) {
            try {
                windowManager.addView(rootView, layoutParams);
            } catch (Exception error) {
                return false;
            }
        } else {
            try {
                windowManager.updateViewLayout(rootView, layoutParams);
            } catch (Exception error) {
                return false;
            }
        }
        currentPlan = plan;
        if (plan == OverlayVisibilityController.WindowPlan.FULL) {
            // READY 升级 FULL：窗口尺寸改变，页面与 touch region 重新计算。
            notifyContentGeometryChanged("shape");
        }
        // 窗口挂上后确保渲染心跳在跑（FIX-RUNTIME-04：心跳现在归 PetWindow 所有，
        // 不再依赖「窗口是否 attached」续期；detach 时由 detachWindowNow 停掉）。
        if (petWindow != null) {
            petWindow.startHeartbeat();
        }
        logPlan();
        return true;
    }

    /** 按形态设置窗口尺寸与内容（FULL = 完整页面，CARD = 有界卡片）。 */
    private void applyWindowShape(OverlayVisibilityController.WindowPlan plan) {
        boolean card = plan == OverlayVisibilityController.WindowPlan.CARD;
        if (overlaySurface != null) {
            overlaySurface.setMode(card ? OverlaySurface.Mode.CARD : OverlaySurface.Mode.FULL);
        }
        if (layoutParams == null) {
            return;
        }
        if (bubbleMode) {
            layoutParams.width = bubbleSize;
            layoutParams.height = bubbleSize;
        } else if (card) {
            int width = overlaySurface != null ? overlaySurface.cardWidthPx() : dp(236);
            int height = overlaySurface != null ? overlaySurface.cardHeightPx() : dp(148);
            layoutParams.width = Math.max(dp(160), Math.min(screenWidth, width));
            layoutParams.height = Math.max(dp(96), Math.min(screenHeight, height));
        } else {
            int width = Math.max(dp(220), Math.round(screenWidth * windowWidthRatio));
            int height = Math.max(dp(260), Math.round(width * windowHeightRatio));
            int wantW = PetConfig.wantWindowWidth(this);
            int wantH = PetConfig.wantWindowHeight(this);
            if (wantW > 0 && wantH > 0) {
                float density = getResources().getDisplayMetrics().density;
                if (density <= 0f) {
                    density = 1f;
                }
                width = Math.max(dp(160), Math.min(screenWidth, Math.round(wantW * density)));
                height = Math.max(dp(160), Math.min(screenHeight, Math.round(wantH * density)));
            }
            layoutParams.width = width;
            layoutParams.height = height;
        }
        if (layoutParams.width > screenWidth) {
            layoutParams.width = screenWidth;
        }
        if (layoutParams.height > screenHeight) {
            layoutParams.height = screenHeight;
        }
        clampWindowPosition();
        updateLayout();
    }

    /** 把窗口移出 WindowManager；幂等。 */
    private void detachWindowNow() {
        currentPlan = null;
        // FIX-RUNTIME-04：窗口被隐藏（用户关闭 / 授权隐藏 / 截图隐藏）时停掉渲染心跳，
        // 不越过用户的隐藏意图继续推进时间线或自动重试。
        if (petWindow != null) {
            petWindow.stopHeartbeat();
        }
        if (windowManager == null || rootView == null) {
            return;
        }
        if (!rootView.isAttachedToWindow()) {
            return;
        }
        try {
            windowManager.removeView(rootView);
        } catch (Exception ignored) {
            // 可能已经被系统移除
        }
        logPlan();
    }

    /** 心跳是否在跑（去敏诊断用）。 */
    boolean isHeartbeatRunning() {
        return petWindow != null && petWindow.isHeartbeatRunning();
    }

    /** 供 OverlaySurfaceHost 在形态变化时请求调整窗口尺寸。 */
    private boolean applyShapeForMode(OverlaySurface.Mode mode) {
        if (layoutParams == null || rootView == null) {
            return false;
        }
        applyWindowShape(mode == OverlaySurface.Mode.CARD
                ? OverlayVisibilityController.WindowPlan.CARD
                : OverlayVisibilityController.WindowPlan.FULL);
        currentPlan = mode == OverlaySurface.Mode.CARD
                ? OverlayVisibilityController.WindowPlan.CARD
                : OverlayVisibilityController.WindowPlan.FULL;
        if (mode == OverlaySurface.Mode.FULL) {
            // 卡片 → 完整窗口：视口尺寸已变，页面与 touch region 必须重算。
            notifyContentGeometryChanged("shape");
        }
        return true;
    }

    // ---- 悬浮窗 ----------------------------------------------------

    private void measureScreen() {
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;
    }

    private boolean attachOverlay() {
        petWindow = new PetWindow(this);
        // 网页和 App 里一样需要 bridge（缩放同步、开启开关等）
        RemoteBridge bridge = new RemoteBridge(new ServiceBridgeHost());
        bridge.setRenderDiagnosticsProvider(() -> petWindow.diagnosticsSnapshot());
        // FIX-RUNTIME-01：网页渲染信号必须先封送到主线程，再落到 RenderSink。
        // 旧实现是桥线程同步直调宿主改 View/WindowManager，真机上信号全部丢失。
        bridge.setRenderSignalSink(new RenderSink());
        renderRouter = bridge.renderRouter();
        petWindow.addJavascriptBridge(bridge);

        // 根容器带「外部触摸 / 返回键 / 失焦」识别：输入焦点不能永远挂在悬浮窗上。
        // 仍然是一个 FrameLayout，布局与几何不变。
        OverlayFocusRootView container = new OverlayFocusRootView(this);
        // 圆角 + 裁剪：让窗口四角不再是直角矩形，弱化「框」的感觉
        android.graphics.drawable.GradientDrawable windowBg =
                new android.graphics.drawable.GradientDrawable();
        windowBg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        windowBg.setColor(0x00000000);
        windowBg.setCornerRadius(dp(18));
        container.setBackground(windowBg);
        container.setClipToOutline(true);
        container.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });

        rootView = container;

        // 焦点交还的三条公开路径：
        //   1) 窗口外的 DOWN（FLAG_WATCH_OUTSIDE_TOUCH → ACTION_OUTSIDE）
        //   2) 返回键（在悬浮窗里等价于「收起键盘 / 退出输入态」）
        //   3) 窗口失焦（用户切到别的应用）
        // 三条都走同一个 releaseInputFocus，只在实际可聚焦时才动手，
        // 不会与网页新发起的输入请求或其他窗口抢焦点。
        container.setFocusListener(new OverlayFocusRootView.Listener() {
            @Override
            public void onOutsideTouch() {
                releaseInputFocus("outside-touch");
            }

            @Override
            public boolean onBackKey() {
                return releaseInputFocus("back-key");
            }

            @Override
            public void onWindowFocusLost() {
                releaseInputFocus("window-blur");
            }
        });

        // 形态切换器 + 渲染可用性协调器：容器内容只有「完整页面」或「有界卡片」，
        // 不存在「WebView 隐藏但窗口仍透明挂载」这一态。
        overlaySurface = new OverlaySurface(this, petWindow.view());
        overlaySurface.bindContainer(container);
        overlayHost = new OverlaySurfaceHost(petWindow, overlaySurface, visibility,
                new OverlaySurfaceHost.LayoutHost() {
                    @Override
                    public boolean applyShape(OverlaySurface.Mode mode) {
                        return applyShapeForMode(mode);
                    }

                    @Override
                    public void requestStopOverlay() {
                        PetConfig.setOverlayEnabled(OverlayService.this, false);
                        handler.post(() -> {
                            if (visibility != null) {
                                visibility.setUserEnabled(false);
                            }
                        });
                        stopSelf();
                    }
                });

        WindowManager access = PortraitOverlayAccess.windowManager();
        accessibilityWindow = access != null;
        if (accessibilityWindow) windowManager = access;
        int type = accessibilityWindow ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                : Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        // 初始尺寸就是**有界卡片**：窗口第一次出现时只会是可见的小卡片，
        // 不可能在页面还没渲染时先挂一块 960×1216 的透明矩形。
        int width = overlaySurface.cardWidthPx();
        int height = overlaySurface.cardHeightPx();

        layoutParams = new WindowManager.LayoutParams(
                width,
                height,
                type,
                // 默认不可获取焦点：否则会抢走其他应用的输入。
                // 网页里点输入框时再通过 onInputFocusChanged 临时打开。
                //
                // 但「不可聚焦」必须始终与「非触摸模态」成对出现：
                // FLAG_NOT_FOCUSABLE 隐含 FLAG_NOT_TOUCH_MODAL，以前清掉前者时
                // 没有显式补回后者，窗口就变成「可聚焦 + 触摸模态」，会吞掉整个
                // 屏幕的触摸（真机表现：点开对话框后除立绘外都点不动）。
                // 策略集中在 OverlayInputFocus，任何切换都保留非触摸模态；
                // FLAG_WATCH_OUTSIDE_TOUCH 让窗口外的 DOWN 回调进来，用于主动交还焦点。
                OverlayInputFocus.baseFlags(),
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        // 新窗口基准必须干净：显式 alpha=1，并清掉上一个窗口保存的穿透 alpha，
        // 避免「之前保存值污染新窗口基准」。
        layoutParams.alpha = 1f;
        passThroughAlpha.reset();
        lastAlphaReduced = false;
        lastRestoreResult = "new-window";

        int savedX = PetConfig.posX(this);
        int savedY = PetConfig.posY(this);
        if (savedX < 0 || savedY < 0) {
            layoutParams.x = Math.max(0, screenWidth - width - dp(10));
            // 默认放偏上一点，别贴屏幕底：
            // screenHeight 是整屏高度（含状态栏/手势区），从底部往上算会偏低。
            layoutParams.y = Math.max(dp(40), Math.round(screenHeight * 0.18f));
        } else {
            // 旋转/换屏后旧坐标可能已经在屏幕外：夹回「至少留 dp(48) 可见」
            layoutParams.x = savedX;
            layoutParams.y = savedY;
            clampWindowPosition();
        }

        // 初始挂载交给状态控制器裁决：此刻渲染可用性为 false（还没确认可见立绘），
        // 控制器只会挂**有界可见卡片**；等网页确认立绘可见才自动升级为完整窗口。
        //
        // FIX-RUNTIME-03：这里必须先刷新一次卡片内容，让 OverlaySurfaceHost 把
        // 「加载期允许卡片」写进可见性控制器，再调用 setServiceAlive —— 否则
        // 初始裁决会是 DETACHED，窗口与 WebView 都不参与布局，
        // 「等可见才挂载」与「先挂载才能渲染」互相等待，立绘预加载无法推进。
        if (overlayHost != null) {
            // 去敏限频快照记录器：PetWindow 每次 notifyAvailability/tick 通知都写
            // 白名单快照（网络/HTTP/JS/重试/形态/真实 root attach），现场只看
            // logcat 就能判定失败分支，不必再靠猜。
            overlayHost.setAvailabilityRecorder(this::logRenderSnapshot);
            overlayHost.renderCardNow();
        }
        visibility.setUserEnabled(PetConfig.overlayEnabled(this));
        visibility.setServiceAlive(true);

        // 精确局部穿透：API33+ 才有公开的 setTouchableRegion。
        // 控制器自己处理 attach/detach、陈旧 revision、空区域回退。
        touchRegion = new OverlayTouchRegionController(rootView, petWindow.webView(),
                new OverlayTouchRegionController.Host() {
                    @Override
                    public void onTouchRegionApplied(long revision, int rectCount,
                                                     boolean fallbackFull) {
                        regionRevision = revision;
                        regionRectCount = rectCount;
                        regionFallbackFull = fallbackFull;
                        regionLastError = "";
                        notifyRegionAck(true, revision, rectCount, fallbackFull, "");
                    }

                    @Override
                    public void onTouchRegionRejected(String reason) {
                        regionLastError = reason == null ? "rejected" : reason;
                        notifyRegionAck(false, regionRevision, regionRectCount,
                                regionFallbackFull, regionLastError);
                    }

                    @Override
                    public void onTouchRegionUnsupported(String reason) {
                        regionLastError = "unsupported:" + reason;
                        notifyRegionAck(false, regionRevision, regionRectCount,
                                regionFallbackFull, regionLastError);
                        // 机型/ROM 永久降级：精确穿透已不可能，必须立刻把窗口 alpha
                        // 恢复，否则用户会「白变淡」却得不到任何点击穿透。
                        onPassThroughDegraded("unsupported");
                    }
                });
        if (passThroughRequested) {
            // 服务重建/切换 URL 后用户原本开着穿透：补一次启用
            handler.post(() -> applyPassThrough(true));
        }

        String url = pendingUrl;
        if (url.isEmpty()) {
            url = buildOverlayUrl();
        }
        pendingUrl = "";
        // 必须在这里补上 mode=overlay。
        //
        // pendingUrl 是「基础页面地址」（PetConfig.chatUrl，形如 .../?token=xxx），
        // 不带 mode 参数。原来这里直接 petWindow.load(url) —— 于是**首次创建窗口**
        // 时加载的是不带 mode 的基础页面：网页看到 mode 不是 overlay，
        // 就不会给 body 加 overlay-mode，结果 #stage / #portraitWrap 被判成
        // 「App 内模式」而 display:none。
        // 表现就是：窗口尺寸对的，但里面空空的 —— 立绘不显示，只剩一块
        // 半透明方框。窗口已存在时的切换路径（onStartCommand）本来就加了 mode，
        // 所以只有「首次开启桌面立绘」会踩到，很容易漏。
        petWindow.load(withMode(url, "overlay"));
        return true;
    }

    /**
     * 区域结果并入渲染诊断（去敏：成功/失败、revision、矩形数、简化级别、回退与错误码）。
     *
     * <p>只在结果**真的变化**时写日志：旧实现每次轮询都会报一遍
     * 「disabled / null region 成功」，logcat 每秒刷一条，反而掩盖真正的问题。
     * 不改变可见性裁决：区域只影响输入命中的精确度，不影响「窗口里有没有内容」。
     */
    private void notifyRegionAck(boolean applied, long revision, int rectCount,
                                 boolean fallbackFull, String error) {
        regionAckApplied = applied;
        regionAckRevision = revision;
        regionAckRects = rectCount;
        regionAckFallback = fallbackFull;
        regionAckError = error == null ? "" : error;
        int level = touchRegion != null ? touchRegion.touchRegionLevel() : 0;
        float tolerance = touchRegion != null ? touchRegion.touchRegionTolerance() : 0f;
        String signature = applied + "|" + revision + "|" + rectCount + "|" + fallbackFull
                + "|" + level + "|" + regionAckError;
        if (signature.equals(lastRegionLogged)) {
            return;
        }
        lastRegionLogged = signature;
        // 去敏限频诊断：只记录稳定状态与计数，不记录任何矩形坐标、URL 或会话 id。
        diagnostics.touchRegion(applied, revision, rectCount, fallbackFull,
                regionAckError, level, tolerance);
    }

    /**
     * 插件页面的「基础地址」：不含 mode 参数。
     *
     * 用它作为切换悬浮窗/小球模式的基准 —— {@link #withMode} 会先去掉旧的
     * mode 再附加新的，所以拿裸地址或带 mode 的地址都能正确工作。
     * 统一走这里，避免再出现「某条路径忘了带 mode」的问题。
     */
    private String overlayBaseUrl() {
        return PetConfig.chatUrl(this);
    }

    /** 悬浮窗默认加载插件页面的 overlay 模式。 */
    private String buildOverlayUrl() {
        return withMode(overlayBaseUrl(), "overlay");
    }

    private void applyWindowSize() {
        if (layoutParams == null || rootView == null) {
            return;
        }
        // 小球模式下尺寸是固定的，缩放比例对它没有意义 —— 不要把它撑大
        if (bubbleMode) {
            if (layoutParams.width != bubbleSize || layoutParams.height != bubbleSize) {
                layoutParams.width = bubbleSize;
                layoutParams.height = bubbleSize;
                updateLayout();
            }
            return;
        }
        int width = Math.max(dp(220), Math.round(screenWidth * windowWidthRatio));
        int height = Math.max(dp(260), Math.round(width * windowHeightRatio));
        layoutParams.width = width;
        layoutParams.height = height;
        updateLayout();
    }

    private void updateLayout() {
        if (rootView == null || layoutParams == null) {
            return;
        }
        // 刷新给 JS 桥线程读的快照（见 overlayLayoutInfo）
        lastX = layoutParams.x;
        lastY = layoutParams.y;
        lastW = layoutParams.width;
        lastH = layoutParams.height;
        try {
            windowManager.updateViewLayout(rootView, layoutParams);
        } catch (Exception ignored) {
            // 视图可能已被移除
        }
    }

    /**
     * 在「完整窗口」与「桌面小图标」之间切换。
     *
     * 小图标模式复用同一个页面（?mode=bubble），只显示一个圆形头像，
     * 点它就能恢复 —— 不需要额外准备图标资源。
     */
    private void setBubbleMode(final boolean bubble) {
        // 桥接线程调进来的，View/WindowManager 操作必须回主线程
        handler.post(() -> applyBubbleMode(bubble));
    }

    private void applyBubbleMode(boolean bubble) {
        if (petWindow == null || layoutParams == null) {
            return;
        }
        if (bubble == bubbleMode) {
            return;
        }
        bubbleMode = bubble;
        if (bubble) {
            // 记住当前尺寸与位置，恢复时用
            expandedWidth = layoutParams.width;
            expandedHeight = layoutParams.height;
            expandedX = layoutParams.x;
            expandedY = layoutParams.y;
            layoutParams.width = bubbleSize;
            layoutParams.height = bubbleSize;
            petWindow.load(withMode(overlayBaseUrl(), "bubble"));
        } else {
            layoutParams.width = expandedWidth > 0
                    ? expandedWidth : Math.max(dp(220), Math.round(screenWidth * windowWidthRatio));
            layoutParams.height = expandedHeight > 0
                    ? expandedHeight : Math.round(layoutParams.width * windowHeightRatio);
            layoutParams.x = expandedX;
            layoutParams.y = expandedY;
            petWindow.load(withMode(overlayBaseUrl(), "overlay"));
        }
        updateLayout();
    }

    /** 给地址加上/替换 mode 参数。 */
    private static String withMode(String url, String mode) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        String cleaned = url.replaceAll("[?&]mode=[^&]*", "");
        String separator = cleaned.contains("?") ? "&" : "?";
        return cleaned + separator + "mode=" + mode;
    }

    // ---- 手机截屏 --------------------------------------------------

    /**
     * 悬浮窗发起一次手机截屏。
     *
     * MediaProjection 授权必须由 Activity 走 startActivityForResult 拿。
     * 这里不再「借」MainActivity（那样结果会被固定绑到 App 的 WebView），
     * 而是启动独立的 {@link CapturePermissionActivity}，并把 requestId 与
     * 页面会话交给 {@link CaptureCoordinator}；结果只送回**本悬浮窗的网页**。
     *
     * 后台启动 Activity：本应用持有 SYSTEM_ALERT_WINDOW，属于 Android 10+
     * 后台启动限制的豁免情形；仍可能被个别 ROM 拒绝，那时明确报错，不静默吞。
     */
    void requestScreenshot(String pageSessionId) {
        CaptureCoordinator coordinator = CaptureCoordinator.get();
        if (coordinator.isBusy()) {
            deliverScreenshot("", "已有截图任务正在进行，请稍候");
            return;
        }
        String session = pageSessionId == null ? "" : pageSessionId;
        // 会话必须已由本悬浮窗网页登记且仍有效，否则是旧页面的迟到请求
        if (!session.isEmpty()
                && !coordinator.sessions().isCurrent(PAGE_ID_OVERLAY, session)) {
            deliverScreenshot("", "页面已刷新，请重试");
            return;
        }
        capturePageSession = session;
        CaptureRequestRegistry.Request request = coordinator.begin(
                CaptureCoordinator.Origin.OVERLAY, capturePageSession, this);
        if (request == null) {
            deliverScreenshot("", "已有截图任务正在进行，请稍候");
            return;
        }
        captureRequestId = request.requestId;
        Intent intent = new Intent(this, CapturePermissionActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(CapturePermissionActivity.EXTRA_REQUEST_ID, request.requestId);
        try {
            startActivity(intent);
        } catch (Exception error) {
            coordinator.cancel(request.requestId, "无法打开截图授权页");
            deliverScreenshot("", "无法在后台打开截图授权页，请先在通知栏打开一次 App");
        }
    }

    /**
     * 截图结果回调（CaptureCoordinator.Target）：只认本服务发起的请求，
     * 且在**主线程投递瞬间**复核页面会话后才送进悬浮窗网页（RV-02）。
     */
    @Override
    public void onCaptureResult(String requestId, String dataUrl, String error) {
        if (requestId == null || !requestId.equals(captureRequestId)) {
            return;
        }
        handler.post(() -> {
            if (!requestId.equals(captureRequestId)) {
                return;
            }
            boolean sessionOk = capturePageSession == null || capturePageSession.isEmpty()
                    || CaptureCoordinator.get().sessions()
                            .isCurrent(PAGE_ID_OVERLAY, capturePageSession);
            captureRequestId = "";
            capturePageSession = "";
            if (!sessionOk) {
                return;   // 页面已刷新：结果作废，不交给新页面
            }
            deliverScreenshot(dataUrl, error);
        });
    }

    // ---- 页面会话登记（RV-02 / RV-03）-----------------------------

    /** 悬浮窗网页 boot / 导航完成时登记真实会话，并同步给触摸区域控制器。 */
    void registerPageSession(String pageSessionId) {
        CaptureCoordinator.get().registerPageSession(PAGE_ID_OVERLAY, pageSessionId);
        if (touchRegion != null) {
            touchRegion.beginSession(pageSessionId);
        }
    }

    /** 网页 pagehide / 导航开始：注销会话，终结在途请求，并让区域 revision 归零。 */
    void invalidatePageSession(String pageSessionId) {
        if (!onMainThread()) {
            final String session = pageSessionId == null ? "" : pageSessionId;
            handler.post(() -> invalidatePageSession(session));
            return;
        }
        CaptureCoordinator.get().invalidatePageSession(PAGE_ID_OVERLAY, pageSessionId);
        if (touchRegion != null) {
            touchRegion.endSession(pageSessionId);
        }
        // 页面正在离开：立绘不能再算「可见」，窗口必须立刻让位
        if (petWindow != null) {
            petWindow.onPageInvalidated();
        }
        if (overlayHost != null) {
            overlayHost.renderCardNow();
        }
    }

    // ---- 渲染可用性信号（FIX-BLANK-01 / FIX-RUNTIME-01）--------------

    /**
     * 主线程落点：网页渲染信号的**唯一**执行者。
     *
     * 由 {@link RenderSignalRouter} 在确认已封送到主线程后调用，因此这里
     * 可以安全触碰 PetWindow / OverlaySurfaceHost / WindowManager。
     *
     * 每个信号都做「会话仍登记在册」的复核：旧页面（reload 后）的迟到信号
     * 会被丢弃，绝不会把新窗口恢复出来。
     */
    private final class RenderSink implements RenderSignalSink {

        @Override
        public void onRenderPageSession(String pageSessionId) {
            if (petWindow == null) {
                logSignal(RenderSignalRouter.KIND_PAGE_SESSION, false, false, "no_window");
                return;
            }
            // 这是「新页面开始」的**第一**个信号，必须先于任何会话复核生效：
            // 旧实现只在「已登记且仍是当前会话」时推进代际，一旦登记顺序有任何
            // 偏差（pagehide 竞态、登记桥不可用），代际就永远不推进 ——
            // 之后所有 ready 信号都因代际落后被丢弃，窗口永远不显示。
            // 因此这里只在「明确是另一个已登记会话的旧页」时拒绝。
            if (isKnownStaleSession(pageSessionId)) {
                logSignal(RenderSignalRouter.KIND_PAGE_SESSION, false, false, "stale_session");
                return;
            }
            long generation = petWindow.onNewPageSession();
            if (overlayHost != null) {
                overlayHost.renderCardNow();
            }
            logSignal(RenderSignalRouter.KIND_PAGE_SESSION, true, false, "g" + generation);
        }

        @Override
        public void onRenderBootOk(String pageSessionId) {
            if (petWindow == null) {
                logSignal(RenderSignalRouter.KIND_BOOT_OK, false, false, "no_window");
                return;
            }
            if (isKnownStaleSession(pageSessionId)) {
                logSignal(RenderSignalRouter.KIND_BOOT_OK, false, false, "stale_session");
                return;
            }
            petWindow.onPageBootOk();
            logSignal(RenderSignalRouter.KIND_BOOT_OK, true, false,
                    petWindow.renderState().reason());
        }

        @Override
        public void onRenderBootFailed(String pageSessionId) {
            if (petWindow == null) {
                logSignal(RenderSignalRouter.KIND_BOOT_FAILED, false, false, "no_window");
                return;
            }
            if (isKnownStaleSession(pageSessionId)) {
                logSignal(RenderSignalRouter.KIND_BOOT_FAILED, false, false, "stale_session");
                return;
            }
            petWindow.onPageBootFailed();
            if (overlayHost != null) {
                overlayHost.renderCardNow();
            }
            logSignal(RenderSignalRouter.KIND_BOOT_FAILED, true, false,
                    petWindow.renderState().reason());
            logPlan();
        }

        @Override
        public void onRenderPortraitVisible(String pageSessionId) {
            if (petWindow == null) {
                logSignal(RenderSignalRouter.KIND_PORTRAIT_VISIBLE, false, false, "no_window");
                return;
            }
            if (isKnownStaleSession(pageSessionId)) {
                logSignal(RenderSignalRouter.KIND_PORTRAIT_VISIBLE, false, false, "stale_session");
                return;
            }
            // 带代际复核：代际不匹配的迟到 ready 只记诊断，绝不恢复新窗口。
            long generation = petWindow.loadGeneration();
            boolean accepted = petWindow.onPortraitVisibleForGeneration(generation);
            logSignal(RenderSignalRouter.KIND_PORTRAIT_VISIBLE, accepted, false,
                    accepted ? "g" + generation : "stale_generation");
            logPlan();
        }

        @Override
        public void onInvalidatePageSession(String pageSessionId) {
            invalidatePageSession(pageSessionId);
            logSignal(RenderSignalRouter.KIND_INVALIDATE, true, false, "");
        }
    }

    /** 渲染信号去敏诊断（kind 只接受稳定代码；绝不输出会话 id/URL/正文）。 */
    private void logSignal(String kind, boolean accepted, boolean deduped, String reason) {
        RenderSignalRouter router = renderRouter;
        if (router != null) {
            diagnostics.renderSignal(kind, accepted, deduped, router.allAppliedOnMainThread()
                    && router.appliedSignals() > 0, reason);
        } else {
            diagnostics.renderSignal(kind, accepted, deduped, onMainThread(), reason);
        }
    }

    /** 阶段/形态去敏诊断：只在阶段或形态真的变化时输出一次。 */
    private void logPlan() {
        String phase = petWindow == null
                ? RenderAvailabilityState.Phase.LOADING.name()
                : petWindow.renderState().phase().name();
        boolean attached = rootView != null && rootView.isAttachedToWindow();
        boolean cardAllowed = petWindow != null && petWindow.renderState().isCardAllowed();
        boolean heartbeat = petWindow != null && petWindow.isHeartbeatRunning();
        if (phase.equals(lastLoggedPhase) && attached == lastLoggedAttached
                && cardAllowed == lastLoggedCardAllowed && heartbeat == lastLoggedHeartbeat) {
            return;
        }
        lastLoggedPhase = phase;
        lastLoggedAttached = attached;
        lastLoggedCardAllowed = cardAllowed;
        lastLoggedHeartbeat = heartbeat;
        String reason = petWindow == null ? "" : petWindow.renderState().reason();
        diagnostics.renderPhaseChanged(phase, reason, cardAllowed, attached, heartbeat);
        diagnostics.windowPlan(currentPlan == null ? "detached" : currentPlan.name(), attached);
    }

    /**
     * 渲染可用性去敏快照（PetWindow notifyAvailability/tick 的落点）。
     *
     * 白名单字段：阶段/失败分类、boot 与立绘可见性、网络错误码、HTTP 状态、
     * JS 异常类型与行号、重试进度、心跳、真实 root attach 与窗口形态。
     * **不含** URL/query/token/console 正文/图片内容。
     */
    private void logRenderSnapshot() {
        if (petWindow == null) {
            return;
        }
        boolean attached = rootView != null && rootView.isAttachedToWindow();
        diagnostics.renderSnapshot(petWindow.diagnostics(), attached,
                currentPlan == null ? "detached" : currentPlan.name());
        // 关键状态转换另走 logPlan：形态/挂载变化不会被快照限频吞掉。
        logPlan();
    }

    /**
     * 窗口尺寸/形态变化后让页面与精确穿透区域重算。
     *
     * READY 升级 FULL 时窗口从固定卡片尺寸变成页面尺寸：WebView 视口随之改变，
     * 输入区域（touch region）必须按新几何重算，否则点击命中仍停留在旧（卡片）
     * 尺寸上。只发固定去敏原因码，不带任何 URL/尺寸参数。
     */
    private void notifyContentGeometryChanged(String reasonCode) {
        if (petWindow == null) {
            return;
        }
        try {
            petWindow.webView().evaluateJavascript(
                    "if (window.SakuraTouchRegion && window.SakuraTouchRegion.notify)"
                            + " { window.SakuraTouchRegion.notify('" + reasonCode + "'); }",
                    null);
        } catch (Exception ignored) {
            // 网页可能还没就绪或已销毁：重算失败不影响窗口形态
        }
    }

    private static boolean onMainThread() {
        Looper looper = Looper.myLooper();
        return looper != null && looper == Looper.getMainLooper();
    }

    private boolean isRenderSessionCurrent(String pageSessionId) {
        if (pageSessionId == null || pageSessionId.isEmpty()) {
            return false;
        }
        return CaptureCoordinator.get().sessions().isCurrent(PAGE_ID_OVERLAY, pageSessionId);
    }

    /**
     * 该信号是否**明确**来自「另一个已登记会话」的旧页面。
     *
     * 只有这种情况才拒绝：会话表里有值、且与传入值不同。空会话 id 或
     * 「登记表里还没有值」都放行 —— 首次加载时 registerPageSession 与
     * notifyOverlayPageSession 的到达顺序不保证，若把「还没登记」当成旧页面，
     * 首帧的 bootOk/portraitVisible 会被误杀，真机表现正是立绘不显示。
     */
    private boolean isKnownStaleSession(String pageSessionId) {
        if (pageSessionId == null || pageSessionId.isEmpty()) {
            return false;
        }
        return !CaptureCoordinator.get().sessions().isCurrent(PAGE_ID_OVERLAY, pageSessionId);
    }

    /**
     * 渲染诊断快照（JSON，去敏）。
     *
     * 只包含稳定代码：页面阶段/失败分类、boot 与立绘是否可见、网络错误码、
     * HTTP 状态、JS 异常类型与行号、区域 ACK 结果与会话代际。
     * **不含** URL、query、token、聊天内容、图片 dataURL 或任何任意 console 文本。
     */
    String renderDiagnostics() {
        String base = petWindow == null
                ? "{\"phase\":\"none\",\"reason\":\"no_window\"}"
                : petWindow.diagnosticsSnapshot();
        if (base.endsWith("}")) {
            base = base.substring(0, base.length() - 1);
        } else {
            base = "{}";
        }
        RenderSignalRouter router = renderRouter;
        String signalFields = router == null
                ? "\"renderSignalKind\":\"\",\"renderSignalResult\":\"\","
                        + "\"renderSignalSessionPresent\":false,\"renderSignalDuplicates\":0,"
                        + "\"renderSignalsQueued\":0,\"renderSignalsApplied\":0,"
                        + "\"renderSignalsOnMainThread\":0,\"renderSignalsAllMainThread\":true,"
                        + "\"renderSignalLastAppliedKind\":\"\",\"renderSignalMainThreadId\":-1,"
                        + "\"renderSignalLastThreadId\":-1,\"renderSignalApplyErrors\":0"
                : router.diagnosticsFields();
        return base
                + ",\"regionAckApplied\":" + regionAckApplied
                + ",\"regionAckRevision\":" + regionAckRevision
                + ",\"regionAckRects\":" + regionAckRects
                + ",\"regionAckFallback\":" + regionAckFallback
                + ",\"regionAckError\":" + org.json.JSONObject.quote(regionAckError)
                + ",\"plan\":" + org.json.JSONObject.quote(
                        currentPlan == null ? "detached" : currentPlan.name())
                + ",\"windowAttached\":" + (rootView != null && rootView.isAttachedToWindow())
                + ",\"heartbeat\":" + isHeartbeatRunning()
                + ",\"onMainThread\":" + onMainThread()
                + ",\"signal\":{" + signalFields + "}"
                + "}";
    }

    /** 把截图结果送进悬浮窗的网页。 */
    private void deliverScreenshot(String dataUrl, String error) {
        if (petWindow == null) {
            return;
        }
        final String payload = dataUrl == null ? "" : dataUrl;
        final String reason = error == null ? "" : error;
        handler.post(() -> {
            try {
                petWindow.webView().evaluateJavascript(
                        "if (window.SakuraNative && window.SakuraNative.onScreenCapture) {"
                                + "window.SakuraNative.onScreenCapture("
                                + org.json.JSONObject.quote(payload) + ","
                                + org.json.JSONObject.quote(reason) + ");}",
                        null);
            } catch (Exception ignored) {
                // 网页可能已销毁
            }
        });
    }

    // ---- 精确局部穿透（API33+）------------------------------------

    /**
     * 接收网页提交的输入区域快照。
     *
     * 载荷是 JSON（CSS 像素矩形 + revision + pageSessionId），
     * 由 {@link OverlayTouchRegionController} 校验、校准并转成 surface 本地设备像素。
     * 返回稳定结果码给网页显示，不抛异常。
     */
    public String setTouchRegion(String json) {
        if (touchRegion == null) {
            return "disabled";
        }
        if (!isTouchRegionSupported()) {
            return "unsupported";
        }
        try {
            return touchRegion.applyPayload(json);
        } catch (Exception error) {
            return "invalid";
        }
    }

    /** 关闭精确穿透，恢复整窗可交互。 */
    public void clearTouchRegion() {
        setPassThroughEnabled(false);
    }

    /** 本机是否具备公开的精确穿透 API（Android 13 / API 33 起）。 */
    public boolean isTouchRegionSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    /**
     * 网页开关穿透。窗口 alpha 的 Android12+ 安全阈值一并处理。
     *
     * <p>这是**网页侧**入口：记下网页已知的 desired，避免随后 App 同步同一状态时
     * 再把相同值推回网页（回环）。
     */
    public void setPassThroughEnabled(final boolean enabled) {
        handler.post(() -> {
            // A stale page retry must not undo the user's persisted OFF choice.
            boolean allowed = enabled && !(PetConfig.hasPassThroughPreference(this)
                    && !PetConfig.passThroughEnabled(this));
            pagePassThroughDesired = allowed;
            passThroughRequested = allowed;
            applyPassThrough(allowed);
            if (allowed != enabled) {
                pushDisplayPreferencesToPage("{\"passThrough\":false}");
            }
        });
    }

    /** App / 偏好通道请求穿透（不经过网页开关）。 */
    private void requestPassThrough(final boolean enabled) {
        passThroughRequested = enabled;
        handler.post(() -> applyPassThrough(enabled));
    }

    /**
     * 应用穿透状态。
     *
     * <p>Android 12 起跨窗口触摸有「不可信触摸」安全规则：输入孔洞并不能绕过
     * 遮挡判定，窗口自身的 LayoutParams.alpha 必须不高于系统给出的
     * maximum obscuring opacity。这里按需降低 alpha，关闭时**精确恢复**原始值；
     * 不关闭系统安全策略、不用 root/反射。
     *
     * <p>关键修正（真机「关闭后仍半透明」）：
     *   - alpha 的处理**不再依赖 touchRegion/rootView 是否就绪**。旧实现在
     *     {@code touchRegion == null || rootView == null} 时直接 return，
     *     关闭穿透的恢复请求会被整段跳过，窗口保留已降低的 alpha；
     *   - **能力门控**：API&lt;33 或 ROM 永久降级时完全不降 alpha（避免白变淡）；
     *   - 幂等：原始 alpha 只在第一次受支持开启时保存，重复开启/重试不污染它。
     */
    private void applyPassThrough(boolean enabled) {
        if (layoutParams == null) {
            // 窗口还没建：没有 alpha 可改；新窗口会用 alpha=1 的干净基准
            lastRestoreResult = "no-window";
            logAlphaState("no-window");
            return;
        }
        float previousAlpha = layoutParams.alpha;
        boolean supported = isTouchRegionSupported()
                && (touchRegion == null || touchRegion.isSupported());
        if (touchRegion != null) {
            touchRegion.setPassThroughEnabled(enabled && supported);
        }
        float safe = maximumObscuringOpacity();
        lastSafeAlpha = safe;
        if (enabled && supported) {
            // The connected service's genuine window token is required. Merely
            // labelling an ordinary application window as trusted never suffices.
            boolean trusted = accessibilityWindow
                    && layoutParams.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    && PortraitOverlayAccess.windowManager() != null;
            layoutParams.alpha = passThroughAlpha.enable(layoutParams.alpha,
                    trusted ? 1f : safe, true);
            lastAlphaReduced = passThroughAlpha.isReduced();
            lastRestoreResult = trusted ? "accessibility-opaque"
                    : lastAlphaReduced ? "dimmed" : "no-dim-needed";
        } else {
            // 关闭 / 不支持 / 降级：只要曾经降低过就必须恢复
            if (passThroughAlpha.isAdjusted()) {
                layoutParams.alpha = passThroughAlpha.disable();
                lastRestoreResult = enabled ? "restored-unsupported" : "restored";
            } else {
                lastRestoreResult = enabled ? "unsupported" : "already-normal";
            }
            lastAlphaReduced = false;
            if (!enabled) {
                regionRevision = -1L;
                regionRectCount = 0;
                regionFallbackFull = false;
                regionLastError = "";
            }
        }
        if (rootView != null && Float.compare(previousAlpha, layoutParams.alpha) != 0) {
            updateLayout();
        }
        logAlphaState(lastRestoreResult);
    }

    /**
     * 控制器报告本 ROM/机型永久不支持精确穿透：恢复到正常 alpha 并停止降级状态。
     *
     * <p>只影响运行态，不覆盖用户持久偏好 —— 换个受支持的机型/系统后偏好仍然生效。
     */
    private void onPassThroughDegraded(String reasonCode) {
        if (layoutParams == null) {
            return;
        }
        boolean wasAdjusted = passThroughAlpha.isAdjusted();
        if (wasAdjusted) {
            layoutParams.alpha = passThroughAlpha.disable();
        }
        lastAlphaReduced = false;
        lastRestoreResult = "degraded";
        if (rootView != null) {
            updateLayout();
        }
        logAlphaState(wasAdjusted ? "degraded" : reasonCode);
    }

    /** 去敏、限频的 alpha 状态日志：只有数字/布尔/稳定代码。 */
    private void logAlphaState(String resultCode) {
        float alpha = layoutParams != null ? layoutParams.alpha : 1f;
        float original = passThroughAlpha.originalAlpha();
        boolean reduced = lastAlphaReduced;
        String signature = passThroughRequested + "|" + alpha + "|" + original + "|"
                + lastSafeAlpha + "|" + reduced + "|" + resultCode;
        if (signature.equals(lastAlphaLogged)) {
            return;
        }
        lastAlphaLogged = signature;
        diagnostics.alphaState(passThroughRequested, touchRegion != null
                        && touchRegion.isPassThroughEnabled(),
                alpha, original, lastSafeAlpha, reduced, resultCode);
    }

    /** 查询系统允许的最大遮挡不透明度；取不到就返回 1（不额外降透明）。 */
    private float maximumObscuringOpacity() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return 1f;
        }
        try {
            android.hardware.input.InputManager manager =
                    (android.hardware.input.InputManager) getSystemService(Context.INPUT_SERVICE);
            if (manager == null) {
                return 1f;
            }
            float value = manager.getMaximumObscuringOpacityForTouch();
            if (value > 0f && value <= 1f) {
                return value;
            }
            return 1f;
        } catch (Throwable error) {
            // 个别 ROM 不实现该查询：不改变 alpha，保持可见性
            return 1f;
        }
    }

    /** 穿透状态（JSON），供网页显示；只含状态码/数量，不含任何用户数据。 */
    public String touchRegionStatus() {
        float alpha = layoutParams != null ? layoutParams.alpha : 1f;
        int level = touchRegion != null ? touchRegion.touchRegionLevel() : 0;
        float tolerance = touchRegion != null ? touchRegion.touchRegionTolerance() : 0f;
        boolean active = touchRegion != null && touchRegion.isPassThroughEnabled();
        return "{\"supported\":" + isTouchRegionSupported()
                + ",\"enabled\":" + passThroughRequested
                + ",\"active\":" + active
                + ",\"alpha\":" + alpha
                + ",\"windowAlpha\":" + alpha
                + ",\"windowType\":" + (layoutParams != null ? layoutParams.type : -1)
                + ",\"accessibilityWindow\":" + accessibilityWindow
                + ",\"originalAlpha\":" + passThroughAlpha.originalAlpha()
                + ",\"alphaReduced\":" + lastAlphaReduced
                + ",\"alphaSafe\":" + lastSafeAlpha
                + ",\"restoreResult\":" + org.json.JSONObject.quote(lastRestoreResult)
                + ",\"level\":" + level
                + ",\"tolerance\":" + (Math.round(tolerance * 100f) / 100f)
                + ",\"prefsSync\":" + prefsSyncCount
                + ",\"revision\":" + regionRevision
                + ",\"rects\":" + regionRectCount
                + ",\"fallbackFull\":" + regionFallbackFull
                + ",\"error\":" + org.json.JSONObject.quote(regionLastError)
                + "}";
    }

    // ---- 显示 / 穿透偏好（App ↔ 运行中浮窗）------------------------

    /** 去敏偏好快照（含本机是否具备穿透能力）。 */
    public String displayPreferences() {
        return PetConfig.displayPreferences(this, isTouchRegionSupported());
    }

    /**
     * 写入偏好（落盘 + 回读校验），并把新值同步给网页与区域控制器。
     *
     * <p>只有带可识别 key 的请求才会落盘；返回的是回读值，调用方可校验。
     */
    public String setDisplayPreferences(String json) {
        String result = PetConfig.applyDisplayPreferences(this, json);
        if (result.indexOf("\"ok\":true") >= 0) {
            prefsSyncCount++;
            onDisplayPreferencesChanged(json);
        }
        return result;
    }

    /**
     * App 侧写入偏好后的**同进程安全通道**：不广播、不起服务、不提交区域，
     * 只让运行中的浮窗按新偏好更新（网页重新计算轮廓由 SakuraTouchRegion 负责）。
     */
    public static void applyDisplayPreferencesFromHost(String json) {
        OverlayService service = current;
        if (service != null) {
            service.handler.post(() -> service.onDisplayPreferencesChanged(json));
        }
    }

    /**
     * 偏好变化后的落地（主线程）：应用穿透意图，并把偏好推给已加载的网页。
     *
     * <p>刻意**不**依赖两个 WebView 的 localStorage 自动同步。
     */
    private void onDisplayPreferencesChanged(String json) {
        Boolean wantPassThrough = DisplayPreferences.bool(json, DisplayPreferences.KEY_PASS_THROUGH);
        Boolean wantFloat = DisplayPreferences.bool(json, DisplayPreferences.KEY_FLOAT);
        // 网页已知 desired 与请求值不同 → 真正需要应用（并推送）；
        // 相同时说明这次变化就来自网页自己，不能再推回去（回环）。
        boolean passThroughDiffers = wantPassThrough != null
                && (pagePassThroughDesired == null
                    || pagePassThroughDesired.booleanValue() != wantPassThrough.booleanValue());
        if (wantPassThrough != null) {
            requestPassThrough(wantPassThrough);
        }
        if (passThroughDiffers || wantFloat != null) {
            pushDisplayPreferencesToPage(json);
        }
    }

    private void pushDisplayPreferencesToPage(String json) {
        if (petWindow == null || json == null || json.isEmpty()) {
            return;
        }
        try {
            petWindow.webView().evaluateJavascript(
                    "if (window.SakuraDisplayPreferences && window.SakuraDisplayPreferences.apply) {"
                            + "window.SakuraDisplayPreferences.apply(" + json + ");}",
                    null);
        } catch (Exception ignored) {
            // 网页可能还没就绪或已销毁：偏好已落盘，下次 install 时读取
        }
    }

    /**
     * 输入框聚焦时要能获取焦点，软键盘才会弹出。
     *
     * <p>切换始终经过 {@link OverlayInputFocus#withInputFocus}：清
     * {@code FLAG_NOT_FOCUSABLE} 的同时显式保留 {@code FLAG_NOT_TOUCH_MODAL}，
     * 窗口不会变成触摸模态。回到非焦点态时顺手收起 IME、清掉视图焦点。
     */
    public void onInputFocusChanged(final boolean focused) {
        handler.post(() -> applyInputFocus(focused));
    }

    /** 主线程：落一次输入焦点状态。 */
    private void applyInputFocus(boolean focused) {
        if (layoutParams == null || rootView == null) {
            return;
        }
        int next = OverlayInputFocus.withInputFocus(layoutParams.flags, focused);
        boolean changed = next != layoutParams.flags;
        layoutParams.flags = next;
        if (!focused) {
            // 回到非焦点态：收起软键盘并让网页输入框失焦，
            // 否则 IME 会继续占着输入连接，用户看起来仍是「焦点被占用」。
            hideImeAndClearInputFocus();
        }
        if (changed) {
            updateLayout();
        }
    }

    /**
     * 主动交还输入焦点（窗口外触摸 / 返回键 / 失焦）。
     *
     * <p>只在当前确实可聚焦时才动手：不可聚焦说明本来就没有输入态，
     * 这时什么都不做，避免与网页刚发起的新输入请求或其他窗口抢焦点。
     *
     * @return true 表示这次调用确实退出了输入态（返回键路径据此决定是否消费）
     */
    private boolean releaseInputFocus(String reason) {
        if (layoutParams == null || rootView == null) {
            return false;
        }
        if (!OverlayInputFocus.isInputFocusable(layoutParams.flags)) {
            return false;
        }
        // 顺序：先收 IME/失焦（网页 focusout → setKeyboardOpen(false)），
        // 再把窗口恢复成不可聚焦。两步都幂等，重复回调不会抖动。
        hideImeAndClearInputFocus();
        layoutParams.flags = OverlayInputFocus.withInputFocus(layoutParams.flags, false);
        updateLayout();
        return true;
    }

    /**
     * 收起软键盘 + 让网页输入框失焦。
     *
     * <p>两条路一起走：原生直接 hideSoftInputFromWindow 立即生效；
     * 再让 WebView 的 activeElement 失焦，触发网页 focusout →
     * {@code setKeyboardOpen(false)}，两边状态一致。
     */
    private void hideImeAndClearInputFocus() {
        try {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager)
                            getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && rootView != null) {
                imm.hideSoftInputFromWindow(rootView.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
            // 没有 IME/窗口令牌时忽略：下面还有网页侧的失焦
        }
        try {
            if (petWindow != null) {
                petWindow.webView().evaluateJavascript(
                        "(function(){try{var a=document.activeElement;"
                                + "if(a&&a!==document.body&&typeof a.blur==='function'){a.blur();}}"
                                + "catch(e){}})()",
                        null);
            }
        } catch (Throwable ignored) {
            // 页面可能还没就绪或已销毁
        }
        try {
            if (rootView != null) {
                rootView.clearFocus();
            }
        } catch (Throwable ignored) {
            // 视图可能已被移除
        }
    }

    /**
     * 整窗是否接收触摸（备选路线 C，**不用于局部穿透**）。
     *
     * 历史实现靠它做「按下透明处 → 整窗 FLAG_NOT_TOUCHABLE」的假穿透，
     * 已被验证不可行：Android 在手势开始时就读定触摸归属，同一根手指的
     * DOWN 不会因为中途切 flag 而改投下层。
     *
     * 精确局部穿透走 {@link OverlayTouchRegionController} 的
     * setTouchableRegion（API33+），与这里无关。
     * 这个方法只保留给「整窗暂时让位」这类显式整体切换，网页侧当前不再调用。
     */
    public void onTouchableChanged(final boolean touchable) {
        handler.post(() -> {
            if (layoutParams == null || rootView == null) {
                return;
            }
            if (touchable) {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            } else {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            }
            updateLayout();
        });
    }

    /**
     * 由网页请求调整窗口尺寸（CSS 像素）。
     *
     * 立绘的宽高比和缩放倍率只有网页知道，所以尺寸由它算好报过来。
     * 原生这边负责单位换算（CSS px → 设备 px）并夹到屏幕范围内。
     *
     * 注意：桥接方法跑在 JavaBridge 线程，而 View/WindowManager 是 UI 对象，
     * 必须切到主线程操作 —— 否则会抛
     * `CalledFromWrongThreadException: Only the original thread that created
     * a view hierarchy can touch its views`（实测就是这个原因导致悬浮窗消失）。
     */
    public void setWindowSizePx(final float cssWidth, final float cssHeight) {
        // 先落盘：下次建窗口时直接用这个尺寸，避免 WebView 按旧视口排版
        PetConfig.setWantWindowSize(this,
                Math.round(cssWidth), Math.round(cssHeight));
        handler.post(() -> applyWindowSizeInternal(cssWidth, cssHeight));
    }

    private void applyWindowSizeInternal(float cssWidth, float cssHeight) {
        if (layoutParams == null || rootView == null) {
            return;
        }
        /*
         * 小球模式下窗口尺寸固定，不要被网页的尺寸重算改掉。
         *
         * 网页每 400ms 会按立绘和对话框内容重算一次窗口尺寸，那些计算是按
         * 「完整窗口」来的；套到小球上会把图标撑得很大（平板上尤其明显）。
         */
        if (bubbleMode) {
            if (layoutParams.width != bubbleSize || layoutParams.height != bubbleSize) {
                layoutParams.width = bubbleSize;
                layoutParams.height = bubbleSize;
                updateLayout();
            }
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        int width = Math.round(cssWidth * density);
        int height = Math.round(cssHeight * density);
        width = Math.max(dp(160), Math.min(screenWidth, width));
        height = Math.max(dp(160), Math.min(screenHeight, height));
        if (width == layoutParams.width && height == layoutParams.height) {
            return;
        }
        layoutParams.width = width;
        layoutParams.height = height;
        /*
         * 只夹到「完全可见」范围。
         * 之前试过「保持右下角不动」去锚定，结果窗口从 886 长到 1048 时
         * 被推出屏幕（实测 mAttrs=(-99,-493)），所以改成简单可靠的夹紧。
         *
         * 但**拖动期间必须跳过**：这个夹取会把用户刚拖出来的位移整个抵消，
         * 表现就是「拖不动，得先点一下」。松手后的下一次 resize 会正常校正。
         */
        if (!dragInProgress) {
            layoutParams.x = Math.max(0, Math.min(screenWidth - width, layoutParams.x));
            layoutParams.y = Math.max(0, Math.min(screenHeight - height, layoutParams.y));
        }
        updateLayout();
    }

    /**
     * 按增量移动窗口（网页拖动立绘时调用）。
     *
     * 位移在原生侧累加到窗口坐标，网页只报「相对上一次移动了多少」。
     *
     * 合并到 vsync：不再每次调用都 handler.post 一个任务。
     * 原来的写法在手指快速移动时会往主线程塞进大量任务，
     * 主线程一旦忙于渲染就开始积压，表现就是「一卡一卡地突进」而不是平滑跟随。
     * 现在把位移攒起来，由主线程的 moveFlushRunnable 合并成一次
     * updateViewLayout，同一帧内的多次位移只刷一次。
     */
    public void moveWindowBy(final float dx, final float dy) {
        /*
         * 位移**在这里（JavaBridge 线程）就累加掉**，只把「调度」丢给主线程。
         *
         * 两点必须同时满足：
         *
         * 1) 累加不能放进 handler.post。
         *    试过整个塞进主线程，结果每帧都往主线程排一个任务；主线程忙于渲染时
         *    任务堆积，攒够一大批才刷一次 —— 实测窗口是「11 帧不动，然后一次跳
         *    165 设备px」，而且总位移还会丢（220 / 期望 275）。用户看到的就是抽搐。
         *    现在累加是即时完成的，主线程只负责「下一帧刷一次」。
         *
         * 2) 调度必须在主线程。
         *    Choreographer.getInstance() 内部走 Looper.myLooper() 取**当前线程**的
         *    Looper，在 JavaBridge 线程调会绑到错误的 Looper 上（或抛异常被吞），
         *    postFrameCallback 永远等不到那一帧 —— 表现为「第一次拖动完全不动，
         *    要先点一下立绘才生效」。
         *
         * moveRemainX/Y 被两个线程访问，所以用 moveLock 保护。
         */
        synchronized (moveLock) {
            moveRemainX += dx;
            moveRemainY += dy;
        }
        requestMoveFlush();
    }

    /**
     * 请求把攒下的位移刷到窗口上。
     *
     * 实现是**自重新调度**：post 一个 Runnable，应用位移后若发现又攒了新位移
     * 就再 post 自己一次，没有就自然停下。
     *
     * 为什么不再用 Choreographer：
     *   - Choreographer.getInstance() 必须在主线程调用（它走 Looper.myLooper()
     *     取当前线程的 Looper），在 JavaBridge 线程调会绑到错误的 Looper 上，
     *     postFrameCallback 永远等不到那一帧；
     *   - 而「本帧是否已排过队」这个状态又必须跨线程读，天然有竞态 ——
     *     实测会让标志永久卡在 true，之后每一帧都不再调度。
     *
     * 自重新调度把所有状态都留在主线程，没有任何跨线程标志；
     * handler.post 本身按帧节奏执行，同一帧内多次位移会被合并成一次
     * updateViewLayout，效果与 vsync 对齐相同。
     */
    private void requestMoveFlush() {
        // 已经排过一次就不再排：主线程忙的时候，每帧都塞任务会把队列压满，
        // 反而让位移延迟更大。
        if (moveFlushPending.compareAndSet(false, true)) {
            handler.post(moveFlushRunnable);
        }
    }

    private final Runnable moveFlushRunnable = new Runnable() {
        @Override
        public void run() {
            moveFlushPending.set(false);   // 先清，后面若要继续排会重新置位
            if (layoutParams == null || rootView == null) {
                synchronized (moveLock) {
                    moveRemainX = 0;
                    moveRemainY = 0;
                }
                return;
            }
            /*
             * 亚像素累积。
             *
             * 位置必须是整数（WindowManager 要求），所以小数部分只能自己记住，
             * 凑够 1px 再进位。直接 Math.round 的话，手指慢速移动时每帧的 dx
             * 常常不足 1px，会被一律取整成 0 丢掉，一秒上百帧下来窗口就明显
             * 落后于手指。
             */
            int stepX;
            int stepY;
            synchronized (moveLock) {
                stepX = (int) Math.floor(moveRemainX);
                stepY = (int) Math.floor(moveRemainY);
                moveRemainX -= stepX;
                moveRemainY -= stepY;
            }
            if (stepX != 0 || stepY != 0) {
                layoutParams.x += stepX;
                layoutParams.y += stepY;
                clampWindowPosition();
                updateLayout();
            }
            // 这一帧期间又攒了新位移就继续排；否则自然停下
            boolean more;
            synchronized (moveLock) {
                more = moveRemainX != 0f || moveRemainY != 0f;
            }
            if (more && moveFlushPending.compareAndSet(false, true)) {
                handler.post(this);
            }
        }
    };


    /**
     * 把窗口直接移到绝对位置（CSS 像素）。
     *
     * 为什么改成绝对定位：
     * 原先是网页逐帧报增量、这里累加。累加有两个无法避免的漂移源 ——
     *   1) 每帧 Math.round(dx)：dx 常常不足 1px，取整后直接丢掉，
     *      一秒钟上百帧累积下来就是明显的「跟不上手指」；
     *   2) 边界夹取：撞到边缘时丢掉的那部分位移不会再补回来。
     * 绝对定位下位置只由「手指当前位置 - 按下时的抓取点」决定，
     * 不做任何累加，因此拖多久都不会偏。
     *
     * 这里只把 CSS 像素换成设备像素并夹到屏幕范围内。
     *
     * 注意 moveWindowBy 收的是**设备像素**（网页负责乘 dpr），而这里收的是
     * **CSS 像素**（原生自己乘 density）。两条路径单位不同，改的时候别混。
     */
    public void setWindowPositionPx(final float x, final float y) {
        final float density = currentDensity();
        handler.post(() -> {
            if (layoutParams == null || rootView == null) {
                return;
            }
            layoutParams.x = Math.round(x * density);
            layoutParams.y = Math.round(y * density);
            clampWindowPosition();
            updateLayout();
        });
    }

    /** 悬浮窗布局信息，JSON 格式；供网页换算拖动坐标。 */
    public String overlayLayoutInfo() {
        final float density = currentDensity();
        int x = 0;
        int y = 0;
        int w = 0;
        int h = 0;
        // layoutParams 只能在主线程读，但这里被 JS 桥线程直接调用，
        // 所以用 volatile 快照值（每次 updateLayout 时刷新）。
        x = lastX;
        y = lastY;
        w = lastW;
        h = lastH;
        return "{\"x\":" + x + ",\"y\":" + y + ",\"w\":" + w + ",\"h\":" + h
                + ",\"screenW\":" + screenWidth + ",\"screenH\":" + screenHeight
                + ",\"density\":" + density + "}";
    }

    private float currentDensity() {
        float density = getResources().getDisplayMetrics().density;
        return density > 0f ? density : 1f;
    }

    /** 边界夹取：至少留 dp(48) 在屏幕内；抽出来给两条移动路径共用。 */
    private void clampWindowPosition() {
        if (layoutParams == null) {
            return;
        }
        int minX = -layoutParams.width + dp(48);
        int maxX = screenWidth - dp(48);
        int minY = -layoutParams.height + dp(48);
        int maxY = screenHeight - dp(48);
        layoutParams.x = Math.min(maxX, Math.max(minX, layoutParams.x));
        layoutParams.y = Math.min(maxY, Math.max(minY, layoutParams.y));
    }

    // ---- 前台通知 --------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        channel.setDescription(getString(R.string.notification_channel_desc));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startAsForeground() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openIntent, pendingFlags);

        Intent stopIntent = new Intent(this, ConfigReceiver.class)
                .setAction(ConfigReceiver.ACTION_STOP_OVERLAY);
        PendingIntent stopPending = PendingIntent.getBroadcast(this, 1, stopIntent, pendingFlags);

        builder.setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentIntent(contentIntent)
                .addAction(0, getString(R.string.notification_stop), stopPending)
                .setOngoing(true);

        Notification notification = builder.build();
        // specialUse 是 API 34（UPSIDE_DOWN_CAKE）才引入的 FGS 类型。
        // 旧实现从 API29 起就传 SPECIAL_USE，在 29–33 上不是合法类型。
        // 现在按版本分支：34+ 用 specialUse，更旧的版本不带类型启动。
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
                return;
            } catch (Exception error) {
                // 个别 ROM 未实现 specialUse：退回无类型前台服务，保证桌宠可用
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    // ---- 工具 ------------------------------------------------------

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 悬浮窗里网页侧的 RemoteBridge 宿主。
     *
     * 悬浮窗和 App 是两个不同的 Context，能做的事情不一样：
     * - 缩放/配置交给 PetConfig（进程内共享）
     * - 需要界面的操作（申请权限、收起）交给 Activity，这里只做无界面的部分
     */
    private final class ServiceBridgeHost implements RemoteBridge.Host {

        @Override
        public void loadRemote(String url) {
            if (petWindow != null) {
                petWindow.load(url);
            }
        }

        @Override
        public void setPetScale(float scale) {
            PetConfig.setScale(OverlayService.this, scale);
        }

        @Override
        public float getPetScale() {
            return PetConfig.scale(OverlayService.this);
        }

        @Override
        public boolean isOverlayRunning() {
            return true;
        }

        @Override
        public boolean startOverlay() {
            return true;
        }

        @Override
        public void stopOverlay() {
            PetConfig.setOverlayEnabled(OverlayService.this, false);
            stopSelf();
        }

        @Override
        public void requestBatteryExemption() {
            Intent intent = new Intent(OverlayService.this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            try {
                startActivity(intent);
            } catch (Exception ignored) {
                // 后台启动受限时忽略
            }
        }

        @Override
        public boolean isIgnoringBatteryOptimizations() {
            android.os.PowerManager manager =
                    (android.os.PowerManager) getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isIgnoringBatteryOptimizations(getPackageName());
        }

        @Override
        public void saveServer(String server, String token) {
            // 立绘页面不写回地址：已配置时忽略，避免旧立绘覆盖主 App 新配置
            PetConfig.applyOverlaySelection(OverlayService.this, server, token);
        }

        @Override
        public void rememberRemoteUrl(String url, boolean overlayPage) {
            // 立绘页面一律按 overlay 来源处理（overlayPage 恒为 true）
            PetConfig.applyPageReport(OverlayService.this, url, true);
        }

        @Override
        public void enterOverlayMode() {
            // 已经在悬浮窗里了，无需切换
        }

        @Override
        public void setKeyboardOpen(boolean open) {
            onInputFocusChanged(open);
        }

        @Override
        public void setBubbleMode(boolean bubble) {
            OverlayService.this.setBubbleMode(bubble);
        }

        @Override
        public void setTouchable(boolean touchable) {
            OverlayService.this.onTouchableChanged(touchable);
        }

        @Override
        public void setDragging(boolean dragging) {
            OverlayService.this.setDragging(dragging);
        }

        @Override
        public void moveWindowBy(float dx, float dy) {
            OverlayService.this.moveWindowBy(dx, dy);
        }

        @Override
        public void setWindowPositionPx(float x, float y) {
            OverlayService.this.setWindowPositionPx(x, y);
        }

        @Override
        public String overlayLayoutInfo() {
            return OverlayService.this.overlayLayoutInfo();
        }

        @Override
        public void setWindowSizePx(float width, float height) {
            OverlayService.this.setWindowSizePx(width, height);
        }

        @Override
        public void requestScreenshot(String pageSessionId) {
            OverlayService.this.requestScreenshot(pageSessionId);
        }

        @Override
        public void requestScreenPermission(String pageSessionId) {
            // 悬浮窗页里没有「授权截屏权限」按钮（那是设置页的东西），
            // 但接口必须实现；走同一条授权路径即可。
            OverlayService.this.requestScreenshot(pageSessionId);
        }

        @Override
        public boolean hasScreenPermission() {
            return ScreenCapturer.hasPermission(OverlayService.this);
        }

        @Override
        public void deliverScreenshot(String dataUrl, String error) {
            OverlayService.this.deliverScreenshot(dataUrl, error);
        }

        @Override
        public void registerPageSession(String pageSessionId) {
            OverlayService.this.registerPageSession(pageSessionId);
        }

        @Override
        public void invalidatePageSession(String pageSessionId) {
            OverlayService.this.invalidatePageSession(pageSessionId);
        }

        // ---- 渲染可用性信号（FIX-BLANK-01 / FIX-RUNTIME-01）----
        //
        // 注意：这里**没有** onRender* 直调入口。网页上报的渲染信号是桥线程回调，
        // 统一走 RemoteBridge 的 RenderSignalRouter 封送到主线程后落到 RenderSink；
        // 本宿主只提供诊断读取，避免桥线程触碰 View/WindowManager。

        @Override
        public String renderDiagnostics() {
            return OverlayService.this.renderDiagnostics();
        }

        @Override
        public String setTouchRegion(String json) {
            return OverlayService.this.setTouchRegion(json);
        }

        @Override
        public void clearTouchRegion() {
            OverlayService.this.clearTouchRegion();
        }

        @Override
        public boolean isTouchRegionSupported() {
            return OverlayService.this.isTouchRegionSupported();
        }

        @Override
        public void setPassThroughEnabled(boolean enabled) {
            OverlayService.this.setPassThroughEnabled(enabled);
        }

        @Override
        public String touchRegionStatus() {
            return OverlayService.this.touchRegionStatus();
        }

        @Override public boolean portraitAccessibilityConnected() {
            return PortraitOverlayAccess.isConnected();
        }

        @Override public void requestPortraitAccessibilitySettings() {
            handler.post(() -> PortraitOverlayAccess.openSettings(OverlayService.this));
        }

        @Override
        public String displayPreferences() {
            return OverlayService.this.displayPreferences();
        }

        @Override
        public String setDisplayPreferences(String json) {
            return OverlayService.this.setDisplayPreferences(json);
        }

        @Override
        public String petStatus() {
            return "{\"canDraw\":true,\"running\":true,\"battery\":"
                    + isIgnoringBatteryOptimizations()
                    + ",\"wanted\":true,"
                    + "\"scale\":" + PetConfig.scale(OverlayService.this)
                    + ",\"overlay\":true}";
        }
    }
}
