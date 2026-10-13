package android.app;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 ActivityManager 替身（测试专用）。
 *
 * 只提供 AppCaptureYield 用到的 {@code getAppTasks()} /
 * {@code AppTask.getTaskInfo().id} / {@code AppTask.moveToFront()}，
 * 并记录调用次数与顺序供断言。
 */
public class ActivityManager {

    /** 与真实 RecentTaskInfo 对应的最小替身（只要 id）。 */
    public static final class RecentTaskInfo {
        public int id;

        public RecentTaskInfo(int id) {
            this.id = id;
        }
    }

    public static final class AppTask {
        /** 最近一次被 moveToFront 的任务（测试断言用）。 */
        public static AppTask lastMovedToFront;
        public static int moveToFrontTotal;
        public int moveToFrontCalls;
        private final RecentTaskInfo info;

        public AppTask(int taskId) {
            this.info = new RecentTaskInfo(taskId);
        }

        public RecentTaskInfo getTaskInfo() {
            return info;
        }

        public void moveToFront() {
            moveToFrontCalls++;
            moveToFrontTotal++;
            lastMovedToFront = this;
        }
    }

    private final List<AppTask> tasks = new ArrayList<>();

    public ActivityManager() {
        tasks.add(new AppTask(1001));
    }

    public void addTaskForTest(int taskId) {
        tasks.add(new AppTask(taskId));
    }

    public AppTask firstTaskForTest() {
        return tasks.isEmpty() ? null : tasks.get(0);
    }

    public List<AppTask> getAppTasks() {
        return tasks;
    }

    public static void resetForTest() {
        AppTask.lastMovedToFront = null;
        AppTask.moveToFrontTotal = 0;
    }
}
