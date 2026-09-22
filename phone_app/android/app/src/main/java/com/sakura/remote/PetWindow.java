package com.sakura.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 悬浮窗的视图：只有一个填满窗口的 WebView。
 *
 * 为什么没有原生标题栏：
 * 原来顶部有一条「按住拖动 + 对话/缩短」的栏，它在透明窗口上会形成一条横贯的
 * 矩形，看起来就是个「框」。现在拖动改成由网页手势驱动
 * （长按立绘 → {@code SakuraNative.moveBy()}），原件整条去掉，
 * 视觉上只剩立绘和控件。
 *
 * 网页侧负责：气泡、输入栏、手势（长按拖动 / 长按 5 秒开设置）、点击穿透判定。
 */
public class PetWindow {

    private final Context context;
    private final FrameLayout root;
    private final WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 加载失败后自动重试的间隔。 */
    private static final long RETRY_MS = 5000L;
    /** 连续失败多少次后停止重试（避免一直打一个明显不可达的地址）。 */
    private static final int MAX_RETRIES = 60;

    private String lastUrl = "";
    private int retries = 0;
    private boolean loadFailed = false;

    public PetWindow(Context context) {
        this.context = context;
        this.root = new FrameLayout(context);
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

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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
     * 处理办法：失败就把 WebView 藏起来（窗口保持全透明，用户什么也看不到），
     * 之后每 5 秒重试一次；一旦加载成功再把它显示出来。
     */
    private final class OverlayClient extends WebViewClient {

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            // 注意：错误页也会触发 onPageFinished！
            //
            // 所以这里绝对不能无条件 setVisibility(VISIBLE) —— 那等于把刚藏起来的
            // 错误页又显示出来（第一版就是这么写的，实测方框照旧）。
            // 是否真的加载成功，只看本次加载有没有触发过 onReceivedError，
            // 标记由 load() 在每次加载前清零。
            if (!loadFailed) {
                view.setVisibility(View.VISIBLE);
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            // 只关心主文档失败；子资源失败（比如某张图 404）不该把整个窗口隐藏
            if (request != null && request.isForMainFrame()) {
                hideAndRetry(view);
            }
        }

        @SuppressWarnings("deprecation")
        @Override
        public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
            super.onReceivedError(view, errorCode, description, failingUrl);
            // Android 6 以下走这个旧回调
            hideAndRetry(view);
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            // 局域网是明文 HTTP，正常不会走到这里；真遇到了也不要弹系统错误页
            handler.cancel();
            hideAndRetry(view);
        }
    }

    private void hideAndRetry(final WebView view) {
        loadFailed = true;
        view.setVisibility(View.INVISIBLE);
        handler.removeCallbacksAndMessages(null);
        if (retries >= MAX_RETRIES || lastUrl.isEmpty()) {
            return;
        }
        retries++;
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (lastUrl.isEmpty()) {
                    return;
                }
                // 关键：重试前必须清掉失败标记。
                //
                // 这里直接调 view.loadUrl()，不经过 load() —— 标记不清的话，
                // 重试虽然成功加载了页面，但 onPageFinished 看到 loadFailed
                // 仍是 true，就永远不把 WebView 显示回来。
                // 实测：Sakura 重启后页面其实已加载好（DOM 里立绘、历史都在），
                // 画面却一直空白，就是这个原因。
                loadFailed = false;
                view.loadUrl(lastUrl);
            }
        }, RETRY_MS);
    }

    @SuppressLint("SetJavaScriptEnabled")
    public void addJavascriptBridge(RemoteBridge bridge) {
        webView.addJavascriptInterface(bridge, "SakuraNative");
    }

    public View view() {
        return root;
    }

    public WebView webView() {
        return webView;
    }

    public void load(String url) {
        if (url != null && !url.isEmpty()) {
            lastUrl = url;
            retries = 0;
            // 每次加载前清掉失败标记（onPageFinished 会读它决定要不要显示）
            loadFailed = false;
            webView.loadUrl(url);
        }
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
        // 先停掉重试，否则窗口都销毁了还在每 5 秒 loadUrl 一次
        handler.removeCallbacksAndMessages(null);
        lastUrl = "";
        try {
            root.removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
            // 视图可能已被系统回收
        }
    }
}
