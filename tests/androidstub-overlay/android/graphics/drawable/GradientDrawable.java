package android.graphics.drawable;

/** 最小 GradientDrawable 替身：记录形状/颜色/圆角/描边，供断言可见性。 */
public class GradientDrawable extends Drawable {

    public static final int RECTANGLE = 0;
    public static final int OVAL = 1;

    public int shape = RECTANGLE;
    public int color;
    public float cornerRadius;
    public int strokeWidth;
    public int strokeColor;
    public boolean hasColor;

    public void setShape(int shape) {
        this.shape = shape;
    }

    public void setColor(int color) {
        this.color = color;
        this.hasColor = true;
    }

    public void setCornerRadius(float radius) {
        this.cornerRadius = radius;
    }

    public void setStroke(int width, int color) {
        this.strokeWidth = width;
        this.strokeColor = color;
    }
}
