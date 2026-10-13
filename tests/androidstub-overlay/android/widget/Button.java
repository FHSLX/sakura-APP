package android.widget;

import android.content.Context;

/** 最小 Button 替身。 */
public class Button extends TextView {

    public boolean allCaps;

    public Button(Context context) {
        super(context);
    }

    public void setAllCaps(boolean allCaps) {
        this.allCaps = allCaps;
    }
}
