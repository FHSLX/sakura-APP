package com.sakura.remote;

/**
 * 输入区域的「会话 + revision + 快照 + 代际」状态（INT-04）。
 *
 * 为什么把它从 {@link OverlayTouchRegionController} 里拆出来：
 *   换页清快照的顺序错误（先切换 gate 再按**新**会话清快照）、同编号重复登记
 *   重置 revision、以及「迟到的异步应用把旧区域恢复回来」这三件事，过去散落在
 *   控制器的 beginSession / endSession / applyPayload 里，只有真机 + API33 才能验。
 *   现在这些规则集中在这个纯 Java 类里，控制器只负责「读状态 → 操作 surface」，
 *   于是可以用普通 JVM 跑真实行为断言（不需要 Android、不需要真机）。
 *
 * 不变量：
 *   - 同一会话重复登记是无操作的（不推进 revision，不清快照）；
 *   - 真正换页时按**旧**会话丢弃快照，再切换会话，并自增代际；
 *   - 快照的会话必须与当前会话一致，否则不算「可应用」；
 *   - 代际只增不减：换页/注销之后，旧代际的事件一律不可应用；
 *   - 「裁决 revision + 读代际 + 存快照」必须在**同一把锁**下完成一次
 *     （{@link #submit}）：否则 A 校验通过、B 换页、A 才 store，就会用旧代际
 *     的载荷覆盖 B 的新快照。三个小类各自 synchronized 并不构成整体原子。
 */
final class RegionSessionState {

    private final RegionSessionGate gate = new RegionSessionGate();
    private final RegionSnapshotStore snapshots = new RegionSnapshotStore();
    private long generation;

    /**
     * 一次载荷提交的原子结果（INT-04）。
     *
     * {@code generation} 是**裁决与落库同一临界区内**读到的代际，
     * 异步应用据此复核；{@code snapshot} 只在 {@link #accepted()} 时非 null。
     */
    static final class SubmitResult {
        final RegionSessionGate.Decision decision;
        final long generation;
        final RegionSnapshotStore.Snapshot snapshot;

        SubmitResult(RegionSessionGate.Decision decision, long generation,
                     RegionSnapshotStore.Snapshot snapshot) {
            this.decision = decision;
            this.generation = generation;
            this.snapshot = snapshot;
        }

        boolean accepted() {
            return decision == RegionSessionGate.Decision.ACCEPT;
        }
    }

    /**
     * 登记页面会话。
     *
     * @return true = 真的换页（调用方需要把窗口恢复成整窗并丢弃在途任务）；
     *         false = 空编号或同编号重复登记（无操作）
     */
    synchronized boolean beginSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || gate.isCurrent(sessionId)) {
            return false;
        }
        // 顺序不能反：必须按**旧**会话清快照，否则新会话编号永远匹配不上旧快照
        String previous = gate.session();
        if (!previous.isEmpty()) {
            snapshots.dropIfSession(previous);
        }
        snapshots.clear();
        generation++;
        gate.beginSession(sessionId);
        return true;
    }

    /**
     * 注销会话（pagehide / 导航开始）。
     *
     * @return true = 真的注销了当前会话；false = 空编号或不是当前会话（旧页迟到注销）
     */
    synchronized boolean endSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty() || !gate.isCurrent(sessionId)) {
            return false;
        }
        snapshots.dropIfSession(sessionId);
        snapshots.clear();
        generation++;
        gate.endSession(sessionId);
        return true;
    }

    synchronized boolean isCurrent(String sessionId) {
        return gate.isCurrent(sessionId);
    }

    synchronized String session() {
        return gate.session();
    }

    synchronized long generation() {
        return generation;
    }

    synchronized boolean isGenerationCurrent(long candidate) {
        return candidate == generation;
    }

    synchronized long lastRevision() {
        return gate.lastRevision();
    }

    /** 载荷的会话 + revision 裁决（未登记/旧会话/陈旧 revision 全部拒绝）。 */
    synchronized RegionSessionGate.Decision acceptRevision(String sessionId, long revision) {
        return gate.accept(sessionId, revision);
    }

    synchronized void store(RegionSnapshotStore.Snapshot snapshot) {
        snapshots.store(snapshot);
    }

    /**
     * 原子提交一次载荷：在同一临界区内完成
     * 「gate.accept → 读代际 → snapshots.store」。
     *
     * 必须用这一个方法，而不是先 {@link #acceptRevision}、再 {@link #generation}、
     * 最后 {@link #store}：三次分别 synchronized 时，beginSession/endSession
     * 可以在中间插进来（换页 + 清快照 + generation++），于是旧代际的载荷会落在
     * 新会话上，把 B 的快照覆盖回 A。这里与 begin/endSession 共用同一把锁，
     * 因此提交与代际切换是串行的整体。
     *
     * @return 裁决 + 落库时的代际；被拒时 snapshot 为 null、代际仍是当时的真实值
     */
    synchronized SubmitResult submit(String sessionId, long revision,
                                     RegionSnapshotStore.Snapshot snapshot) {
        RegionSessionGate.Decision decision = gate.accept(sessionId, revision);
        long acceptedGeneration = generation;
        if (decision != RegionSessionGate.Decision.ACCEPT || snapshot == null) {
            return new SubmitResult(decision, acceptedGeneration, null);
        }
        snapshots.store(snapshot);
        return new SubmitResult(decision, acceptedGeneration, snapshot);
    }

    /**
     * 取「当前可应用」的快照：会话与代际都必须仍然一致。
     *
     * 不一致时**只拒绝**，返回 null —— 绝不在这里丢弃快照。
     *
     * 旧实现遇到旧代际会 {@code snapshots.dropIfSession(latest.sessionId)}，
     * 而 latest 可能就是**当前有效的 B 快照**：A 已换页、B 刚存好快照，
     * 此时一次迟到的 A 查询（旧代际）就会把 B 快照清掉 —— 属于用旧事件
     * 破坏新会话。真正的清理只应由 {@link #beginSession}/{@link #endSession}
     * 在代际切换的临界区内完成。
     */
    synchronized RegionSnapshotStore.Snapshot applicableSnapshot(long candidateGeneration,
                                                                 String sessionId) {
        RegionSnapshotStore.Snapshot latest = snapshots.latest();
        if (latest == null) {
            return null;
        }
        if (candidateGeneration != generation || !gate.isCurrent(sessionId)
                || !gate.isCurrent(latest.sessionId)) {
            return null;
        }
        return latest;
    }

    /** 只在该快照仍是最新一版、会话与代际都一致时返回它（异步应用前的复核）。 */
    synchronized boolean isApplicable(RegionSnapshotStore.Snapshot snapshot,
                                      long candidateGeneration) {
        if (snapshot == null) {
            return false;
        }
        RegionSnapshotStore.Snapshot latest = snapshots.latest();
        return latest == snapshot
                && candidateGeneration == generation
                && gate.isCurrent(snapshot.sessionId);
    }

    synchronized RegionSnapshotStore.Snapshot latest() {
        return snapshots.latest();
    }

    synchronized void clearSnapshots() {
        snapshots.clear();
    }

    /** 服务销毁：彻底复位。 */
    synchronized void reset() {
        generation++;
        gate.reset();
        snapshots.clear();
    }
}
