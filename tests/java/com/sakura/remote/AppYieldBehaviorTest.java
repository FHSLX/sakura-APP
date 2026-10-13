package com.sakura.remote;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;

/**
 * AppCaptureYield 的让位/恢复所有权行为测试（MEDIA-02 相关）。
 *
 * <p>只驱动真实生产类 {@link AppCaptureYield} + 系统替身，验证：
 *   1) 只有 APP 来源才收起 App；
 *   2) 恢复必须匹配「被收起的那次 requestId」，别的请求不能消费它；
 *   3) 恢复按**记录的任务 id**（不是 tasks.get(0)）带回前台；
 *   4) 旧恢复 Runnable 与新截图重叠时不得把 App 带回前台（代际失效）；
 *   5) 没有 ActivityManager 时不崩溃。
 */
public final class AppYieldBehaviorTest {

    private static int failures;

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name
                + (ok ? "" : " -> " + String.valueOf(detail)));
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        check("APP 来源需要让位", AppCaptureYield.shouldYield("APP"), "APP");
        check("OVERLAY 来源不让位", !AppCaptureYield.shouldYield("OVERLAY"), "OVERLAY");

        // ---- 2) 请求 id 不匹配时不消费恢复状态 ----
        {
            AppCaptureYield.resetForTest();
            ActivityManager.resetForTest();
            Handler.clearQueue();
            Context context = new Context();
            ActivityManager manager = new ActivityManager();
            manager.addTaskForTest(77);
            context.setSystemServiceForTest(Context.ACTIVITY_SERVICE, manager);

            AppCaptureYield.markYielded("req-A", context, 77);
            AppCaptureYield.restore("req-B");   // 不是它收起的请求
            Handler.drainAll();
            check("别的 requestId 不能触发恢复", ActivityManager.AppTask.moveToFrontTotal == 0,
                    ActivityManager.AppTask.moveToFrontTotal);
            check("被误调用的恢复不消费让位状态", AppCaptureYield.isYielded(), "yielded");

            AppCaptureYield.restore("req-A");
            Handler.drainAll();
            check("正确 requestId 恢复一次", ActivityManager.AppTask.moveToFrontTotal == 1,
                    ActivityManager.AppTask.moveToFrontTotal);
            check("恢复的是记录的 task id（不是 get(0)）",
                    ActivityManager.AppTask.lastMovedToFront != null
                            && ActivityManager.AppTask.lastMovedToFront.getTaskInfo().id == 77,
                    ActivityManager.AppTask.lastMovedToFront == null ? null
                            : ActivityManager.AppTask.lastMovedToFront.getTaskInfo().id);
            check("恢复后让位状态已清除", !AppCaptureYield.isYielded(), "yielded");
        }

        // ---- 4) 旧恢复与新截图重叠：旧 Runnable 必须失效 ----
        {
            AppCaptureYield.resetForTest();
            ActivityManager.resetForTest();
            Handler.clearQueue();
            Context context = new Context();
            ActivityManager manager = new ActivityManager();
            manager.addTaskForTest(41);
            manager.addTaskForTest(42);
            context.setSystemServiceForTest(Context.ACTIVITY_SERVICE, manager);

            AppCaptureYield.markYielded("req-old", context, 41);
            AppCaptureYield.restore("req-old");      // 排队 R1（gen=1）
            AppCaptureYield.markYielded("req-new", context, 42);   // 新截图开始（gen=2）
            AppCaptureYield.restore("req-new");      // 排队 R2（gen=2）
            Handler.drainAll();
            check("旧恢复没有把旧任务带回前台",
                    manager.firstTaskForTest() != null
                            && manager.firstTaskForTest().moveToFrontCalls == 0,
                    manager.firstTaskForTest() == null ? null
                            : manager.firstTaskForTest().moveToFrontCalls);
            check("新截图恢复的是新任务一次",
                    ActivityManager.AppTask.moveToFrontTotal == 1
                            && ActivityManager.AppTask.lastMovedToFront.getTaskInfo().id == 42,
                    ActivityManager.AppTask.moveToFrontTotal);
        }

        // ---- 5) 没有 ActivityManager 不崩溃 ----
        {
            AppCaptureYield.resetForTest();
            ActivityManager.resetForTest();
            Handler.clearQueue();
            Context context = new Context();   // 未注入 ACTIVITY_SERVICE
            AppCaptureYield.markYielded("req-x", context, 5);
            AppCaptureYield.restore("req-x");
            Handler.drainAll();
            check("缺少 ActivityManager 时安全返回", ActivityManager.AppTask.moveToFrontTotal == 0,
                    ActivityManager.AppTask.moveToFrontTotal);
        }

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production source: AppCaptureYield)");
    }
}
