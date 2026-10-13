package com.sakura.remote;

import android.content.Context;

/**
 * App 发起「桌面截屏」时的让位 / 恢复状态（纯状态 + 小心调用系统入口）。
 *
 * <p>为什么需要：浮动窗口的截图路径已经要求「授权页 + 悬浮窗都让位」才建首帧，
 * 但主 App 自己发起的截图没有这一步 —— 授权页 finish 之后 MainActivity 又回到
 * 前台，首帧拍到的就是 App 自己，而不是用户想截的桌面。
 *
 * <p>做法：授权成功那一刻（授权页仍是前台、允许移动任务）把整个任务收到后台；
 * 截图终结（成功/失败/取消/超时/服务启动失败/页面销毁）时再尝试把任务带回前台。
 *
 * <p>恢复必须绑定**具体请求 + 任务 id + 恢复代际**：旧请求/旧 Runnable 不能把
 * 新截图期间的用户界面强行带回前台，也不能用 {@code tasks.get(0)} 猜任务
 * （它不保证是刚才被收起的那个任务）。
 */
final class AppCaptureYield {

    private static boolean yielded;
    private static String yieldedRequestId = "";
    private static int yieldedTaskId = -1;
    private static Context appContext;
    private static long restoreGeneration;

    private AppCaptureYield() {
    }

    /** 只有 App 自己发起的截图才需要收起 App（浮窗发起时 App 本就不在前台）。 */
    static boolean shouldYield(String origin) {
        return "APP".equals(origin);
    }

    static synchronized void markYielded(String requestId, Context context, int taskId) {
        yielded = true;
        yieldedRequestId = requestId == null ? "" : requestId;
        yieldedTaskId = taskId;
        appContext = context == null ? null : context.getApplicationContext();
        // 新一次让位：让此前排队但还没执行的恢复 Runnable 失效
        restoreGeneration += 1;
    }

    static synchronized boolean isYielded() {
        return yielded;
    }

    static synchronized void resetForTest() {
        yielded = false;
        yieldedRequestId = "";
        yieldedTaskId = -1;
        appContext = null;
        restoreGeneration = 0;
    }

    /**
     * 指定请求终结后恢复 App（幂等，且只恢复**它自己**收起的那个任务）。
     *
     * <p>用 {@link android.app.ActivityManager.AppTask#moveToFront()}：它在主线程
     * 调用最稳妥，所以统一 post 到主线程，并在执行瞬间复核「请求 id + 代际」。
     * 任何异常（后台启动受限/任务已不存在）都只吞掉 —— 用户仍可从通知栏回到 App。
     */
    static void restore(String requestId) {
        final Context context;
        final int taskId;
        final long generation;
        synchronized (AppCaptureYield.class) {
            if (!yielded) {
                return;
            }
            String wanted = requestId == null ? "" : requestId;
            if (!wanted.equals(yieldedRequestId)) {
                // 不是当前被收起的那次请求：绝不动它的恢复状态
                return;
            }
            yielded = false;
            yieldedRequestId = "";
            taskId = yieldedTaskId;
            yieldedTaskId = -1;
            context = appContext;
            appContext = null;
            generation = restoreGeneration;
        }
        if (context == null || taskId < 0) {
            return;
        }
        Runnable task = () -> {
            synchronized (AppCaptureYield.class) {
                // 期间已经有新的让位（新截图开始）：旧恢复不得把 App 带回前台
                if (generation != restoreGeneration) {
                    return;
                }
            }
            try {
                android.app.ActivityManager manager = (android.app.ActivityManager)
                        context.getSystemService(Context.ACTIVITY_SERVICE);
                if (manager == null) {
                    return;
                }
                java.util.List<android.app.ActivityManager.AppTask> tasks = manager.getAppTasks();
                if (tasks == null) {
                    return;
                }
                for (android.app.ActivityManager.AppTask candidate : tasks) {
                    android.app.ActivityManager.RecentTaskInfo info = candidate.getTaskInfo();
                    if (info != null && info.id == taskId) {
                        candidate.moveToFront();
                        return;
                    }
                }
            } catch (Throwable ignored) {
                // 后台启动受限：保持后台即可
            }
        };
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(task);
        } catch (Throwable ignored) {
            task.run();
        }
    }
}
