package android.content.res;

import android.util.DisplayMetrics;

/** 最小 Resources 替身。 */
public class Resources {

    private final DisplayMetrics metrics = new DisplayMetrics();

    public DisplayMetrics getDisplayMetrics() {
        return metrics;
    }
}
