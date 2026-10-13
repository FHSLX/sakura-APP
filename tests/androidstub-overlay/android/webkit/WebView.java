package android.webkit;

import android.graphics.Bitmap;

/**
 * 最小 WebView 替身（悬浮窗可见性测试专用）。
 *
 * 记录 loadUrl 次数与最近 URL，并保存 WebViewClient，供测试确定性地驱动
 * onPageStarted/onReceivedError/onReceivedHttpError/onReceivedSslError/onConsoleMessage。
 * 不含任何被测生产控制流：所有失败/恢复决策都在真实 PetWindow 里执行。
 */
public class WebView extends android.view.View {

    private WebViewClient client;
    private WebChromeClient chromeClient;
    public int loadUrlCalls;
    public String lastLoadedUrl = "";
    public String javascriptInterfaceName = "";
    public Object javascriptInterface;
    public int clearCacheCalls;
    public int destroyCalls;
    public int evaluateJavascriptCalls;

    public WebView(android.content.Context context) {
        super(context);
    }

    public void setWebViewClient(WebViewClient client) {
        this.client = client;
    }

    public WebViewClient clientForTest() {
        return client;
    }

    public void setWebChromeClient(WebChromeClient chromeClient) {
        this.chromeClient = chromeClient;
    }

    public WebChromeClient chromeClientForTest() {
        return chromeClient;
    }

    /** 覆盖 View 的替身：返回测试可控的 WebSettings。 */
    @Override
    public WebSettings getSettings() {
        return settings;
    }

    public void loadUrl(String url) {
        loadUrlCalls++;
        lastLoadedUrl = url == null ? "" : url;
    }

    public void addJavascriptInterface(Object object, String name) {
        javascriptInterface = object;
        javascriptInterfaceName = name;
    }

    public void clearCache(boolean includeDiskFiles) {
        clearCacheCalls++;
    }

    public void destroy() {
        destroyCalls++;
    }

    public void evaluateJavascript(String script, ValueCallback<String> callback) {
        evaluateJavascriptCalls++;
        // 本测试不执行页面脚本；真实 OverlayService 会调用它推送状态
    }

    public interface ValueCallback<T> {
        void onReceiveValue(T value);
    }
}
