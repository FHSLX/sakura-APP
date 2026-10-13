package android.view;

/** 最小 WindowManager 替身（PetWindow 只用 LayoutParams 常量）。 */
public class WindowManager {

    public static class LayoutParams {
        public static final int FLAG_NOT_FOCUSABLE = 0x00000008;
        public static final int FLAG_NOT_TOUCHABLE = 0x00000010;
        public static final int FLAG_LAYOUT_NO_LIMITS = 0x00000200;
        public static final int FLAG_HARDWARE_ACCELERATED = 0x01000000;

        public int width;
        public int height;
        public int x;
        public int y;
        public int flags;
        public float alpha = 1f;
        public int gravity;
    }
}
