package android.os;

/** 可控 SystemClock 替身（INT-07）：授权等待超时测试需要推进时间。 */
public final class SystemClock {

    private static volatile long elapsedRealtime = 1_000L;

    private SystemClock() {
    }

    public static long elapsedRealtime() {
        return elapsedRealtime;
    }

    public static void setElapsedRealtimeForTest(long value) {
        elapsedRealtime = value;
    }

    public static void advanceForTest(long deltaMillis) {
        elapsedRealtime += deltaMillis;
    }
}
