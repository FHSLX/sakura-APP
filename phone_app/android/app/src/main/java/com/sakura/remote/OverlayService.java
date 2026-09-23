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
import android.view.Choreographer;
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
public class OverlayService extends Service {

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

    /** 让服务立刻执行一次截屏（MainActivity 拿到授权后回抛用）。 */
    public static final String EXTRA_CAPTURE_NOW = "captureNow";

    /**
     * 截屏期间临时隐藏悬浮窗。
     *
     * 为什么必须隐藏：MediaProjection 拍的是整块屏幕，悬浮窗也在上面，
     * 不隐藏就会把学姐和对话框一起截进去。用 static 是因为
     * CaptureService 拿不到 OverlayService 实例。
     */
    private static volatile boolean hiddenForCapture;

    public static void setHiddenForCapture(boolean hidden) {
        hiddenForCapture = hidden;
        OverlayService instance = current;
        if (instance != null) {
            instance.applyHiddenForCapture();
        }
    }

    /** 当前存活的实例，供静态方法回调（截屏服务拿不到实例）。 */
    private static volatile OverlayService current;

    /**
     * 按 hiddenForCapture 把窗口移出/放回屏幕。
     *
     * 用 removeView 而不是设成 INVISIBLE：MediaProjection 拍的是合成后的
     * 屏幕内容，只把 view 设为不可见在部分机型上仍会留下残影，
     * 直接移出窗口才干净。
     */
    private void applyHiddenForCapture() {
        handler.post(() -> {
            if (windowManager == null || rootView == null || layoutParams == null) {
                return;
            }
            boolean attached = rootView.isAttachedToWindow();
            try {
                if (hiddenForCapture && attached) {
                    windowManager.removeView(rootView);
                } else if (!hiddenForCapture && !attached) {
                    windowManager.addView(rootView, layoutParams);
                }
            } catch (Exception ignored) {
                // 窗口状态变化时可能已经不在，忽略即可
            }
        });
    }

    /**
     * 由 MainActivity 在拿到（或已有）截屏授权后调用，让服务自己起 CaptureService。
     *
     * 为什么绕这一圈：悬浮窗状态下 MainActivity 已被 moveTaskToBack 收到后台，
     * Android 12+ 禁止后台应用启动前台服务
     *（ForegroundServiceStartNotAllowedException）。OverlayService 本身就是
     * 前台服务，由它启动才合法。
     */
    public static void requestCaptureFromActivity(Context context) {
        Intent intent = new Intent(context, OverlayService.class);
        intent.putExtra(EXTRA_CAPTURE_NOW, true);
        try {
            context.startService(intent);
        } catch (Exception ignored) {
            // 服务已停就只能放弃，网页侧有超时提示
        }
    }

    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;
    private FrameLayout rootView;
    private PetWindow petWindow;
    private final Handler handler = new Handler(Looper.getMainLooper());

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

    private boolean moveFlushScheduled;
    private Choreographer choreographer;

    /** 迷你图标（小圆头像）的直径。 */
    private int bubbleSize;
    /**
     * 授权流程期间是否临时隐藏悬浮窗。
     *
     * MIUI 会在「本应用正在显示系统悬浮窗」时压掉系统的授权确认框
     *（实测：MainActivity 被拉起来了，但对话框一闪就消失，前台回到桌面）。
     * 所以请求截屏授权前先把它藏起来，等结果回来再放回来。
     */
    private static volatile boolean permissionHidden;
    private volatile boolean permissionHiddenApplied;

    /** 由 MainActivity 在授权前后调用。 */
    public static void setPermissionHidden(boolean hidden) {
        OverlayService service = current;
        permissionHidden = hidden;
        if (service != null) {
            service.handler.post(service::applyPermissionHidden);
        }
    }

