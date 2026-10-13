package android.view;

/** 最小 ViewOutlineProvider 替身。 */
public abstract class ViewOutlineProvider {

    public abstract void getOutline(View view, android.graphics.Outline outline);
}
