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

public class MainActivity extends BridgeActivity implements RemoteBridge.Host {

    /** 悬浮窗权限申请的请求码。 */
    private static final int REQUEST_OVERLAY = 0x5341;
    /** 电池优化白名单的请求码。 */
    private static final int REQUEST_BATTERY = 0x5342;
    /** 通知权限（Android 13+ 前台服务需要）。 */
    private static final int REQUEST_NOTIFICATIONS = 0x5343;
    /** 屏幕截图（MediaProjection）授权。 */
    private static final int REQUEST_SCREEN_CAPTURE = 0x5344;

    /**
     * 从桌面立绘点进来：直接进聊天页，并把自己收起，让立绘继续显示。
     * 由 OverlayService 在用户点击立绘时传入。
     */
    public static final String EXTRA_OPEN_CHAT = "open_chat";

    /** 悬浮窗请求截图授权时传进来。 */
    public static final String EXTRA_REQUEST_SCREENSHOT = "request_screenshot";

    private RemoteBridge remoteBridge;
    /** 用户点了「开启桌面立绘」但还没授权时记住意图，授权回来后自动继续。 */
    private boolean pendingOverlayStart;
    /** 用户点了「进入立绘模式」但还没授权；授权回来后要继续「开启并收起」。 */
    private boolean pendingEnterOverlayMode;
    /** 正在等屏幕截图授权。 */
    private boolean pendingScreenshot;
    /** 本次启动是否要进聊天页（从立绘点进来）。 */
    private boolean pendingOpenChat;
    /** 已经处理过跳转，避免 onResume 反复触发。 */
    private boolean chatHandled;

    /**
     * 网页主动回报的当前地址。
     *
     * 桥接方法运行在 JavaBridge 线程，不能在里面调 WebView.getUrl()，
     * 所以由网页在 boot() 时推过来，这里只做缓存。
     */
    private volatile String lastReportedUrl = "";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = getBridge() != null ? getBridge().getWebView() : null;
        if (webView != null) {
            remoteBridge = new RemoteBridge(this);
            // 名字和 www/index.html 里的 window.SakuraNative 对应
            webView.addJavascriptInterface(remoteBridge, "SakuraNative");
        }
        pendingOpenChat = readOpenChatExtra(getIntent());
        // 悬浮窗请求截图授权：进 Activity 后立刻走申请流程
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_REQUEST_SCREENSHOT, false)) {
            getIntent().removeExtra(EXTRA_REQUEST_SCREENSHOT);
            requestScreenshot();
        }
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
        // 显式带上页面地址：悬浮窗的 WebView 是长驻的，不重新加载就会一直跑旧版页面
        String chat = PetConfig.chatUrl(this);
        if (!chat.isEmpty()) {
            service.putExtra(OverlayService.EXTRA_URL, chat);
        }
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
        PetConfig.rememberFromUrl(this, lastReportedUrl);
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
        PetConfig.saveServer(this, server, token);
        notifyOverlay(ConfigReceiver.ACTION_REFRESH_OVERLAY);
    }

    @Override
    public void rememberRemoteUrl(String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        lastReportedUrl = url;
        PetConfig.rememberFromUrl(this, url);
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

    /** App 是整屏窗口，没有可拖动的悬浮位置。 */
    @Override
    public void moveWindowBy(float dx, float dy) {
        // no-op：仅悬浮窗支持
    }

    /** App 是整屏窗口，尺寸固定。 */
    @Override
    public void setWindowSizePx(float width, float height) {
        // no-op：仅悬浮窗支持
    }

    // ---- 手机截屏 --------------------------------------------------

    /**
     * 请求一次手机截屏，结果通过 JS 回调 `window.SakuraNative.onScreenCapture` 回传。
     *
     * 首次会弹系统的「开始录制或投放」授权框；授权后同一次调用会直接出图。
     */
    @Override
    public void requestScreenshot() {
        android.media.projection.MediaProjectionManager manager =
                (android.media.projection.MediaProjectionManager)
                        getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            deliverScreenshot("", "系统不支持屏幕截图");
            return;
        }
        pendingScreenshot = true;
        try {
            startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_SCREEN_CAPTURE);
        } catch (Exception error) {
            pendingScreenshot = false;
            deliverScreenshot("", "打不开截图授权页");
        }
    }

    @Override
    public boolean hasScreenPermission() {
        return ScreenCapturer.hasPermission(this);
    }

    /** 补一次截图（授权已有时用）。 */
    public void captureScreenshotNow() {
        startCaptureWithStoredGrant();
    }

    /**
     * 用已有授权再截一次。
     *
     * 关键：**不能**从这里直接启动 CaptureService。
     * 悬浮窗状态下 MainActivity 已被 moveTaskToBack 收到后台，而 Android 12+
     * 禁止后台应用启动前台服务（ForegroundServiceStartNotAllowedException）。
     * OverlayService 自己就是前台服务，由它启动才合法。
     */
    private void startCaptureWithStoredGrant() {
        if (OverlayService.isRunning()) {
            OverlayService.requestCaptureFromActivity(this);
            return;
        }
        ScreenCapturer.capture(this, this::deliverScreenshot);
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
        return "{\"canDraw\":" + canDrawOverlays()
                + ",\"running\":" + OverlayService.isRunning()
                + ",\"battery\":" + isIgnoringBatteryOptimizations()
                + ",\"wanted\":" + PetConfig.overlayEnabled(this)
                + ",\"scale\":" + PetConfig.scale(this) + "}";
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(service);
        } else {
            startService(service);
        }
        toast(getString(R.string.toast_overlay_started));
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
        if (requestCode == REQUEST_SCREEN_CAPTURE) {
            if (!pendingScreenshot) {
                return;
            }
            pendingScreenshot = false;
            if (resultCode == RESULT_OK && data != null) {
                ScreenCapturer.captureWithGrant(this, data, this::deliverScreenshot);
            } else {
                deliverScreenshot("", "你取消了截图授权");
            }
            // 悬浮窗模式下截屏授权是「借」MainActivity 走个流程，用完必须让位。
            //
            // 不收回后台的话：MainActivity 会一直停在前台盖住悬浮窗 ——
            // 用户看到的是 App 界面而不是桌宠，而且和悬浮窗叠成重影。
            // 授权结果已经交给 CaptureService，可以安全退出。
            if (OverlayService.isRunning()) {
                final WebView view = getBridge() != null ? getBridge().getWebView() : null;
                if (view != null) {
                    // 等回调把结果交给网页后再退，退太早可能丢掉这一拍
                    view.postDelayed(() -> moveTaskToBack(true), 600);
                } else {
                    moveTaskToBack(true);
                }
            }
            return;
        }
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