package com.sakura.remote;

/**
 * Codex 在复审期用**真实生产 RegionSessionState** 复现 INT-04 的探针。
 *
 * 场景：A 换页到 B、B 已存好有效快照之后，一次迟到的旧 A 查询（旧代际）
 * 必须被拒绝，且**不能**把最新有效的 B 快照清掉。
 *
 * 期望输出（修复后）：
 *   old_lookup_rejected=true
 *   current_B_snapshot_preserved=true
 *   退出码 0
 */
public final class RegionLateLookupProbe {

    private RegionLateLookupProbe() {
    }

    public static void main(String[] args) {
        RegionSessionState state = new RegionSessionState();
        state.beginSession("A");
        long oldGeneration = state.generation();
        state.beginSession("B");
        RegionSnapshotStore.Snapshot b = new RegionSnapshotStore.Snapshot(
                "B", 1, 100, 100, new float[][]{{0, 0, 50, 50}}, false);
        state.acceptRevision("B", 1);
        state.store(b);
        boolean rejectedOld = state.applicableSnapshot(oldGeneration, "A") == null;
        boolean preservedB = state.latest() == b;
        System.out.println("old_lookup_rejected=" + rejectedOld);
        System.out.println("current_B_snapshot_preserved=" + preservedB);
        if (!rejectedOld || !preservedB) {
            System.exit(1);
        }
    }
}
