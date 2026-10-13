package android.net.http;

/** 最小 SslError 替身：只暴露 primary error（去敏诊断需要）。 */
public class SslError {

    private final int primaryError;

    public SslError(int primaryError) {
        this.primaryError = primaryError;
    }

    public int getPrimaryError() {
        return primaryError;
    }
}
