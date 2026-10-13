package android.os;

import java.util.ArrayDeque;

/**
 * 最小 Handler 替身（悬浮窗可见性测试专用）。
 *
 * 与 tests/androidstub 版本同语义：所有 Handler 共享一条主线程队列，测试用
 * drainOne/drainAll 确定性推进。替身**不实现延迟语义**（postDelayed 同 post），
 * 因为被测逻辑的时间推进由注入时钟负责，队列只负责「该执行时执行」。
 */
public class Handler {

    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    private static final int MAX_DRAIN_STEPS = 200000;

    public Handler() {
    }

    public Handler(Looper looper) {
    }

    public Looper getLooper() {
        return Looper.getMainLooper();
    }

    public boolean post(Runnable runnable) {
        if (runnable == null) {
            return false;
        }
        QUEUE.addLast(runnable);
        return true;
    }

    public boolean postDelayed(Runnable runnable, long delayMillis) {
        return post(runnable);
    }

    public void removeCallbacks(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        while (QUEUE.remove(runnable)) {
            // 全部移除，与真实语义一致
        }
    }

    public void removeCallbacksAndMessages(Object token) {
        QUEUE.clear();
    }

    public static boolean drainOne() {
        Runnable next = QUEUE.pollFirst();
        if (next == null) {
            return false;
        }
        next.run();
        return true;
    }

    public static int drainAll() {
        int steps = 0;
        while (!QUEUE.isEmpty() && steps < MAX_DRAIN_STEPS) {
            Runnable next = QUEUE.pollFirst();
            if (next == null) {
                break;
            }
            next.run();
            steps++;
        }
        return steps;
    }

    public static int pending() {
        return QUEUE.size();
    }

    public static void clearQueue() {
        QUEUE.clear();
    }
}
