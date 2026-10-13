package android.util;

/** 最小 Log 替身：吞掉日志，不让它影响行为。 */
public final class Log {

    private Log() {
    }

    public static int d(String tag, String message) {
        return 0;
    }

    public static int i(String tag, String message) {
        return 0;
    }

    public static int w(String tag, String message) {
        return 0;
    }

    public static int e(String tag, String message) {
        return 0;
    }
}
