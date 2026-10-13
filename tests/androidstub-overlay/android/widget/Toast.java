package android.widget;

import android.content.Context;

/** 最小 Toast 替身（测试专用）：只记录调用，不显示任何东西。 */
public class Toast {

    public static final int LENGTH_SHORT = 0;
    public static final int LENGTH_LONG = 1;

    private Toast() {
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        return new Toast();
    }

    public void show() {
    }
}
