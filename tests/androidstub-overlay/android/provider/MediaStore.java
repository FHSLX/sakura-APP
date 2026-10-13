package android.provider;

/** 最小 MediaStore 替身（测试专用）：只覆盖拍照 intent 的动作与输出 extra。 */
public class MediaStore {

    public static final String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE";
    public static final String ACTION_VIDEO_CAPTURE = "android.media.action.VIDEO_CAPTURE";
    public static final String EXTRA_OUTPUT = "output";
}
