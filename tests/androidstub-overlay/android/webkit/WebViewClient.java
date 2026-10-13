package android.webkit;

import android.graphics.Bitmap;
import android.net.http.SslError;
import android.view.View;

/** 最小 WebViewClient 替身：真实 PetWindow 覆盖这些回调。 */
public class WebViewClient {

    public void onPageStarted(WebView view, String url, Bitmap favicon) {
    }

    public void onPageCommitVisible(WebView view, String url) {
    }

    public void onPageFinished(WebView view, String url) {
    }

    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
    }

    @SuppressWarnings("deprecation")
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
    }

    public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                    WebResourceResponse errorResponse) {
    }

    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
    }

    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return false;
    }
}
