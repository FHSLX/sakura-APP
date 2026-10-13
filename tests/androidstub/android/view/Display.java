package android.view;

import android.util.DisplayMetrics;

/** 最小 Display 替身：真实产品只在这里读屏幕尺寸。 */
public class Display {

    public int widthPixels = 1080;
    public int heightPixels = 2400;
    public int densityDpi = 420;

    public void getRealMetrics(DisplayMetrics outMetrics) {
        outMetrics.widthPixels = widthPixels;
        outMetrics.heightPixels = heightPixels;
        outMetrics.densityDpi = densityDpi;
    }
}
