package android.view;

/**
 * 最小 WindowManager 替身（INT-07）。
 *
 * CaptureService 只通过它取默认 Display 的真实尺寸；替身提供可控 Display。
 */
public class WindowManager {

    public static class LayoutParams {
        public int width;
        public int height;
        public int x;
        public int y;
        public int flags;
        public float alpha = 1f;
        public int gravity;
    }

    private final Display display = new Display();

    public Display getDefaultDisplay() {
        return display;
    }

    public Display displayForTest() {
        return display;
    }

    public void addView(View view, LayoutParams params) {
    }

    public void removeView(View view) {
    }

    public void updateViewLayout(View view, LayoutParams params) {
    }
}
