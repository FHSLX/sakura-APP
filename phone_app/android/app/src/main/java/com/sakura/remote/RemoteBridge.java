package com.sakura.remote;

import android.webkit.JavascriptInterface;

/**
 * 让本地连接设置页能调用原生能力。
 *
 * 为什么需要：
 *  1. Capacitor 把本地页跑在 https://localhost，从 https 页面用 location.replace
 *     跳到 http://192.168.x.x 属于跨源导航，WebView 会静默拦掉，
 *     表现为「测试连接」通过但页面不动。交给原生层 loadUrl 就没这个问题。
 *  2. 桌面立绘是原生悬浮窗，需要网页提供一个开关；
 *     角色缩放也要两边共享同一个值。
 */
public class RemoteBridge {

    public interface Host {
        void loadRemote(String url);

        void setPetScale(float scale);

        float getPetScale();

        boolean isOverlayRunning();

        /** 返回 true 表示直接启动了；false 表示需要用户先授予悬浮窗权限。 */
        boolean startOverlay();

        void stopOverlay();

        /** 打开系统的「电池优化白名单」申请页/弹窗。 */
        void requestBatteryExemption();

        boolean isIgnoringBatteryOptimizations();

        /** 网页保存地址后同步给原生，悬浮窗才知道连哪儿。 */
        void saveServer(String server, String token);

        /**
         * 远程页回报自己的地址，原生侧用成功上报修复陈旧配置。
         *
         * @param overlayPage true = 来自桌面立绘/小球页面；该页面的上报不得把
         *                    主 App 的新配置写回旧地址
         */
        void rememberRemoteUrl(String url, boolean overlayPage);

        /**
         * 进入「桌面立绘模式」：开启悬浮窗并收起 App，
         * 让角色真正留在桌面上（否则 App 在前台会把悬浮窗盖住）。
         */
        void enterOverlayMode();

        /** 输入框聚焦状态变化：悬浮窗要据此切换是否可获取焦点（软键盘）。 */
        void setKeyboardOpen(boolean open);

        /** 迷你图标模式：缩成小图标 / 恢复完整窗口。 */
        void setBubbleMode(boolean bubble);

        /** 切换窗口是否接收触摸（false = 点击穿透到下层）。 */
        void setTouchable(boolean touchable);

        /**
         * 告知原生「正在拖动窗口」。拖动期间会跳过 resize 后的位置夹取 ——
         * 否则窗口尺寸一变就把拖动位移抵消掉（表现为拖不动）。
         */
        void setDragging(boolean dragging);

        /** 按增量移动窗口（去掉原生标题栏后，拖动由网页手势驱动）。 */
        void moveWindowBy(float dx, float dy);

        /**
         * 直接把窗口移到指定位置（CSS 像素，左上角为准）。
         *
         * 拖动改用绝对定位而不是累加增量：逐帧累加会被每帧取整和边界夹取
         * 一点点吃掉误差，拖久了窗口和手指就对不上（实测「不跟手」的主因之一）。
         * 绝对定位下位置只由「手指当前位置」决定，不做累加，因此不会漂。
         */
        void setWindowPositionPx(float x, float y);

        /**
         * 悬浮窗布局信息，供网页换算坐标。
         *
         * 返回 JSON：{x, y, w, h, screenW, screenH, density}
         *   x/y/w/h  窗口位置与尺寸，**设备像素**
         *   screenW/H 屏幕尺寸，设备像素
         *   density  设备像素 / CSS 像素
         *
         * 网页拖动时要用它把「手指的 CSS 像素坐标」换算成窗口的绝对位置。
         */
        String overlayLayoutInfo();

        /** 由网页请求调整窗口尺寸（CSS 像素）；原生夹到屏幕范围内。 */
        void setWindowSizePx(float width, float height);

        /** 请求一次手机截屏；结果通过 onScreenCapture 异步回传。 */
        void requestScreenshot(String pageSessionId);

        /** 只申请截屏授权（不截取），供设置页的「授权截屏权限」按钮使用。 */
        void requestScreenPermission(String pageSessionId);

        boolean hasScreenPermission();

        /** 把截图结果交给网页（由宿主回调）。 */
        void deliverScreenshot(String dataUrl, String error);

