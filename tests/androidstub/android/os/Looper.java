package android.os;

/**
 * 最小 Looper 替身（INT-07 测试专用，不进入产品构建）。
 *
 * 只提供 {@code getMainLooper()} 供真实产品的 Handler 构造使用；真正的
 * 可控制消息队列在 {@link Handler} 里，测试通过 Handler 的静态方法排空。
 */
public final class Looper {

    private static final Looper MAIN = new Looper();

    private Looper() {
    }

    public static Looper getMainLooper() {
        return MAIN;
    }

    public static Looper myLooper() {
        return MAIN;
    }

    /** 与真实 Looper 同名：FIX-RUNTIME-01 用它标识主线程。 */
    public Thread getThread() {
        return mainThread;
    }

    private static final Thread mainThread = Thread.currentThread();

    public static void prepare() {
    }

    public static void loop() {
    }
}
