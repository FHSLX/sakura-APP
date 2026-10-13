package android.util;

/**
 * 最小 Log 替身（悬浮窗可见性/运行时诊断测试专用）。
 *
 * 记录条数与最近一条消息，便于断言两件事：
 *   1. 限频后确实没有刷屏（lines 有上限）；
 *   2. 输出里不含任何敏感原文（只出现白名单稳定代码）。
 */
public final class Log {

    public static int lines;
    public static String lastTag = "";
    public static String lastMessage = "";

    private Log() {
    }

    public static int d(String tag, String message) {
        return record(tag, message);
    }

    public static int i(String tag, String message) {
        return record(tag, message);
    }

    public static int w(String tag, String message) {
        return record(tag, message);
    }

    public static int e(String tag, String message) {
        return record(tag, message);
    }

    public static void resetForTest() {
        lines = 0;
        lastTag = "";
        lastMessage = "";
    }

    private static int record(String tag, String message) {
        lines++;
        lastTag = tag;
        lastMessage = message;
        return 0;
    }
}
