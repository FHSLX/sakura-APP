package android.webkit;

/** 最小 SslErrorHandler 替身：记录 cancel/proceed 调用。 */
public class SslErrorHandler {

    public int cancelCalls;
    public int proceedCalls;

    public void cancel() {
        cancelCalls++;
    }

    public void proceed() {
        proceedCalls++;
    }
}
