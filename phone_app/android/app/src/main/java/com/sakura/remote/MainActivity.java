package com.sakura.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.widget.Toast;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity
        implements RemoteBridge.Host, CaptureCoordinator.Target {

    /** 悬浮窗权限申请的请求码。 */
    private static final int REQUEST_OVERLAY = 0x5341;
    /** 电池优化白名单的请求码。 */
    private static final int REQUEST_BATTERY = 0x5342;
    /** 通知权限（Android 13+ 前台服务需要）。 */
    private static final int REQUEST_NOTIFICATIONS = 0x5343;

    /**
     * 从桌面立绘点进来：直接进聊天页，并把自己收起，让立绘继续显示。
     * 由 OverlayService 在用户点击立绘时传入。
     */
    public static final String EXTRA_OPEN_CHAT = "open_chat";

    private RemoteBridge remoteBridge;
    /** 用户点了「开启桌面立绘」但还没授权时记住意图，授权回来后自动继续。 */
    private boolean pendingOverlayStart;
    /** 用户点了「进入立绘模式」但还没授权；授权回来后要继续「开启并收起」。 */
    private boolean pendingEnterOverlayMode;
    /** 本次启动是否要进聊天页（从立绘点进来）。 */
    private boolean pendingOpenChat;
    /** 已经处理过跳转，避免 onResume 反复触发。 */
    private boolean chatHandled;

    /** 本 Activity 在页面会话登记表里的宿主 id。 */
    private static final String PAGE_ID_APP = "app";

    /**
     * 本 Activity 的文件选择/拍照在 {@link FileChooserBridge} 里的 owner。
     *
     * <p>每个实例独立：旧实例迟到的 onDestroy 只取消自己的请求，
     * 不会误伤新实例刚登记的请求。
     */
    private String chooserOwner = "";

    /**
     * 本 Activity 最近一次发起的截图请求 id / 页面会话。
     *
     * 结果回来时两者都要匹配才允许送到网页：reload 之后旧会话（即使是同一个 URL）
     * 会因为新页登记了新会话而失效，迟到结果被丢弃，不会出现在新页面上。
     */
    private volatile String captureRequestId = "";
    private volatile String capturePageSession = "";

    /**
     * 网页主动回报的当前地址。
     *
     * 桥接方法运行在 JavaBridge 线程，不能在里面调 WebView.getUrl()，
     * 所以由网页在 boot() 时推过来，这里只做缓存。
     */
    private volatile String lastReportedUrl = "";

    /**
     * 连接配置唯一接线点。
     *
     * 所有地址变更（连接页选择 / 远程页成功上报）都经过它规范化写入，
     * 并且只在有效配置真的变化时请求运行中的立绘重载。
     */
    private ConnectionSync connectionSync;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        connectionSync = new ConnectionSync(this, new OverlayReloadPort());
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView != null) {
            remoteBridge = new RemoteBridge(this);
            // 名字和 www/index.html 里的 window.SakuraNative 对应
            webView.addJavascriptInterface(remoteBridge, "SakuraNative");
            /*
             * App 内的文件选择/拍照也必须走**有预算、精确一次**的同一条通道。
             *
             * Capacitor 自带的 BridgeWebChromeClient 直接 resolveActivity 后开相机，
             * 既没有 6MiB 预算/EXIF 处理，Android 11+ 还可能因缺少 queries 查询不到
             * 相机而回退图库。这里安装一个只覆写 onShowFileChooser 的子类：
             * 其余桥/权限/对话框逻辑全部沿用父类，不覆盖丢弃已有桥。
             */
            chooserOwner = "app-" + Integer.toHexString(System.identityHashCode(this));
            try {
                webView.setWebChromeClient(new AppFileChooserClient(getBridge(), chooserOwner));
            } catch (Throwable ignored) {
                // 装不上就继续用父类默认实现，不影响其余功能
            }
        }
        pendingOpenChat = readOpenChatExtra(getIntent());
        // App 一启动就把桌面立绘带起来，不用先手动去点开关
        maybeAutoStartOverlay();
    }

    private boolean readOpenChatExtra(Intent intent) {
        return intent != null && intent.getBooleanExtra(EXTRA_OPEN_CHAT, false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (readOpenChatExtra(intent)) {
            pendingOpenChat = true;
            chatHandled = false;
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (pendingOpenChat && !chatHandled) {
            chatHandled = true;
            openChatPage();
        }
    }

    // ---- 桌面立绘模式 ---------------------------------------------

    /**
     * 进入「桌面立绘模式」：收起 App 自己，让悬浮窗成为唯一可见的东西。
     *
     * 这是「放在手机桌面」的关键一步 —— 否则 App 一直在前台，
     * 悬浮窗被自己的窗口盖住，看起来就像没显示。
     */
    @Override
    public void enterOverlayMode() {
        // 未配置就没法拉立绘，先把用户挡回连接页
        if (!PetConfig.isConfigured(this)) {
            toast(getString(R.string.toast_need_config));
            return;
        }
        PetConfig.setOverlayEnabled(this, true);
        // 第一次用或权限被撤销时，先申请权限；授权回来后 onActivityResult 继续
        if (!canDrawOverlays()) {
            pendingEnterOverlayMode = true;
            requestOverlayPermission();
            return;
        }
        startOverlayAndMinimize();
    }

    /** 开启悬浮窗并把 App 收到后台，让角色留在桌面上。 */
    private void startOverlayAndMinimize() {
        // Android 13+ 前台服务要显示通知，没有通知权限会影响保活，顺手申请一次
        requestNotificationPermissionIfNeeded();
        Intent service = new Intent(this, OverlayService.class);
        // 显式带上当前有效配置（完整 origin + token）：悬浮窗的 WebView 是长驻的，
        // 不带地址就会一直跑旧版页面/旧主机，表现为「聊天页能连、立绘连不上」。
        putCurrentChatUrl(service);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception error) {
            toast(getString(R.string.toast_overlay_start_failed));
            return;
        }
        toast(getString(R.string.toast_overlay_mode));
        // 关键：收起自己，否则悬浮窗被 App 的前台窗口盖住，看起来像没显示
        moveTaskToBack(true);
    }

    /**
     * Android 13+ 起前台服务必须能发通知，否则保活会打折。
     * 只在没授权时申请一次，用户拒绝也不阻塞主流程。
     */
    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
        } catch (Exception ignored) {
            // 申请失败不影响悬浮窗本身
        }
    }

    /** 从桌面立绘点进来时调用：加载远程页并收起 App。 */
    private void openChatPage() {
        String url = PetConfig.chatUrl(this);
        if (url.isEmpty()) {
            // 没配置过就留在连接设置页
            return;
        }
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView == null) {
            return;
        }
        webView.loadUrl(url);
        // 稍等 WebView 起来再收起，避免白屏一眼
        webView.postDelayed(() -> moveTaskToBack(true), 1200);
    }

    /** 启动时若「应常驻」且条件具备，就自动把悬浮窗带起来。 */
    private void maybeAutoStartOverlay() {
        if (!PetConfig.overlayEnabled(this)) {
            return;
        }
        if (!PetConfig.isConfigured(this)) {
            return;
        }
        if (!canDrawOverlays()) {
            // 权限被撤销过，别自动弹设置页打扰用户，等他自己点开关
            return;
        }
        if (OverlayService.isRunning()) {
            // 悬浮窗已经在跑，说明用户是从桌宠点回来的，不要重复启动
            return;
        }
        Intent service = new Intent(this, OverlayService.class);
        // 全部启动路径都带入当前有效配置，不允许出现「裸启动」读旧配置
        putCurrentChatUrl(service);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Exception ignored) {
            // 启动失败就让用户手动点开关
            return;
        }
        // 关键：必须把自己收到后台。
        //
        // 漏了这一步会出现「App 和悬浮窗同时显示、画面叠在一起」——
        // 实测截图里能看到两套对话框和两个输入栏。
        // 悬浮窗是独立窗口，盖在 App 的 Activity 之上，两者都可见就是重影。
        // 延后一点再收，否则前台服务刚起来时被切后台可能影响它启动。
        final WebView view = getBridge() != null ? getBridge().getWebView() : null;
        if (view != null) {
            view.postDelayed(() -> moveTaskToBack(true), 800);
        } else {
            moveTaskToBack(true);
        }
    }

    // ---- RemoteBridge.Host ----------------------------------------

    @Override
    public void loadRemote(final String url) {
        runOnUiThread(() -> {
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView != null) {
                webView.loadUrl(url);
            }
        });
    }

    @Override
    public void setPetScale(float scale) {
        PetConfig.setScale(this, scale);
        notifyOverlay(ConfigReceiver.ACTION_RESCALE_OVERLAY);
    }

    @Override
    public float getPetScale() {
        return PetConfig.scale(this);
    }

    @Override
    public boolean isOverlayRunning() {
        return OverlayService.isRunning();
    }

    @Override
    public boolean startOverlay() {
        // 先兜底同步一次地址：连接设置页和远程页是不同源，
        // 只靠 localStorage 会在远程页误报「未配置」。
        //
        // 注意：这里不能调 WebView.getUrl()。
        // addJavascriptInterface 的方法运行在 JavaBridge 线程，
        // 而 WebView 的所有方法必须在主线程调用，否则会抛
        // "A WebView method was called on thread 'JavaBridge'"。
        connectionSync.report(lastReportedUrl, false);
        if (!PetConfig.isConfigured(this)) {
            toast(getString(R.string.toast_need_config));
            return false;
        }
        PetConfig.setOverlayEnabled(this, true);
        if (!canDrawOverlays()) {
            pendingOverlayStart = true;
            requestOverlayPermission();
            return false;
        }
        launchOverlay();
        return true;
    }

    @Override
    public void stopOverlay() {
        pendingOverlayStart = false;
        PetConfig.setOverlayEnabled(this, false);
        stopService(new Intent(this, OverlayService.class));
    }

    @Override
    public void requestBatteryExemption() {
        if (isIgnoringBatteryOptimizations()) {
            toast(getString(R.string.toast_battery_already));
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_BATTERY);
        } catch (Exception error) {
            // 部分 ROM 没有这个页面，退回电池优化列表
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast(getString(R.string.toast_battery_failed));
            }
        }
    }

    @Override
    public boolean isIgnoringBatteryOptimizations() {
        PowerManager manager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (manager == null) {
            return false;
        }
        return manager.isIgnoringBatteryOptimizations(getPackageName());
    }

    @Override
    public void saveServer(String server, String token) {
        // 用户在连接设置页明确选择目标：完整 origin + token 原子同步进原生配置。
        // 配置真的变了并且立绘在运行，才会让它重载到新地址。
        connectionSync.select(server, token);
    }

    @Override
    public void rememberRemoteUrl(String url, boolean overlayPage) {
        if (url == null || url.isEmpty()) {
            return;
        }
        if (!overlayPage) {
            // 只有主 App 远程页的地址代表「用户当前连的是哪儿」；
            // 立绘页面的旧地址不能覆盖它
            lastReportedUrl = url;
        }
        connectionSync.report(url, overlayPage);
    }

    /** App 自己的窗口本来就是可聚焦的，这里无需处理。 */
    @Override
    public void setKeyboardOpen(boolean open) {
        // no-op：只有悬浮窗需要动态切换 FLAG_NOT_FOCUSABLE
    }

    /** App 里没有悬浮窗，缩成小图标这个动作不适用。 */
    @Override
    public void setBubbleMode(boolean bubble) {
        // no-op：仅悬浮窗支持
    }

    /** App 自己的窗口正常接收触摸，不需要穿透。 */
    @Override
    public void setTouchable(boolean touchable) {
        // no-op：仅悬浮窗支持
    }

    /** App 是整屏窗口，不需要跳过夹取。 */
    @Override
    public void setDragging(boolean dragging) {
        // no-op：仅悬浮窗需要
    }

    /** App 是整屏窗口，没有可拖动的悬浮位置。 */
    @Override
    public void moveWindowBy(float dx, float dy) {
        // no-op：仅悬浮窗支持
    }

    /** App 是整屏窗口，没有可拖动的悬浮位置。 */
    @Override
    public void setWindowPositionPx(float x, float y) {
        // no-op：仅悬浮窗支持
    }

    /**
     * App 页也能被当作普通网页打开，此时拿不到悬浮窗布局信息。
     *
     * 返回空对象而不是 null —— 网页侧会读它的字段，null 会直接抛异常。
     */
    @Override
    public String overlayLayoutInfo() {
        return "{}";
    }

    /** App 是整屏窗口，尺寸固定。 */
    @Override
    public void setWindowSizePx(float width, float height) {
        // no-op：仅悬浮窗支持
    }

    // ---- 精确局部穿透：App 内只报告能力，不裁剪区域 -------------------
    //
    // App 的 Activity 是整屏前台的普通窗口，不存在「透到桌面」的语义，
    // 因此绝不提交区域裁剪（setTouchRegion 明确返回 unsupported）。
    // 但**能力报告**要如实：网页据此决定「透明处穿透」开关是否可用，
    // 用户意图再由 setDisplayPreferences 持久化并同步运行中的悬浮窗。

    @Override
    public String setTouchRegion(String json) {
        return "unsupported";
    }

    @Override
    public void clearTouchRegion() {
        // no-op：区域裁剪只在悬浮窗生效
    }

    /** 本机是否具备公开的精确穿透 API（Android 13 / API 33+）。 */
    @Override
    public boolean isTouchRegionSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    @Override
    public void setPassThroughEnabled(boolean enabled) {
        // no-op：App 窗口本身不接收精确区域；意图走 setDisplayPreferences
    }

    @Override
    public String touchRegionStatus() {
        return "{\"supported\":" + isTouchRegionSupported()
                + ",\"enabled\":false,\"host\":\"app\"}";
    }

    // ---- 显示 / 穿透偏好（App ↔ 运行中悬浮窗）----------------------

    @Override
    public String displayPreferences() {
        return PetConfig.displayPreferences(this, isTouchRegionSupported());
    }

    /**
     * 持久化用户意图，并经**同进程安全通道**同步运行中的悬浮窗。
     *
     * <p>不广播、不启动服务、不提交区域：悬浮窗自己按新偏好重算轮廓。
     * 返回的是落盘后的回读值，网页可校验，不做「只改文字的假保存」。
     */
    @Override
    public String setDisplayPreferences(String json) {
        String result = PetConfig.applyDisplayPreferences(this, json);
        if (result.indexOf("\"ok\":true") >= 0) {
            OverlayService.applyDisplayPreferencesFromHost(json);
        }
        return result;
    }

    // ---- 渲染可用性信号（FIX-BLANK-01 / FIX-RUNTIME-01）-------------
    // App 内页面不是悬浮窗，没有「透明窗口拦点击」的问题，也不参与窗口挂载裁决。
    // 渲染信号只由 OverlayService 的主线程落点（RenderSignalSink）处理，
    // 因此这里不再需要 onRender* 直调入口。

    @Override
    public String renderDiagnostics() {
        return "{\"host\":\"app\",\"enabled\":false}";
    }

    // ---- 手机截屏 --------------------------------------------------

    /**
     * 只申请截屏授权，不附带截取动作。
     *
     * 系统每次截图都会重新弹「开始录制或投放」确认框；这个按钮只是让用户
     * 先熟悉授权流程，不再假装「授过一次以后就免弹」。
     */
    @Override
    public void requestScreenPermission(String pageSessionId) {
        requestScreenshot(pageSessionId);
    }

    @Override
    public boolean hasScreenPermission() {
        return ScreenCapturer.hasPermission(this);
    }

    /** 补一次截图（从设置页调用）。 */
    public void captureScreenshotNow() {
        requestScreenshot(capturePageSession);
    }

    /**
     * 发起一次截图。
     *
     * 浮窗发起时不能走这里：悬浮窗状态下 MainActivity 可能已被 moveTaskToBack
     * 收到后台，Android 12+ 禁止后台应用启动前台服务。悬浮窗入口有自己的
     * requestScreenshot（由 OverlayService 启动 CaptureService）。
     */
    @Override
    public void requestScreenshot(String pageSessionId) {
        CaptureCoordinator coordinator = CaptureCoordinator.get();
        if (coordinator.isBusy()) {
            deliverScreenshot("", "已有截图任务正在进行，请稍候");
            return;
        }
        String session = pageSessionId == null ? "" : pageSessionId;
        // 会话必须已由页面登记且仍有效，否则是旧页面的迟到请求
        if (!session.isEmpty() && !coordinator.sessions().isCurrent(PAGE_ID_APP, session)) {
            deliverScreenshot("", "页面已刷新，请重试");
            return;
        }
        capturePageSession = session;
        CaptureRequestRegistry.Request request =
                coordinator.begin(CaptureCoordinator.Origin.APP, capturePageSession, this);
        if (request == null) {
            deliverScreenshot("", "已有截图任务正在进行，请稍候");
            return;
        }
        captureRequestId = request.requestId;
        Intent intent = new Intent(this, CapturePermissionActivity.class);
        intent.putExtra(CapturePermissionActivity.EXTRA_REQUEST_ID, request.requestId);
        try {
            startActivity(intent);
        } catch (Exception error) {
            coordinator.cancel(request.requestId, "打不开截图授权页");
            deliverScreenshot("", "打不开截图授权页");
        }
    }

    /**
     * 截图结果回调（CaptureCoordinator.Target）。
     *
     * 在**主线程投递的瞬间**复核 requestId 与页面会话：同 URL reload 后旧会话
     * 已失效，结果作废；旧回调不会污染新任务，也不会交给别的 WebView。
     */
    @Override
    public void onCaptureResult(String requestId, String dataUrl, String error) {
        if (requestId == null || !requestId.equals(captureRequestId)) {
            return;
        }
        runOnUiThread(() -> {
            if (!requestId.equals(captureRequestId)) {
                return;
            }
            boolean sessionOk = capturePageSession == null || capturePageSession.isEmpty()
                    || CaptureCoordinator.get().sessions()
                            .isCurrent(PAGE_ID_APP, capturePageSession);
            captureRequestId = "";
            capturePageSession = "";
            if (!sessionOk) {
                return;   // 页面已刷新：结果作废（不重复回调、不交给新页面）
            }
            deliverScreenshot(dataUrl, error);
        });
    }

    @Override
    public void onDestroy() {
        // App 页面结束：登记会话失效，在途请求不再投递到已消失的页面
        CaptureCoordinator.get().sessions().invalidate(PAGE_ID_APP);
        // 只取消本 Activity 自己发起的文件选择/拍照，不误伤悬浮窗或新实例的请求
        if (chooserOwner != null && !chooserOwner.isEmpty()) {
            FileChooserBridge.cancelAll(chooserOwner);
        }
        super.onDestroy();
    }

    // ---- 页面会话登记（RV-02）-------------------------------------

    @Override
    public void registerPageSession(String pageSessionId) {
        CaptureCoordinator.get().registerPageSession(PAGE_ID_APP, pageSessionId);
    }

    @Override
    public void invalidatePageSession(String pageSessionId) {
        // 注销登记，并终结属于该会话的在途截图请求（含停服务）
        CaptureCoordinator.get().invalidatePageSession(PAGE_ID_APP, pageSessionId);
    }

    /** 把截图结果交给网页。dataUrl 为空表示失败。 */
    @Override
    public void deliverScreenshot(String dataUrl, String error) {
        final String payload = dataUrl == null ? "" : dataUrl;
        final String reason = error == null ? "" : error;
        runOnUiThread(() -> {
            WebView webView = getBridge() != null ? getBridge().getWebView() : null;
            if (webView == null) {
                return;
            }
            String script = "if (window.SakuraNative && window.SakuraNative.onScreenCapture) {"
                    + "window.SakuraNative.onScreenCapture("
                    + org.json.JSONObject.quote(payload) + ","
                    + org.json.JSONObject.quote(reason) + ");}";
            webView.evaluateJavascript(script, null);
        });
    }

    /** 悬浮窗权限、运行状态、电池白名单，一次读全，供设置页渲染。 */
    @Override
    public String petStatus() {
        ConnectionSync sync = connectionSync;
        return "{\"canDraw\":" + canDrawOverlays()
                + ",\"running\":" + OverlayService.isRunning()
                + ",\"battery\":" + isIgnoringBatteryOptimizations()
                + ",\"wanted\":" + PetConfig.overlayEnabled(this)
                + ",\"scale\":" + PetConfig.scale(this)
                + ",\"connection\":" + (sync == null ? "{}" : sync.diagnostics()) + "}";
    }

    // ---- 权限与生命周期 -------------------------------------------

    private boolean canDrawOverlays() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        toast(getString(R.string.toast_need_overlay_permission));
        try {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, REQUEST_OVERLAY);
        } catch (Exception error) {
            toast(getString(R.string.toast_overlay_permission_failed));
        }
    }

    private void launchOverlay() {
        Intent service = new Intent(this, OverlayService.class);
        // 与 enterOverlayMode / 自动拉起同一条规则：新启动必定带当前有效配置
        putCurrentChatUrl(service);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        toast(getString(R.string.toast_overlay_started));
    }

    /** 把当前有效配置（完整 origin + token 的聊天页地址）带进立绘启动 Intent。 */
    private void putCurrentChatUrl(Intent service) {
        // 唯一来源：ConnectionSync.overlayUrl（规范化 origin + token）
        String chat = connectionSync == null ? PetConfig.chatUrl(this) : connectionSync.overlayUrl();
        if (!chat.isEmpty()) {
            service.putExtra(OverlayService.EXTRA_URL, chat);
        }
    }

    /**
     * 立绘侧效果出口。
     *
     * <p>只有配置确实变化、并且立绘正在运行时才会走到 reload；通过已有广播通道
     * 让服务读新配置重载页面，避免在 Activity 侧直接操作 WebView。
     */
    private final class OverlayReloadPort implements ConnectionSync.OverlayPort {
        @Override
        public boolean isRunning() {
            return OverlayService.isRunning();
        }

        @Override
        public void reload(String chatUrl) {
            Intent intent = new Intent(MainActivity.this, ConfigReceiver.class)
                    .setAction(ConfigReceiver.ACTION_REFRESH_OVERLAY)
                    .putExtra(OverlayService.EXTRA_URL, chatUrl);
            sendBroadcast(intent);
        }
    }

    private void notifyOverlay(String action) {
        if (!OverlayService.isRunning()) {
            return;
        }
        Intent intent = new Intent(this, ConfigReceiver.class).setAction(action);
        sendBroadcast(intent);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 截图授权已经交给独立的 CapturePermissionActivity，这里只处理悬浮窗权限
        if (requestCode != REQUEST_OVERLAY) {
            return;
        }
        boolean granted = canDrawOverlays();
        if (granted) {
            if (pendingEnterOverlayMode) {
                pendingEnterOverlayMode = false;
                pendingOverlayStart = false;
                startOverlayAndMinimize();
                return;
            }
            if (pendingOverlayStart) {
                pendingOverlayStart = false;
                launchOverlay();
            }
            return;
        }
        pendingOverlayStart = false;
        pendingEnterOverlayMode = false;
        toast(getString(R.string.toast_overlay_permission_denied));
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }
}