        // ---- 页面会话登记（RV-02）----

        /** 页面 boot / 导航完成时登记本次加载的真实会话 id。 */
        void registerPageSession(String pageSessionId);

        /** 页面 pagehide / 导航开始时注销会话，让旧结果立即失效。 */
        void invalidatePageSession(String pageSessionId);

        /** 悬浮窗权限、运行状态、电池白名单，一次读全，供设置页渲染。 */
        String petStatus();

        // ---- 显示 / 穿透偏好（App 与浮窗共用一份持久状态）----

        /**
         * 去敏偏好快照（JSON）：{float:{has,value}, passThrough:{has,value,supported}}。
         *
         * <p>默认实现返回「无能力、无偏好」，保证旧宿主/测试替身不必改动。
         */
        default String displayPreferences() {
            return "{\"supported\":false}";
        }

        /**
         * 写入显示 / 穿透偏好，返回可校验回执（{"ok":true,...} 或 {"ok":false,"error":"..."}）。
         *
         * <p>App 侧只持久化意图并经安全通道同步运行中的浮窗；**不提交区域裁剪**。
         */
        default String setDisplayPreferences(String json) {
            return "{\"ok\":false,\"error\":\"unsupported\"}";
        }

        // ---- 精确局部穿透（API33+ AttachedSurfaceControl）----

        /**
         * 提交一次输入区域快照（JSON，见 OverlayTouchRegionController）。
         * 返回稳定结果码：
         *   queued / stale / not-registered / disabled / unsupported / invalid。
         * queued 只表示已受理；真正 applied 由 touchRegionStatus() 的 revision ACK。
         */
        String setTouchRegion(String json);

        /** 关闭精确穿透：把窗口输入区域恢复成整窗。 */
        void clearTouchRegion();

        /** 本机是否具备公开的精确穿透 API（API33+）。 */
        boolean isTouchRegionSupported();

        /** 启用/停用精确穿透模式（会同时处理 Android12+ 窗口 alpha 安全阈值）。 */
        void setPassThroughEnabled(boolean enabled);

        /** 精确穿透的当前状态（JSON），供界面显示。 */
        String touchRegionStatus();

        // ---- 渲染可用性信号（FIX-BLANK-01 / FIX-RUNTIME-01）----
        //
        // 注意：网页上报的 bootOk / bootFailed / portraitVisible / pageSession
        // **不在这里**。它们是桥线程回调，必须经 {@link RenderSignalRouter}
        // 封送到主线程后落到 {@link RenderSignalSink}，绝不能同步直调宿主去改
        // View/WindowManager。见 notifyOverlay* 方法与 setRenderSignalSink()。

        /** 去敏渲染诊断快照（JSON）：稳定代码/计数，不含 URL/token/聊天/图片内容。 */
        String renderDiagnostics();
    }

    /** 诊断快照提供者；由宿主注入（OverlayService），避免 Bridge 反向依赖。 */
    public interface DiagnosticsProvider {
        String snapshot();
    }

    private final Host host;
    private DiagnosticsProvider diagnosticsProvider;
    /**
     * 渲染信号的**主线程路由器**（FIX-RUNTIME-01）。
     *
     * 这些方法由网页通过 {@code @JavascriptInterface} 调用，运行在 WebView
     * 桥线程。桥线程绝不能直接改 View/WindowManager —— 旧实现同步直调到
     * OverlayService 才导致真机「信号都到了、窗口不挂」。所有渲染信号统一
     * 经过本路由器封送到主线程后，才落到 {@link RenderSignalSink}。
     */
    private RenderSignalRouter renderRouter;

    public RemoteBridge(Host host) {
        this.host = host;
    }

    public void setRenderDiagnosticsProvider(DiagnosticsProvider provider) {
        this.diagnosticsProvider = provider;
    }

    /**
     * 注入主线程落点并建立路由器；由宿主（OverlayService）在创建悬浮窗时调用。
     *
     * @param sink 只允许在主线程执行的落点；为 null 时信号只记诊断不落地
     */
    public void setRenderSignalSink(RenderSignalSink sink) {
        this.renderRouter = new RenderSignalRouter(sink);
    }

