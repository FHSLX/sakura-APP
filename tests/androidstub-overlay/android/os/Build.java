package android.os;

/** 最小 Build 替身：默认按 API 33（支持精确穿透）暴露，可按测试需要调整。 */
public final class Build {

    public static final class VERSION {
        public static int SDK_INT = 33;
    }

    public static final class VERSION_CODES {
        public static final int O = 26;
        public static final int Q = 29;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
    }
}
