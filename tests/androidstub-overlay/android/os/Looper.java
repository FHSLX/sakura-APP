package android.os;

/**
 * 最小 Looper 替身（悬浮窗可见性测试专用）。
 *
 * getThread() 提供与真实 Looper 同名的主线程标识：FIX-RUNTIME-01 的
 * RenderSignalRouter 用它区分「桥线程封送」与「主线程落地」。
 * 替身固定返回（并缓存）构造它的线程，等价于真实实现里的主线程。
 */
public final class Looper {

    private static final Looper MAIN = new Looper();
    private final Thread thread = Thread.currentThread();

    private Looper() {
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }

    public Thread getThread() {
        return thread;
    }
}