    /** 供行为测试注入替身 Poster（可区分「入队」与「主线程执行」）。 */
    void setRenderSignalRouterForTest(RenderSignalRouter router) {
        this.renderRouter = router;
    }

    public RenderSignalRouter renderRouter() {
        return renderRouter;
    }

    /** 由本地页调用；名字与 MainActivity 里的 addJavascriptInterface 一致。 */
    @JavascriptInterface
    public void open(final String url) {
        if (url == null) {
            return;
        }
        final String target = url.trim();
        // 只接受 http/https，避免被本地页注入奇怪的 scheme。
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            return;
        }
        host.loadRemote(target);
    }

    @JavascriptInterface
    public void setScale(float value) {
        host.setPetScale(value);
    }

    @JavascriptInterface
    public float getScale() {
        return host.getPetScale();
    }

    @JavascriptInterface
    public boolean isOverlayRunning() {
        return host.isOverlayRunning();
    }

    @JavascriptInterface
    public boolean startOverlay() {
        return host.startOverlay();
    }

    @JavascriptInterface
    public void stopOverlay() {
        host.stopOverlay();
    }

    @JavascriptInterface
    public void requestBatteryExemption() {
        host.requestBatteryExemption();
    }

    @JavascriptInterface
    public boolean isIgnoringBatteryOptimizations() {
        return host.isIgnoringBatteryOptimizations();
    }

    @JavascriptInterface
    public void saveServer(String server, String token) {
        host.saveServer(server, token);
    }

    /**
     * 主 App 的远程页进入后回报自己的地址：用成功上报修复陈旧配置。
     *
     * 为什么需要：连接设置页跑在 https://localhost，远程页跑在
     * http://电脑IP:8770 —— 两者是不同源，localStorage 不互通。
     * 用户在连接页换了新 IP 后，原生侧可能还留着连不上的旧主机
     *（真机表现为 ERROR_HOST_LOOKUP = -2），这里用实际加载成功的地址覆盖它。
     */
    @JavascriptInterface
    public void rememberUrl(String url) {
        host.rememberRemoteUrl(url, false);
    }

    /**
     * 桌面立绘 / 小球页面回报自己的地址。
     *
     * <p>旧立绘页面的地址永远不能覆盖主 App 的新配置，所以单独走一条入口，
     * 由宿主按「立绘页面」规则处理（已配置时忽略）。
     */
    @JavascriptInterface
    public void rememberOverlayUrl(String url) {
        host.rememberRemoteUrl(url, true);
    }

    /** 返回 JSON 字符串；网页解析后决定按钮状态。 */
    @JavascriptInterface
    public String petStatus() {
        return host.petStatus();
    }

    /**
     * 偏好快照：App / 悬浮窗 / 小球读同一份持久状态，不依赖两个 WebView 的 localStorage。
     */
    @JavascriptInterface
    public String displayPreferences() {
        return host.displayPreferences();
    }

    /**
     * 写入偏好。App 页面只持久化意图 + 通过安全通道同步运行中浮窗；
     * 真正的区域裁剪仍只由悬浮窗页面的 SakuraTouchRegion 提交。
     */
    @JavascriptInterface
    public String setDisplayPreferences(String json) {
        return host.setDisplayPreferences(json == null ? "" : json);
    }

    /**
     * 进入桌面立绘模式：开启悬浮窗并收起 App。
     *
     * 网页只能调原生方法，没法自己「最小化」，所以这一步必须由原生做。
     * 不做这一步的话，用户在设置页点开启后 App 还在前台，
     * 悬浮窗被自己的窗口盖住，看起来就像「打开软件时不显示」。
     */
    @JavascriptInterface
    public void enterOverlayMode() {
        host.enterOverlayMode();
    }

    /**
     * 输入框聚焦状态变化。
     *
     * 悬浮窗默认是 FLAG_NOT_FOCUSABLE（否则会抢别的应用的输入），
     * 但那样软键盘弹不出来；网页在输入框聚焦/失焦时通知我们切换。
     */
    @JavascriptInterface
    public void setKeyboardOpen(boolean open) {
        host.setKeyboardOpen(open);
    }

    /** 迷你图标模式：true 缩成小图标，false 恢复完整窗口。 */
    @JavascriptInterface
    public void setBubbleMode(boolean bubble) {
        host.setBubbleMode(bubble);
    }

    /**
     * 设置窗口是否接收触摸。
     *
     * 数据来自网页的 `document.elementFromPoint`：指针在立绘/气泡/按钮上时为 true，
     * 在空白处时为 false —— 后者让窗口整体不接收触摸，点击就穿透到桌面或其他应用。
     * 这是桌面宠物类悬浮窗的标准做法（FLAG_NOT_TOUCHABLE 动态切换）。
     */
    @JavascriptInterface
    public void setTouchable(boolean touchable) {
        host.setTouchable(touchable);
    }

    /** 告知原生拖动开始/结束，拖动期间跳过 resize 的位置夹取。 */
    @JavascriptInterface
    public void setDragging(boolean dragging) {
        host.setDragging(dragging);
    }

    /**
     * 按增量移动窗口。
     *
     * 去掉原生标题栏之后，窗口没有自带的拖动区了，所以拖动由网页手势驱动：
     * 长按立绘后移动 → 这里把位移量交给原生去改窗口坐标。
     */
    @JavascriptInterface
    public void moveBy(float dx, float dy) {
        host.moveWindowBy(dx, dy);
    }

    /**
     * 把窗口直接移到绝对位置（CSS 像素）。
     *
     * 拖动改用绝对定位：位置只由「手指当前位置」决定，不做逐帧累加，
     * 因此不会因为取整和边界夹取而越拖越偏。
     */
    @JavascriptInterface
    public void moveTo(float x, float y) {
        host.setWindowPositionPx(x, y);
    }

    /** 悬浮窗布局信息（位置/尺寸/屏幕/密度），供网页换算拖动坐标。 */
    @JavascriptInterface
    public String overlayLayout() {
        return host.overlayLayoutInfo();
    }

    /**
     * 请求把窗口调成指定尺寸（CSS 像素）。
     *
     * 网页知道立绘的真实宽高比和当前缩放倍率，由它算出「需要多大窗口」，
     * 原生只负责套用并夹到屏幕范围内。否则窗口尺寸写死时，
     * 放大立绘就会超出窗口被裁掉 —— 这是必须由网页驱动的原因。
     */
    @JavascriptInterface
    public void setWindowSize(float width, float height) {
        host.setWindowSizePx(width, height);
    }

    /**
     * 请求一次手机截屏。
     *
     * 首次会弹系统授权框；结果通过 {@link #onScreenCapture(String, String)}
     * 异步回传，调用方（网页）要先定义 `window.SakuraNative.onScreenCapture`。
     */
    @JavascriptInterface
    public void requestScreenshot() {
        host.requestScreenshot("");
    }

    /**
     * 带页面会话 id 的截图请求（推荐）。
     *
     * pageSessionId 由网页在每次加载时生成；结果只会送回**发起请求且会话仍匹配**
     * 的页面。WebView reload 之后旧会话的迟到结果会被丢弃，不会出现在新页面上。
     */
    @JavascriptInterface
    public void requestScreenshotWithSession(String pageSessionId) {
        host.requestScreenshot(pageSessionId == null ? "" : pageSessionId);
    }

    /** 只申请截屏授权，不截取。设置页的「授权截屏权限」按钮用。 */
    @JavascriptInterface
    public void requestScreenPermission() {
        host.requestScreenPermission("");
    }

    @JavascriptInterface
    public void requestScreenPermissionWithSession(String pageSessionId) {
        host.requestScreenPermission(pageSessionId == null ? "" : pageSessionId);
    }

    @JavascriptInterface
    public boolean hasScreenPermission() {
        return host.hasScreenPermission();
    }

    // ---- 页面会话登记（RV-02）----

    /**
     * 登记本次页面加载的会话 id。
     *
     * 原生不再拿随机会话 id 和 URL 比较，而是按 (宿主 → 会话) 登记：
     * 新页登记即替换旧会话，同 URL reload 也会换新 id，旧结果自然失效。
     */
    @JavascriptInterface
    public void registerPageSession(String pageSessionId) {
        host.registerPageSession(pageSessionId == null ? "" : pageSessionId);
    }

    /** 页面卸载/导航开始时注销会话。 */
    @JavascriptInterface
    public void invalidatePageSession(String pageSessionId) {
        host.invalidatePageSession(pageSessionId == null ? "" : pageSessionId);
    }

    /**
     * 渲染信号路由器；未注入落点时用一个空路由器，保证桥方法永不抛异常回网页。
     *
     * 「永不抛异常」是刻意的：网页侧 notifyRenderSession 捕获异常后静默返回 false，
     * 一旦这里抛出，现场失败会被完全掩盖（这正是旧实现在真机上难查的原因）。
     */
    private RenderSignalRouter route() {
        RenderSignalRouter router = renderRouter;
        if (router == null) {
            router = new RenderSignalRouter(null);
            renderRouter = router;
        }
        return router;
    }

    // ---- 精确局部穿透 ----

    /**
     * 提交输入区域快照。
     *
     * 载荷里的矩形是 **WebView 内容坐标系（CSS 像素）**，不是屏幕坐标：
     * 原生按 inputView 实际像素 / 报告的 CSS 视口尺寸校准，并只加 WebView
     * 相对窗口的偏移。窗口拖动时本地轮廓不变，所以这里不需要也不应该
     * 叠加窗口 x/y。
     */
    @JavascriptInterface
    public String setTouchRegion(String json) {
        return host.setTouchRegion(json);
    }

    /** 关闭精确穿透，恢复整窗可交互（用户永远能拿回控制）。 */
    @JavascriptInterface
    public void clearTouchRegion() {
        host.clearTouchRegion();
    }

    @JavascriptInterface
    public boolean isTouchRegionSupported() {
        return host.isTouchRegionSupported();
    }

    @JavascriptInterface
    public void setPassThroughEnabled(boolean enabled) {
        host.setPassThroughEnabled(enabled);
    }

    @JavascriptInterface
    public String touchRegionStatus() {
        return host.touchRegionStatus();
    }

    // ---- 渲染可用性信号（FIX-BLANK-01 / FIX-RUNTIME-01）----

    /**
     * 网页 boot 开始/导航开始：通知原生推进渲染代际。
     *
     * 必须在页面级引导最前面调用（与 registerPageSession 同处），
     * 让原生立刻知道「旧页面内容已不可信」。
     *
     * 桥线程 → 主线程：这里只入队，真正的代际推进与窗口裁决由路由器在主线程执行。
     */
    @JavascriptInterface
    public void notifyOverlayPageSession(String pageSessionId) {
        route().pageSession(pageSessionId);
    }

    /** 网页 boot 完成：DOM/桥就绪，但立绘可能还在解码。 */
    @JavascriptInterface
    public void notifyOverlayBootOk(String pageSessionId) {
        route().bootOk(pageSessionId);
    }

    /** 网页 boot 失败（初始化异常/拿不到状态）；只传会话 id，不传错误原文。 */
    @JavascriptInterface
    public void notifyOverlayBootFailed(String pageSessionId) {
        route().bootFailed(pageSessionId);
    }

    /**
     * 确认有可见且已解码的立绘：原生据此才允许挂完整透明窗口。
     *
     * 无参数、无图片数据：只表达「现在有可见内容」这一事实。
     */
    @JavascriptInterface
    public void notifyOverlayPortraitVisible(String pageSessionId) {
        route().portraitVisible(pageSessionId);
    }

    /** 页面卸载/导航开始（pagehide）：注销会话，让在途结果立刻失效。 */
    @JavascriptInterface
    public void notifyOverlayPageHidden(String pageSessionId) {
        route().invalidatePageSession(pageSessionId);
    }

    /**
     * 去敏渲染诊断快照（JSON）。
     *
     * 只含稳定代码与计数，供排障读取；不返回 URL、token、聊天或图片内容。
     */
    @JavascriptInterface
    public String overlayRenderDiagnostics() {
        DiagnosticsProvider provider = diagnosticsProvider;
        return provider == null ? "{}" : provider.snapshot();
    }

    /**
     * 由宿主调用，把截图结果回传网页。
     * 网页需要先定义 window.SakuraNative.onScreenCapture(dataUrl, error)。
     */
    public void deliverScreenCapture(String dataUrl, String error) {
        host.deliverScreenshot(dataUrl, error);
    }
}
