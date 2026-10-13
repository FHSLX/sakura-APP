package android.webkit;

/** 最小 WebResourceRequest 替身：只暴露 isForMainFrame（去敏诊断需要）。 */
public class WebResourceRequest {

    private final boolean mainFrame;

    public WebResourceRequest(boolean mainFrame) {
        this.mainFrame = mainFrame;
    }

    public boolean isForMainFrame() {
        return mainFrame;
    }
}
