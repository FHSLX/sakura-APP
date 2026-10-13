package android.graphics;

/** 最小 Outline 替身。 */
public class Outline {

    public int left;
    public int top;
    public int right;
    public int bottom;
    public float radius;

    public void setRoundRect(int left, int top, int right, int bottom, float radius) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
        this.radius = radius;
    }
}
