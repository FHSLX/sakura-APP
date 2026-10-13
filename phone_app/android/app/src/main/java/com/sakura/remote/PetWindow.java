package com.sakura.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 悬浮窗的视图：一个填满窗口的 WebView，外面包一层容器。
 *
 * 为什么没有原生标题栏：
 * 原来顶部有一条「按住拖动 + 对话/缩短」的栏，它在透明窗口上会形成一条横贯的
 * 矩形，看起来就是个「框」。现在拖动改成由网页手势驱动
 * （长按立绘 → {@code SakuraNative.moveBy()}），原件整条去掉，
 * 视觉上只剩立绘和控件。
 *
 * 网页侧负责：气泡、输入栏、手势（长按拖动 / 长按 5 秒开设置）、点击穿透判定。
 *
 * ---- FIX-BLANK-01：绝不再让「空白页面」留下透明可触摸大窗 ----
 *
 * 旧实现的问题（实际缺陷）：
 *   主文档失败时只把 WebView 设成 INVISIBLE，外层 WindowManager 窗口仍然挂载、
 *   仍然 alpha=1、touchableRegion 仍是整块矩形。用户什么都看不到，系统却把
 *   整块大矩形当遮挡层 —— 表现为「透明框拦住所有点击」且「立绘消失」。
 *   源注释还明确写着「窗口保持全透明，用户什么也看不到」，等于承认了这点。
 *
 * 现在的规则：
 *   1. 本类只负责**报告渲染可用性**（是否有可见立绘），窗口挂载/移除由
 *      {@link OverlayVisibilityController} 依据 {@link RenderAvailabilityState} 统一裁决：
 *      没有可见内容时窗口被真正移除，或退成有界可见的卡片，绝不透明整窗冒充降级。
 *   2. 主文档失败只清掉 WebView 可见性并升级失败分类，不自己 addView/removeView。
 *   3. 自动重试是有预算的（{@value #MAX_RETRIES} 次，每题间隔 {@value #RETRY_MS}ms），
 *      预算耗尽后停在恢复卡片，等待用户显式重试或关闭。
 *   4. 所有诊断只记录**去敏稳定代码**：网络错误码、HTTP 状态、JS 异常类型与行号、
 *      boot/立绘/区域结果、页面代际；不记录完整 URL、query、token、聊天内容、
 *      图片 dataURL，也不保存任意真实 console 文本。
 */
public class PetWindow {

    /** 页面渲染可用性回调；由 {@link OverlaySurfaceHost} 连接到可见性控制器。 */
    public interface AvailabilityListener {
        /**
         * 渲染可用性发生变化。
         *
         * @param attachable 是否已确认有可见立绘（true 才允许挂完整透明窗口）
         * @param phase      {@link RenderAvailabilityState.Phase} 名称（去敏）
         * @param reason     去敏失败分类（仅 {@link RenderAvailabilityState} 的常量）
         */
        void onRenderAvailability(boolean attachable, String phase, String reason);

        /**
         * 自动重试结论。
         *
         * @param accepted true 表示已发起一次重试；false 表示预算耗尽/无法重试
         * @param reason   拒绝原因（去敏）：无地址 / 预算耗尽
         */
        void onRetryResult(boolean accepted, String reason);
    }

    /** 承载 WebView 的容器；恢复/加载卡片由 {@link OverlaySurface} 负责替换内容。 */
    private final FrameLayout container;

    private final Context context;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final RenderAvailabilityState render;
    private AvailabilityListener availabilityListener;

    /** 页面是否已经开始加载（用于诊断与卡片文案）。 */
    private volatile boolean loadStarted;

    /** 加载失败后自动重试的间隔。 */
    public static final long RETRY_MS = 5000L;
    /** 连续失败多少次后停止自动重试（避免一直打一个明显不可达的地址）。 */
    public static final int MAX_RETRIES = 6;

    private String lastUrl = "";
    private int retries = 0;

    /**
     * 本 WebView 的加载代际：**由 {@link RenderAvailabilityState} 唯一持有**。
     *
     * 为什么不再各存一份（FIX-RUNTIME-02 的真实缺陷）：
     *   旧实现里 PetWindow 自己有一个 loadGeneration，状态机另有一个 generation。
     *   {@link #retryNow()} 只推进了前者，状态机代际没动 —— 重试之后的
     *   bootOk/portraitVisible 信号全部因代际不匹配被状态机丢弃，
     *   窗口再也升不到 READY。现场表现正是「页面已经重新加载，立绘仍不显示」。
     *   现在两个数字合并成同一个权威代际，任何一次 load/重试都推进它，
     *   旧页面的迟到信号因为代际落后而被丢弃。
     */
    /** 代际替换后仍挂着的失败重试任务，替换时必须撤销。 */
    private Runnable retryRunnable;
    /** 下一次有意的加载（load / 用户重试）是否算「手动重试」。 */
    private boolean nextRetryIsManual;
    /** 心跳是否处于活动状态（避免重复排期）。 */
    private boolean heartbeatRunning;

    /**
     * 诊断计数器与最近一次结果（全部去敏）。
     * 只保存稳定代码/行号/计数，不保存 URL、query、token、聊天或图片内容。
     */
    private volatile int netErrorCount;
    private volatile int httpErrorCount;
    private volatile int consoleErrorCount;
    private volatile int lastNetErrorCode = -1;
    private volatile boolean lastNetErrorMainFrame;
    private volatile int lastHttpStatus = -1;
    private volatile String lastConsoleType = "";
    private volatile int lastConsoleLine = -1;
    private volatile String lastConsoleSource = "";
    private volatile int lastSslError = -1;

    /** 本窗口的文件选择 owner：销毁时只取消自己的 pending，不影响新窗口。 */
    private static final java.util.concurrent.atomic.AtomicLong WINDOW_SEQ =
            new java.util.concurrent.atomic.AtomicLong();
    private final String chooserOwner = "pet-window-" + WINDOW_SEQ.incrementAndGet();

    public PetWindow(Context context) {
        this(context, null);
    }

    /**
     * 供故障注入/行为测试注入确定性状态机（可自定义超时与时钟）。
     *
     * @param injected 为 null 时使用生产默认状态机
     */
    PetWindow(Context context, RenderAvailabilityState injected) {
        this.context = context;
        this.render = injected != null ? injected : new RenderAvailabilityState();
        this.container = new FrameLayout(context);
        this.webView = new WebView(context);
        webView.setBackgroundColor(Color.TRANSPARENT);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        // 关闭 useWideViewPort：开着会让 WebView 退回 980px 的默认布局宽度，
        // 把页面里的 width=device-width 覆盖掉（实测视口变成 980 而不是窗口宽）。
        // 我们需要视口严格等于窗口宽度。
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        // 悬浮窗是实时界面，必须关掉缓存：
        // 实测它会连 HTML 一起吃旧缓存，导致改了 CSS/JS 后一直跑旧版
        //（页面上加版本参数也救不了，因为页面本身就没重新请求）。
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        webView.clearCache(true);
        webView.setWebViewClient(new OverlayClient());
        webView.setWebChromeClient(new OverlayChromeClient());

        // 默认不可见：只有确认有可见内容（READY）时才显示；否则显示卡片。
        webView.setVisibility(View.INVISIBLE);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    public void setAvailabilityListener(AvailabilityListener listener) {
        this.availabilityListener = listener;
    }

    /**
     * 悬浮窗自己的 WebViewClient。
     *
     * 存在的唯一原因：**加载失败时不能把浏览器的错误页显示出来**。
     *
     * 默认 WebViewClient 在加载失败时会渲染 Chromium 的错误页（浅色底），
     * 而悬浮窗是半透明窗口 —— 桌面从错误页底下透出来，看起来就是一块
     * 「有色方框」盖在桌面上，而且它**不会自动重试**，所以会一直卡在那儿。
     * 实测：电脑端插件没起来时打开桌面立绘，就是一块浅色方框，十几秒都不消失。
     *
     * 现在的处理办法：把失败**升级成状态机的失败分类**（不直接动窗口），
     * 由状态机/可见性控制器决定「移除窗口」还是「退成有界可见卡片」；
     * WebView 自身保持不可见，并在预算内重试。
     */
    private final class OverlayClient extends WebViewClient {

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            // 新页面开始：先当作「没有可见内容」，避免旧页面的可见内容
            // 让新页面在还没渲染时就被判成可用。
            loadStarted = true;
            view.setVisibility(View.INVISIBLE);
            notifyAvailability();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            // 主文档首帧提交只说明 DOM 开始可见，不说明立绘已解码：
            // 这里不做「可用」判断，等网页的立绘可见信号。
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 注意：错误页也会触发 onPageFinished！
            // 所以这里绝不能无条件 setVisibility(VISIBLE) —— 那等于把刚藏起来的
            // 错误页又显示出来（第一版就是这么写的，实测方框照旧）。
            // 是否真的可以显示，只由「已确认可见立绘」的状态决定。
            loadStarted = false;
            notifyAvailability();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            boolean mainFrame = request != null && request.isForMainFrame();
            int code = -1;
            try {
                if (error != null) {
                    code = error.getErrorCode();
                }
            } catch (Throwable ignored) {
                // 个别 ROM 的 error 对象不完整：只记录 -1
            }
            netErrorCount++;
            lastNetErrorCode = code;
            lastNetErrorMainFrame = mainFrame;
            if (mainFrame) {
                failCurrentLoad(RenderAvailabilityState.REASON_LOAD_FAILED);
            }
            // 子资源失败（例如某张立绘 404）不直接失败整个窗口：
            // 由网页上报「是否有可见立绘」后再判断，避免整窗被一张图拖垮。
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            // Android 6 以下走这个旧回调；它没有 isForMainFrame，保守当作主文档失败，
            // 但只在确实已经失败时升级（与新版回调可能同时触发，重复调用是幂等的）。
            netErrorCount++;
            lastNetErrorCode = errorCode;
            lastNetErrorMainFrame = true;
            failCurrentLoad(RenderAvailabilityState.REASON_LOAD_FAILED);
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                        WebResourceResponse errorResponse) {
            super.onReceivedHttpError(view, request, errorResponse);
            boolean mainFrame = request != null && request.isForMainFrame();
            int status = -1;
            try {
                if (errorResponse != null) {
                    status = errorResponse.getStatusCode();
                }
            } catch (Throwable ignored) {
                // 状态码取不到时只记 -1
            }
            httpErrorCount++;
            lastHttpStatus = status;
            if (mainFrame) {
                // 关键：HTTP 200/404 也要有证据。新脚本 200、电脑插件重启之后，
                // 4xx/5xx 才是真正的「页面被拒」——必须按失败处理，不能继续挂窗口。
                render.onHttpRejected(loadGeneration(), status);
                failCurrentLoad(RenderAvailabilityState.REASON_HTTP_REJECTED);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // 局域网是明文 HTTP，正常不会走到这里；真遇到了也不要弹系统错误页
            handler.cancel();
            int primary = -1;
            try {
                if (error != null) {
                    primary = error.getPrimaryError();
                }
            } catch (Throwable ignored) {
                // 忽略
            }
            lastSslError = primary;
            failCurrentLoad(RenderAvailabilityState.REASON_SSL_FAILED);
        }
    }

    /**
     * 控制台诊断（去敏）。
     *
     * 为什么放在 WebChromeClient：新版 API 的 WebViewClient 已经不再有
     * {@code onConsoleMessage} 回调，控制台消息统一由 WebChromeClient 接收。
     *
     * 只记录**异常类型、行号与来源文件名**，绝不记录消息正文（正文可能带
     * token/聊天/图片 dataURL）。任何真实 console 文本都不落地、不返回给网页。
     */
    private final class OverlayChromeClient extends WebChromeClient {
        @Override
        public boolean onConsoleMessage(ConsoleMessage message) {
            if (message != null) {
                consoleErrorCount++;
                try {
                    lastConsoleType = message.messageLevel() == null
                            ? "" : message.messageLevel().name();
                } catch (Throwable ignored) {
                    lastConsoleType = "";
                }
                try {
                    lastConsoleLine = message.lineNumber();
                } catch (Throwable ignored) {
                    lastConsoleLine = -1;
                }
                try {
                    // 只保留文件名尾段：绝不记录完整 URL/query/token
                    lastConsoleSource = sanitizeSource(message.sourceId());
                } catch (Throwable ignored) {
                    lastConsoleSource = "";
                }
            }
            // 不保存消息正文；返回 false 保持默认行为
            return false;
        }

        /**
         * 悬浮窗里的「选择图片」。
         *
         * 默认 WebView 没有实现 onShowFileChooser 时会直接取消选择，网页的
         * file input 永远拿不到结果。这里交给受控的辅助 Activity（不导出）调系统
         * 选择器，结果按 Uri 回传；窗口/WebView 销毁时只取消本窗口的 pending。
         *
         * WebChromeClient 契约：只有返回 true 才表示已接管并会回调。因此接管
         * 失败时**不调用**回调、返回 false，交给 WebView 自己取消（恰好一次）。
         */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> filePathCallback,
                                         FileChooserParams fileChooserParams) {
            if (filePathCallback == null) {
                return false;
            }
            try {
                return OverlayFileChooser.request(context, chooserOwner,
                        filePathCallback, fileChooserParams);
            } catch (Throwable error) {
                // 接线异常：不调用回调（返回 false 由 WebView 取消），绝不留 pending
                return false;
            }
        }
    }

    // ---- 加载与失败处理 ------------------------------------------------

    /** 主文档失败：升级失败分类 + 安排预算内重试；不自己动 WindowManager。 */
    private void failCurrentLoad(String reason) {
        if (lastUrl.isEmpty()) {
            return;
        }
        webView.setVisibility(View.INVISIBLE);
        if (RenderAvailabilityState.REASON_HTTP_REJECTED.equals(reason)) {
            // HTTP 状态已由 onReceivedHttpError 记入状态机
        } else if (RenderAvailabilityState.REASON_SSL_FAILED.equals(reason)) {
            render.onLoadFailed(loadGeneration(), RenderAvailabilityState.REASON_SSL_FAILED);
        } else {
            render.onLoadFailed(loadGeneration(), reason);
        }
        notifyAvailability();
        scheduleRetry();
    }

    /** 预算内安排一次自动重试；超出预算则通知宿主（宿主停在恢复卡片）。 */
    private void scheduleRetry() {
        cancelRetry();
        if (retries >= MAX_RETRIES) {
            render.onRetriesExhausted(loadGeneration());
            notifyAvailability();
            if (availabilityListener != null) {
                availabilityListener.onRetryResult(false, "预算耗尽");
            }
            return;
        }
        // 关键：预算在**安排重试时**就消耗掉。
        // 旧写法只在重试真正执行时计数，而重试可能被取消/被新加载替换，
        // 于是「连续失败」永远不会耗尽预算，窗口就会无限重试下去。
        retries++;
        retryRunnable = new Runnable() {
            @Override
            public void run() {
                retryRunnable = null;
                retryNow();
            }
        };
        handler.postDelayed(retryRunnable, RETRY_MS);
    }

    private void cancelRetry() {
        if (retryRunnable != null) {
            handler.removeCallbacks(retryRunnable);
            retryRunnable = null;
        }
    }
    /**
     * 立即发起一次重试（用户点「重试」或自动重试到点）。
     *
     * 关键顺序（FIX-RUNTIME-02）：
     *   1. 先让状态机裁决这次重试是否被接受（含手动重试的预算重置）；
     *   2. 接受后才真正 loadUrl，并**同时推进状态机代际**（{@code onAttemptStarted}）。
     *      旧实现只推进 PetWindow 自己的计数器，状态机代际不动，于是重试之后
     *      网页送来的 bootOk/portraitVisible 全部因代际不匹配被丢弃 ——
     *      这正是「重试后立绘永远不出现」的直接原因。
     *   3. 被拒绝（预算耗尽且非手动）时不发起加载，也不推进代际，
     *      停在恢复卡片等用户显式重试。
     *
     * @return true 表示确实发起了一次加载
     */
    public boolean retryNow() {
        cancelRetry();
        if (lastUrl.isEmpty()) {
            if (availabilityListener != null) {
                availabilityListener.onRetryResult(false, "无地址");
            }
            return false;
        }
        webView.setVisibility(View.INVISIBLE);
        loadFailed = false;
        boolean manual = nextRetryIsManual;
        nextRetryIsManual = false;
        if (!render.onRetryRequested(manual)) {
            notifyAvailability();
            if (availabilityListener != null) {
                availabilityListener.onRetryResult(false, "预算耗尽");
            }
            return false;
        }
        // 权威代际：本次尝试与随后网页送来的信号必须落在同一代际上。
        render.onAttemptStarted();
        webView.loadUrl(lastUrl);
        if (availabilityListener != null) {
            availabilityListener.onRetryResult(true, "");
        }
        notifyAvailability();
        return true;
    }

    /** 重试预算是否已耗尽。 */
    public boolean retriesExhausted() {
        return retries >= MAX_RETRIES;
    }

    /** 标记下一次 retryNow() 为用户手动重试（会重置自动重试预算）。 */
    public void markNextRetryManual() {
        nextRetryIsManual = true;
    }

    /** 当前是否还有自动重试排期。 */
    public boolean retryScheduled() {
        return retryRunnable != null;
    }

    /**
     * 撤销已排期的自动重试（仅故障注入测试使用）。
     *
     * 真实设备上重试由 {@link #RETRY_MS} 延迟触发；替身 Handler 没有延迟语义，
     * 行为测试需要显式区分「安排重试」与「执行重试」两个时刻。
     */
    void cancelScheduledRetry() {
        cancelRetry();
    }

    /**
     * 把 WebView 可见性对齐到当前渲染可用性。
     *
     * FIX-RUNTIME-03：**加载期间也必须让 WebView 真正可见并参与布局/绘制**。
     * 旧实现「没确认立绘就 INVISIBLE」，而窗口又被移除 —— 于是
     * 「等可见才挂载」与「先挂载才能渲染」互相等待：网页的立绘预加载
     * 与图片解码无法推进，ready 信号永远不来，窗口永远升不到完整形态。
     * 现在窗口以**有界可见卡片**承载加载期，WebView 在该卡片内正常工作；
     * 只有到达 FAILED（重试预算耗尽、需要用户显式重试）才收起 WebView，
     * 由卡片给出明确结果，避免继续跑一个明显不可达的页面。
     */
    private void applyWebViewVisibility() {
        if (render.phase() == RenderAvailabilityState.Phase.FAILED) {
            webView.setVisibility(View.INVISIBLE);
            return;
        }
        webView.setVisibility(View.VISIBLE);
    }

    private boolean loadFailed;

    @SuppressLint("SetJavaScriptEnabled")
    public void addJavascriptBridge(RemoteBridge bridge) {
        webView.addJavascriptInterface(bridge, "SakuraNative");
    }

    /** 外层容器：包含 WebView（以及可能的恢复卡片）。 */
    public View view() {
        return container;
    }

    public WebView webView() {
        return webView;
    }

    /** 当前加载代际（与状态机共享同一个权威代际）；网页桥可带回来做陈旧信号判别。 */
    public long loadGeneration() {
        return render.generation();
    }

    public RenderAvailabilityState renderState() {
        return render;
    }

    /**
     * 去敏诊断快照（类型化）。
     *
     * 现场需要能**只看 logcat** 就判定失败分支：是主文档网络失败、HTTP 被拒、
     * JS/boot 失败，还是重试预算耗尽。字段全部是稳定代码/布尔/计数：
     * 网络错误码、HTTP 状态、console 异常类型与行号、boot/立绘状态、阶段与
     * 失败分类、心跳、重试进度与代际。
     *
     * **不含** URL、query、token、聊天内容、图片 dataURL 或任意 console 正文。
     */
    public static final class Diagnostics {
        public final String phase;
        public final String reason;
        public final String consoleType;
        public final String consoleSource;
        public final boolean attachable;
        public final boolean cardAllowed;
        public final boolean heartbeat;
        public final boolean retryScheduled;
        public final boolean netMainFrame;
        public final int netErrors;
        public final int lastNetCode;
        public final int httpErrors;
        public final int lastHttpStatus;
        public final int consoleErrors;
        public final int consoleLine;
        public final int retryCount;
        public final int maxRetries;
        public final int sslError;
        public final long generation;

        Diagnostics(String phase, String reason, String consoleType, String consoleSource,
                    boolean attachable, boolean cardAllowed, boolean heartbeat,
                    boolean retryScheduled, boolean netMainFrame, int netErrors,
                    int lastNetCode, int httpErrors, int lastHttpStatus, int consoleErrors,
                    int consoleLine, int retryCount, int maxRetries, int sslError,
                    long generation) {
            this.phase = phase;
            this.reason = reason;
            this.consoleType = consoleType;
            this.consoleSource = consoleSource;
            this.attachable = attachable;
            this.cardAllowed = cardAllowed;
            this.heartbeat = heartbeat;
            this.retryScheduled = retryScheduled;
            this.netMainFrame = netMainFrame;
            this.netErrors = netErrors;
            this.lastNetCode = lastNetCode;
            this.httpErrors = httpErrors;
            this.lastHttpStatus = lastHttpStatus;
            this.consoleErrors = consoleErrors;
            this.consoleLine = consoleLine;
            this.retryCount = retryCount;
            this.maxRetries = maxRetries;
            this.sslError = sslError;
            this.generation = generation;
        }
    }

    /** 当前去敏诊断快照（供服务按阶段/重试变化写限频日志）。 */
    public Diagnostics diagnostics() {
        try {
            return new Diagnostics(
                    render.phase().name(), render.reason(),
                    lastConsoleType == null ? "" : lastConsoleType,
                    lastConsoleSource == null ? "" : lastConsoleSource,
                    render.isRenderAttachable(), render.isCardAllowed(), heartbeatRunning,
                    retryScheduled(), lastNetErrorMainFrame, netErrorCount, lastNetErrorCode,
                    httpErrorCount, lastHttpStatus, consoleErrorCount, lastConsoleLine,
                    retries, MAX_RETRIES, lastSslError, render.generation());
        } catch (Throwable error) {
            return new Diagnostics("unknown", "diag_error", "", "", false, false, false,
                    false, false, 0, -1, 0, -1, 0, -1, 0, MAX_RETRIES, -1, -1L);
        }
    }

    public void load(String url) {
        if (url != null && !url.isEmpty()) {
            cancelRetry();
            lastUrl = url;
            retries = 0;
            loadFailed = false;   // 新加载：清掉失败标记（onPageFinished 会读它）
            // 新加载 = 新页面会话：推进权威代际（清掉上一页的 ready 与重试预算），
            // 再作为「手动重试」接纳本次加载，最后才真正 loadUrl。
            render.onSessionStarted();
            nextRetryIsManual = true;
            render.onRetryRequested(true);
            nextRetryIsManual = false;
            webView.setVisibility(View.INVISIBLE);
            webView.loadUrl(url);
            startHeartbeat();
            notifyAvailability();
        }
    }

    // ---- 网页信号（全部去敏）------------------------------------------

    /** 网页 boot 完成（DOM 就绪、桥可用）。 */
    public void onPageBootOk() {
        render.onBootOk(loadGeneration());
        notifyAvailability();
    }

    /** 网页主动上报 boot 失败。 */
    public void onPageBootFailed() {
        render.onBootFailed(loadGeneration());
        notifyAvailability();
    }

    /** 确认有可见且已解码的立绘：唯一允许挂完整窗口的依据。 */
    public void onPortraitVisible() {
        render.onPortraitVisible(loadGeneration());
        retries = 0;   // 成功一次即重置重试预算
        notifyAvailability();
    }

    /**
     * 带代际的立绘可见信号（网页桥带回来的 loadGeneration）。
     *
     * 代际不匹配说明这是旧页面的迟到信号：直接丢弃，绝不恢复新窗口。
     *
     * @return true 表示信号被接受
     */
    public boolean onPortraitVisibleForGeneration(long expectedGeneration) {
        if (expectedGeneration != loadGeneration()) {
            return false;
        }
        onPortraitVisible();
        return true;
    }

    /** 网页 pagehide / 导航开始：立即视为没有可见内容。 */
    public void onPageInvalidated() {
        render.onSessionInvalidated(loadGeneration());
        notifyAvailability();
    }

    /**
     * 由宿主在「本次页面会话更换」时调用：推进权威代际并回到 LOADING。
     *
     * @return 推进后的新代际（宿主/诊断可用）
     */
    public long onNewPageSession() {
        cancelRetry();
        retries = 0;
        loadFailed = false;
        // 新会话同时作废旧 WebView 代际：旧页面随后送来的带代际信号一律被丢弃。
        render.onSessionStarted();
        startHeartbeat();
        notifyAvailability();
        return loadGeneration();
    }

    private void notifyAvailability() {
        applyWebViewVisibility();
        AvailabilityListener listener = availabilityListener;
        if (listener == null) {
            return;
        }
        listener.onRenderAvailability(render.isRenderAttachable(),
                render.phase().name(), render.reason());
    }

    /**
     * 心跳：推进状态机（LOADING→WARNING→AUTO_RECOVERY 等）；FAILED 终止。
     *
     * 心跳现在由本类自己按状态机给出的间隔续期（{@link #startHeartbeat()}），
     * 不再依赖「窗口是否 attached」——V1.2.6 现场缺陷之一就是 renderHeartbeat
     * 只在窗口挂载时续期：窗口一旦 detach（加载期/恢复期），tick 与自动重试
     * 就再也不会发生，FAILED 恢复卡片永远不出现。
     *
     * 到点该自动重试时先发起一次真实重试（推进权威代际），再继续推进时间线。
     */
    public boolean tick() {
        if (render.autoRetryPending() && !lastUrl.isEmpty()) {
            // AUTO_RECOVERY 到点：做一次真实的自动重试。
            // 只由心跳触发，预算仍由状态机约束（预算耗尽的自动重试会被拒绝）。
            retryNow();
        }
        boolean changed = render.tick();
        if (changed) {
            // 页面/卡片可见性只由「是否已确认可见立绘」决定：
            // FAILED 时收起 WebView、由卡片给出明确结果；其余阶段页面必须可见，
            // 否则加载/预加载根本无法推进。
            applyWebViewVisibility();
            notifyAvailability();
        }
        return changed;
    }

    /**
     * 启动渲染心跳（幂等）。
     *
     * 只要状态机还需要周期推进（LOADING/WARNING/AUTO_RECOVERY）就续期；
     * READY 与 FAILED 是稳定终点，心跳自然停下，不会空转。
     */
    public void startHeartbeat() {
        if (heartbeatRunning) {
            return;
        }
        heartbeatRunning = true;
        handler.removeCallbacks(tickRunnable);
        long delay = render.nextTickDelayMs();
        if (delay <= 0) {
            delay = 2000L;
        }
        handler.postDelayed(tickRunnable, Math.min(delay, 2000L));
    }

    /** 停掉心跳（窗口被隐藏 / 服务销毁）：销毁后不再有任何 tick 或自动重试。 */
    public void stopHeartbeat() {
        heartbeatRunning = false;
        handler.removeCallbacks(tickRunnable);
    }

    /** 心跳是否在跑（诊断用；READY/FAILED 是稳定终点，心跳会自然停下）。 */
    public boolean isHeartbeatRunning() {
        return heartbeatRunning;
    }

    /** 心跳任务：由宿主 post 触发；按状态机需要续期。 */
    public final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            tick();
            if (!heartbeatRunning) {
                return;
            }
            if (render.needsPeriodicTick()) {
                long delay = render.nextTickDelayMs();
                if (delay <= 0) {
                    delay = 2000L;
                }
                handler.postDelayed(this, Math.min(delay, 2000L));
            } else {
                // AUTO_RECOVERY 到点：心跳让位给自动重试（已在 tick() 内发起）。
                heartbeatRunning = false;
            }
        }
    };

    /**
     * 去敏诊断快照（JSON）。
     *
     * 只含稳定代码与计数：主文档网络错误码、HTTP 状态、console 异常类型与行号、
     * boot/立绘状态、区域结果与代际。**不含** URL、query、token、聊天、图片 dataURL。
     */
    public String diagnosticsSnapshot() {
        try {
            RenderAvailabilityState.Phase phase = render.phase();
            return "{\"phase\":" + org.json.JSONObject.quote(phase.name())
                    + ",\"reason\":" + org.json.JSONObject.quote(render.reason())
                    + ",\"renderAttachable\":" + render.isRenderAttachable()
                    + ",\"cardAllowed\":" + render.isCardAllowed()
                    + ",\"bootOk\":" + render.bootOk()
                    + ",\"portraitVisible\":" + render.visiblePortrait()
                    + ",\"generation\":" + render.generation()
                    + ",\"loadGeneration\":" + loadGeneration()
                    + ",\"heartbeat\":" + heartbeatRunning
                    + ",\"webViewVisibility\":" + webView.getVisibility()
                    + ",\"autoRetryPending\":" + render.autoRetryPending()
                    + ",\"retryCount\":" + retries
                    + ",\"maxRetries\":" + MAX_RETRIES
                    + ",\"netErrorCount\":" + netErrorCount
                    + ",\"lastNetErrorCode\":" + lastNetErrorCode
                    + ",\"lastNetErrorMainFrame\":" + lastNetErrorMainFrame
                    + ",\"httpErrorCount\":" + httpErrorCount
                    + ",\"lastHttpStatus\":" + lastHttpStatus
                    + ",\"consoleErrorCount\":" + consoleErrorCount
                    + ",\"lastConsoleType\":" + org.json.JSONObject.quote(lastConsoleType)
                    + ",\"lastConsoleLine\":" + lastConsoleLine
                    + ",\"lastConsoleSource\":" + org.json.JSONObject.quote(lastConsoleSource)
                    + ",\"lastSslError\":" + lastSslError
                    + "}";
        } catch (Throwable error) {
            return "{\"phase\":\"unknown\",\"reason\":\"diag_error\"}";
        }
    }

    private static String sanitizeSource(String source) {
        if (source == null || source.isEmpty()) {
            return "";
        }
        String value = source;
        int query = value.indexOf('?');
        if (query >= 0) {
            value = value.substring(0, query);
        }
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        int slash = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        if (slash >= 0 && slash + 1 < value.length()) {
            value = value.substring(slash + 1);
        }
        if (value.length() > 48) {
            value = value.substring(0, 48);
        }
        return value;
    }

    /** 输入框聚焦时窗口必须可获取焦点，软键盘才出得来。 */
    public void applyFocusable(android.view.WindowManager.LayoutParams params, boolean focusable) {
        int noFocus = android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (focusable) {
            params.flags &= ~noFocus;
        } else {
            params.flags |= noFocus;
        }
    }

    public void destroy() {
        // 先停掉心跳与重试，否则窗口都销毁了还在每 5 秒 loadUrl 一次、
        // 或者心跳继续推进时间线（FIX-RUNTIME-04：销毁后不得越权）。
        stopHeartbeat();
        handler.removeCallbacksAndMessages(null);
        cancelRetry();
        lastUrl = "";
        // 窗口销毁：只取消本窗口待处理的文件选择，不误伤新窗口刚登记的请求
        FileChooserBridge.cancelAll(chooserOwner);
        try {
            container.removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
            // 视图可能已被系统回收
        }
    }
}
