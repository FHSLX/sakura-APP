package android.util;

/** 最小 DisplayMetrics 替身：固定 1080x2400 @420dpi。 */
public class DisplayMetrics {

    public static final int DENSITY_DEFAULT = 160;

    public int widthPixels = 1080;
    public int heightPixels = 2400;
    public int densityDpi = 420;
    public float density = 2.625f;
}
