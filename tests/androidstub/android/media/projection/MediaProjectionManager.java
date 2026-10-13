package android.media.projection;

import android.content.Intent;

/** 最小 MediaProjectionManager 替身：可控返回的 MediaProjection + 整屏配置记录。 */
public final class MediaProjectionManager {

    private MediaProjection projection = new MediaProjection();

    /** 最近一次 createScreenCaptureIntent 是否带了 MediaProjectionConfig。 */
    public MediaProjectionConfig lastConfig;
    public int captureIntentCalls;

    public void setProjectionForTest(MediaProjection projection) {
        this.projection = projection;
    }

    public MediaProjection projectionForTest() {
        return projection;
    }

    public Intent createScreenCaptureIntent() {
        captureIntentCalls++;
        lastConfig = null;
        return new Intent();
    }

    public Intent createScreenCaptureIntent(MediaProjectionConfig config) {
        captureIntentCalls++;
        lastConfig = config;
        return new Intent();
    }

    public MediaProjection getMediaProjection(int resultCode, Intent resultData) {
        return projection;
    }
}