    private void applyPermissionHidden() {
        if (rootView == null) {
            return;
        }
        if (permissionHidden && !permissionHiddenApplied) {
            permissionHiddenApplied = true;
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
                // 可能已经移除
            }
        } else if (!permissionHidden && permissionHiddenApplied) {
            permissionHiddenApplied = false;
            try {
                windowManager.addView(rootView, layoutParams);
            } catch (Exception ignored) {
                // 已经加回去了
            }
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
        bubbleSize = dp(56);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.getBooleanExtra(EXTRA_STOP, false)) {
            PetConfig.setOverlayEnabled(this, false);
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
        // MainActivity 拿到截屏授权后会带这个 extra 回来，让我们自己起 CaptureService
        if (intent != null && intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false) && rootView != null) {
            startCaptureService();
            return START_STICKY;
        }
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
        if (petWindow != null) {
            petWindow.destroy();
            petWindow = null;
        }
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeView(rootView);
            } catch (Exception ignored) {
                // 视图可能已被系统移除
            }
        }
        rootView = null;
        running = false;
        current = null;
        // 服务重启时不能让隐藏标记残留，否则窗口再也显示不出来
        hiddenForCapture = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
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
        petWindow.addJavascriptBridge(new RemoteBridge(new ServiceBridgeHost()));

        FrameLayout container = new FrameLayout(this);
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
        container.addView(petWindow.view(), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        rootView = container;

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        int width = Math.max(dp(220), Math.round(screenWidth * windowWidthRatio));
        int height = Math.max(dp(260), Math.round(width * windowHeightRatio));
        // 优先用网页算好的尺寸：必须在加载页面前就把窗口定好，
        // 否则 WebView 会按旧尺寸算布局视口，而它改窗口后不更新视口。
        float density = getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        int wantW = PetConfig.wantWindowWidth(this);
        int wantH = PetConfig.wantWindowHeight(this);
        if (wantW > 0 && wantH > 0) {
            width = Math.max(dp(160), Math.min(screenWidth, Math.round(wantW * density)));
            height = Math.max(dp(160), Math.min(screenHeight, Math.round(wantH * density)));
        }

        layoutParams = new WindowManager.LayoutParams(
                width,
                height,
                type,
                // 默认不可获取焦点：否则会抢走其他应用的输入。
                // 网页里点输入框时再通过 onInputFocusChanged 临时打开。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;

        int savedX = PetConfig.posX(this);
        int savedY = PetConfig.posY(this);
        if (savedX < 0 || savedY < 0) {
            layoutParams.x = Math.max(0, screenWidth - width - dp(10));
            // 默认放偏上一点，别贴屏幕底：
            // screenHeight 是整屏高度（含状态栏/手势区），从底部往上算会偏低。
            layoutParams.y = Math.max(dp(40), Math.round(screenHeight * 0.18f));
        } else {
            layoutParams.x = savedX;
            layoutParams.y = savedY;
        }

        try {
            if (!permissionHidden) {
                windowManager.addView(rootView, layoutParams);
            } else {
                permissionHiddenApplied = true;
            }
        } catch (Exception error) {
            rootView = null;
            petWindow = null;
            return false;
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
     * 请求一次手机截屏。
     *
     * MediaProjection 的授权必须由 Activity 走 `startActivityForResult` 拿，
     * Service 自己发不了。所以这里拉起 MainActivity 去申请，
     * 结果通过 {@link MainActivity#deliverScreenshot} 广播回来。
     */
    private void requestScreenshot() {
        if (ScreenCapturer.hasPermission(this)) {
            // 已有授权，直接截
            startCaptureService();
            return;
        }
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra(MainActivity.EXTRA_REQUEST_SCREENSHOT, true);
        try {
            startActivity(intent);
        } catch (Exception error) {
            deliverScreenshot("", "需要先打开一次 App 才能授权截图");
        }
    }

    /**
     * 启动 CaptureService 真正截图。
     *
     * 结果回调指向本服务，这样截图会送回**悬浮窗**的网页，
     * 而不是 App 的网页（之前的结果发错地方，悬浮窗永远等不到）。
     */
    private void startCaptureService() {
        Intent data = ScreenCapturer.storedGrant();
        if (data == null) {
            deliverScreenshot("", "还没有截图权限");
            return;
        }
        CaptureService.setReceiver(this::deliverScreenshot);
        CaptureService.start(this, android.app.Activity.RESULT_OK, data);
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

    /** 输入框聚焦时要能获取焦点，软键盘才会弹出。 */
    public void onInputFocusChanged(final boolean focused) {
        handler.post(() -> {
            if (layoutParams == null || rootView == null) {
                return;
            }
            if (focused) {
                layoutParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            } else {
                layoutParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            }
            updateLayout();
        });
    }

    /**
     * 切换窗口是否接收触摸。
     *
     * 网页按指针位置（`document.elementFromPoint`）判断当前在不在
     * 立绘/气泡/按钮上，据此通知这里。为 false 时整个窗口不接收触摸，
     * 点击直接穿透到桌面或其他应用 —— 这是桌面宠物悬浮窗的关键体验：
     * 只有角色和控件吃点击，别的地方当它不存在。
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
     * 现在把位移攒起来，由 Choreographer 在下一次屏幕刷新回调里合成一次
     * updateViewLayout，天然对齐刷新率。
     */
    public void moveWindowBy(final float dx, final float dy) {
        /*
         * 这里跑在 JavaBridge 线程，而 Choreographer.getInstance()
         * **必须在主线程调用** —— 它内部走 Looper.myLooper() 取当前线程的 Looper。
         * 在别的线程调，拿到的 Choreographer 会绑在错误的 Looper 上
         *（或者直接抛异常被吞掉），于是 postFrameCallback 永远等不到那一帧。
         *
         * 实际表现就是：**第一次拖动完全不动，要先点一下立绘才生效**。
         * 那一下点击触发了网页重排，主线程顺手又调度了一次，之后才正常。
         *
         * 所以：先用 handler.post 切到主线程，再在那边做合并调度。
         */
        handler.post(() -> {
            moveRemainX += dx;
            moveRemainY += dy;
            scheduleMoveFlush();
        });
    }

    /** 把攒下的位移在下一帧一次性应用。**必须在主线程调用。** */
    private void scheduleMoveFlush() {
        if (moveFlushScheduled) {
            return;
        }
        moveFlushScheduled = true;
        if (choreographer == null) {
            choreographer = Choreographer.getInstance();
        }
        choreographer.postFrameCallback(moveFlushCallback);
    }

    private final Choreographer.FrameCallback moveFlushCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            moveFlushScheduled = false;
            if (layoutParams == null || rootView == null) {
                moveRemainX = 0;
                moveRemainY = 0;
                return;
            }
            /*
             * 亚像素累积，不要每帧直接取整。
             *
             * 原来写的是 layoutParams.x += Math.round(dx)：手指慢慢移动时
             * 每帧的 dx 常常不足 1px，Math.round 一律变成 0 直接丢掉，
             * 一秒上百帧累积下来窗口就明显落后于手指。
             * 这里把小数部分攒起来，凑够 1px 再进位，位移一点都不丢。
             * 位置必须是整数（WindowManager 要求），所以小数只能自己记。
             */
            int stepX = (int) Math.floor(moveRemainX);
            int stepY = (int) Math.floor(moveRemainY);
            moveRemainX -= stepX;
            moveRemainY -= stepY;
            if (stepX == 0 && stepY == 0) {
                return;   // 还没攒够 1px，余量留到下一帧
            }
            layoutParams.x += stepX;
            layoutParams.y += stepY;
            clampWindowPosition();
            updateLayout();
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
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
            PetConfig.saveServer(OverlayService.this, server, token);
        }

        @Override
        public void rememberRemoteUrl(String url) {
            PetConfig.rememberFromUrl(OverlayService.this, url);
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
        public void requestScreenshot() {
            OverlayService.this.requestScreenshot();
        }

        @Override
        public void requestScreenPermission() {
            // 悬浮窗页里没有「授权截屏权限」按钮（那是设置页的东西），
            // 但接口必须实现；走同一条授权路径即可。
            OverlayService.this.requestScreenshot();
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
        public String petStatus() {
            return "{\"canDraw\":true,\"running\":true,\"battery\":"
                    + isIgnoringBatteryOptimizations()
                    + ",\"wanted\":true,"
                    + "\"scale\":" + PetConfig.scale(OverlayService.this)
                    + ",\"overlay\":true}";
        }
    }
}
