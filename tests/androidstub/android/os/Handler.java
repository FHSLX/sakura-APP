package android.os;

import java.util.ArrayDeque;

/**
 * 最小 Handler 替身，带**可控队列**（INT-07 测试专用，不进入产品构建）。
 *
 * 真实 Android 的 Handler 绑定 Looper；这里所有 Handler 共享一条主线程队列，
 * 测试可以确定性地逐条执行（等价于主线程消息循环），既验证真实产品的
 * post/removeCallbacks 接线，也不含任何被测生产控制流。
 *
 * 注意：替身**不实现延迟语义** —— {@code postDelayed} 与 {@code post} 一样
 * 只入队；{@code removeCallbacks} 按 Runnable 身份移除；{@code removeCallbacksAndMessages(null)}
 * 清空整条队列（与真实语义一致）。测试按需调用 drainOne/drainAll。
 */
public class Handler {

    private static final ArrayDeque<Runnable> QUEUE = new ArrayDeque<>();
    /** 排空上限，防止某个自重复调度的任务把测试挂死。 */
    private static final int MAX_DRAIN_STEPS = 200000;

    private final Looper looper;

    public Handler() {
        this(Looper.getMainLooper());
    }

    public Handler(Looper looper) {
        this.looper = looper == null ? Looper.getMainLooper() : looper;
    }

    public Looper getLooper() {
        return looper;
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

    public boolean postAtFrontOfQueue(Runnable runnable) {
        if (runnable == null) {
            return false;
        }
        QUEUE.addFirst(runnable);
        return true;
    }

    public void removeCallbacks(Runnable runnable) {
        if (runnable == null) {
            return;
        }
        // 同一 Runnable 可能被重复入队；按真实语义全部移除。
        while (QUEUE.remove(runnable)) {
            // keep removing
        }
    }

    public void removeCallbacks(Runnable runnable, Object token) {
        removeCallbacks(runnable);
    }

    public void removeCallbacksAndMessages(Object token) {
        QUEUE.clear();
    }

    // ---- 测试控制 ----------------------------------------------------

    /** 执行一条排队任务（FIFO）；队列为空返回 false。 */
    public static boolean drainOne() {
        Runnable next = QUEUE.pollFirst();
        if (next == null) {
            return false;
        }
        next.run();
        return true;
    }

    /** 按 FIFO 全部执行（含执行过程中新排入的任务），带步数上限。 */
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
