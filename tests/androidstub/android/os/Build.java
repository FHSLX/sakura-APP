package android.os;

/** 最小 Build 替身：SDK_INT=34，覆盖产品用到的版本常量。 */
public final class Build {

    private Build() {
    }

    public static final class VERSION {
        /** API 34：让所有 {@code >= O/Q/S/TIRAMISU} 分支都按现代路径执行。 */
        public static final int SDK_INT = 34;

        private VERSION() {
        }
    }

    public static final class VERSION_CODES {
        public static final int M = 23;
        public static final int N = 24;
        public static final int O = 26;
        public static final int Q = 29;
        public static final int S = 31;
        public static final int TIRAMISU = 33;
        /** API 34：MediaProjectionConfig 整屏请求的版本门槛。 */
        public static final int UPSIDE_DOWN_CAKE = 34;

        private VERSION_CODES() {
        }
    }
}
