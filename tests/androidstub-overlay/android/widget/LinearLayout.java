package android.widget;

import android.content.Context;
import android.view.ViewGroup;

/** 最小 LinearLayout 替身。 */
public class LinearLayout extends ViewGroup {

    public static final int HORIZONTAL = 0;
    public static final int VERTICAL = 1;

    public int orientation = HORIZONTAL;
    public int gravity;

    public static class LayoutParams extends ViewGroup.MarginLayoutParams {
        public LayoutParams(int width, int height) {
            super(width, height);
        }
    }

    public LinearLayout(Context context) {
        super(context);
    }

    public void setOrientation(int orientation) {
        this.orientation = orientation;
    }

    public void setGravity(int gravity) {
        this.gravity = gravity;
    }
}
