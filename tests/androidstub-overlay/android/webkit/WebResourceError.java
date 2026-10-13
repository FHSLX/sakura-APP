package android.webkit;

/** 最小 WebResourceError 替身：只暴露错误码（去敏诊断需要）。 */
public class WebResourceError {

    private final int errorCode;

    public WebResourceError(int errorCode) {
        this.errorCode = errorCode;
    }

    public int getErrorCode() {
        return errorCode;
    }

    public CharSequence getDescription() {
        return "";
    }
}
