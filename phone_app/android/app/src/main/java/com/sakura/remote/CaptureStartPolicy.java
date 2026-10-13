package com.sakura.remote;

/**
 * CaptureService.onStartCommand 的启动裁决（RV-07 / INT-02）。
 *
 * 旧实现一进 onStartCommand 就先 startForeground，再读 requestId、校验、建资源；
 * 重复 start 会直接覆盖 {@code requestId}/资源；陈旧启动还会去动「截图隐藏」这个
 * 属于当前任务的隐藏理由，导致新任务的悬浮窗提前恢复。
 *
 * 第二轮修正后仍有 INT-02：拒绝分支一律调用
 * {@code startForegroundBestEffortAndStop()} —— 它包含 stopForeground + stopSelf，
 * 会把**正在执行的合法截图**一起杀掉。现在把「拒绝时该不该停服务」也做成纯函数：
 *   - 有活动 run：拒绝只处理被拒绝的那次 start，不碰当前 projection /
 *     notification / 隐藏令牌，也不 stopSelf；
 *   - 没有活动 run 的空启动：按 startId 清理服务，并先满足前台期限。
 *
 * 纯 Java 无 Android 依赖，可用普通 JVM 断言上述分支。
 */
final class CaptureStartPolicy {

    enum Decision {
        ACCEPT,
        ACCEPT_IDEMPOTENT,
        REPLACE_STALE,
        REJECT_INVALID,
        REJECT_STALE,
        REJECT_BUSY
    }

    /**
     * 拒绝一次 start 之后对**服务本身**的处置（INT-02）。
     */
    enum RejectAction {
        /** 当前没有活动 run：按 startId 清理这次空启动（需要时先满足前台期限）。 */
        STOP_EMPTY_START,
        /** 有活动 run：只丢弃这次 start，绝不 stopSelf / 不碰当前资源。 */
        DROP_START_ONLY
    }

    private CaptureStartPolicy() {
    }

    /**
     * @param running              当前实例是否已有一次未终结的 run
     * @param currentId            当前 run 的 requestId（可能为空）
     * @param newId                本次 start 的 requestId
     * @param newRequestActive     本次 requestId 在协调器里是否仍活动
     * @param currentRequestActive 当前 run 的 requestId 在协调器里是否仍活动
     */
    static Decision decide(boolean running, String currentId, String newId,
                           boolean newRequestActive, boolean currentRequestActive) {
        if (newId == null || newId.isEmpty()) {
            return Decision.REJECT_INVALID;
        }
        if (!newRequestActive) {
            // 陈旧启动：不能释放新任务的隐藏状态，也不能消耗 grant
            return Decision.REJECT_STALE;
        }
        if (!running) {
            return Decision.ACCEPT;
        }
        if (newId.equals(currentId)) {
            return Decision.ACCEPT_IDEMPOTENT;
        }
        if (currentRequestActive) {
            // 当前 run 仍对应活动请求：拒绝覆盖它的 requestId/resources
            return Decision.REJECT_BUSY;
        }
        return Decision.REPLACE_STALE;
    }

    /**
     * 拒绝一次 start 时对服务的处置（INT-02）。
     *
     * @param activeRunPresent 当前是否真的有一次未终结的 run（{@code !finished}）
     */
    static RejectAction onRejected(boolean activeRunPresent) {
        return activeRunPresent ? RejectAction.DROP_START_ONLY : RejectAction.STOP_EMPTY_START;
    }
}
