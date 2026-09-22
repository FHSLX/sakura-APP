package com.sakura.remote;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
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
        webView.setWebViewClient(new WebViewClient());

        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
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
        try {
            root.removeView(webView);
            webView.destroy();
        } catch (Exception ignored) {
            // 视图可能已被系统回收
        }
    }
}
