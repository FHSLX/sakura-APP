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

        /** 远程页回报自己的地址，原生侧未配置时作为兜底。 */
        void rememberRemoteUrl(String url);

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
        void requestScreenshot();

        /** 只申请截屏授权（不截取），供设置页的「授权截屏权限」按钮使用。 */
        void requestScreenPermission();

        boolean hasScreenPermission();

        /** 把截图结果交给网页（由宿主回调）。 */
        void deliverScreenshot(String dataUrl, String error);

        /** 悬浮窗权限、运行状态、电池白名单，一次读全，供设置页渲染。 */
        String petStatus();
    }

    private final Host host;

    public RemoteBridge(Host host) {
        this.host = host;
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
     * 网页进入远程页后回报自己的地址，作为原生侧配置的兜底。
     *
     * 为什么需要：连接设置页跑在 https://localhost，远程页跑在
     * http://电脑IP:8770 —— 两者是不同源，localStorage 不互通。
     * 如果只依赖 localStorage，用户在远程页点「开启桌面立绘」时原生侧读不到地址，
     * 会误报「需要连接电脑」。
     */
    @JavascriptInterface
    public void rememberUrl(String url) {
        host.rememberRemoteUrl(url);
    }

    /** 返回 JSON 字符串；网页解析后决定按钮状态。 */
    @JavascriptInterface
    public String petStatus() {
        return host.petStatus();
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
     * 首次会弹系统授权框；结果通过 {@link #onScreenCapture(String, String)} 异步回传，
     * 调用方（网页）要先定义 `window.SakuraNative.onScreenCapture`。
     */
    @JavascriptInterface
    public void requestScreenshot() {
        host.requestScreenshot();
    }

    /** 只申请截屏授权，不截取。设置页的「授权截屏权限」按钮用。 */
    @JavascriptInterface
    public void requestScreenPermission() {
        host.requestScreenPermission();
    }

    @JavascriptInterface
    public boolean hasScreenPermission() {
        return host.hasScreenPermission();
    }

    /**
     * 由宿主调用，把截图结果回传网页。
     * 网页需要先定义 window.SakuraNative.onScreenCapture(dataUrl, error)。
     */
    public void deliverScreenCapture(String dataUrl, String error) {
        host.deliverScreenshot(dataUrl, error);
    }
}
