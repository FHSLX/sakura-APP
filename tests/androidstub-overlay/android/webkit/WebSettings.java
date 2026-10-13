package android.webkit;

/** 最小 WebSettings 替身。 */
public class WebSettings {

    public static final int LOAD_DEFAULT = -1;
    public static final int LOAD_NO_CACHE = 2;

    private boolean javaScriptEnabled;
    private boolean domStorageEnabled;
    private boolean useWideViewPort = true;
    private boolean loadWithOverviewMode = true;
    private boolean supportZoom = true;
    private boolean builtInZoomControls = true;
    private int cacheMode = LOAD_DEFAULT;

    public void setJavaScriptEnabled(boolean enabled) {
        this.javaScriptEnabled = enabled;
    }

    public boolean getJavaScriptEnabled() {
        return javaScriptEnabled;
    }

    public void setDomStorageEnabled(boolean enabled) {
        this.domStorageEnabled = enabled;
    }

    public boolean getDomStorageEnabled() {
        return domStorageEnabled;
    }

    public void setUseWideViewPort(boolean enabled) {
        this.useWideViewPort = enabled;
    }

    public boolean getUseWideViewPort() {
        return useWideViewPort;
    }

    public void setLoadWithOverviewMode(boolean enabled) {
        this.loadWithOverviewMode = enabled;
    }

    public boolean getLoadWithOverviewMode() {
        return loadWithOverviewMode;
    }

    public void setSupportZoom(boolean enabled) {
        this.supportZoom = enabled;
    }

    public boolean supportZoom() {
        return supportZoom;
    }

    public void setBuiltInZoomControls(boolean enabled) {
        this.builtInZoomControls = enabled;
    }

    public boolean getBuiltInZoomControls() {
        return builtInZoomControls;
    }

    public void setCacheMode(int mode) {
        this.cacheMode = mode;
    }

    public int getCacheMode() {
        return cacheMode;
    }
}
