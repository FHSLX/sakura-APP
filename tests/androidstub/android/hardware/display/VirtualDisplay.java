package android.hardware.display;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最小 VirtualDisplay 替身（INT-07）。
 *
 * 用一个进程级计数器记录「真实生产代码到底创建了几次显示」，
 * 这是 INT-05「超时不得建显示」验收的核心可测量量。
 */
public class VirtualDisplay {

    private static final AtomicInteger CREATE_COUNT = new AtomicInteger(0);

    public static int createCountForTest() {
        return CREATE_COUNT.get();
    }

    public static void resetCreateCountForTest() {
        CREATE_COUNT.set(0);
    }

    public static void countCreationForTest() {
        CREATE_COUNT.incrementAndGet();
    }

    public void release() {
    }
}
