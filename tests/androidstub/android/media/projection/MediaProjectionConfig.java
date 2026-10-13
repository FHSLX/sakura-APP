package android.media.projection;

/**
 * 最小 MediaProjectionConfig 替身（API 34，测试专用）。
 *
 * 只提供产品代码用到的公开工厂方法，用来断言「Android 14+ 明确请求默认整屏」，
 * 不含任何系统行为。
 */
public final class MediaProjectionConfig {

    private final boolean defaultDisplay;

    private MediaProjectionConfig(boolean defaultDisplay) {
        this.defaultDisplay = defaultDisplay;
    }

    /** 请求默认显示器（整屏）。 */
    public static MediaProjectionConfig createConfigForDefaultDisplay() {
        return new MediaProjectionConfig(true);
    }

    /** 单应用共享（对照用）。 */
    public static MediaProjectionConfig createConfigForSingleApp() {
        return new MediaProjectionConfig(false);
    }

    public boolean isDefaultDisplayForTest() {
        return defaultDisplay;
    }
}
