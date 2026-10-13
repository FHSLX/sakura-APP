package android.webkit;

/** 最小 WebResourceResponse 替身：只暴露 HTTP 状态码（去敏诊断需要）。 */
public class WebResourceResponse {

    private final int statusCode;

    public WebResourceResponse(int statusCode) {
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
