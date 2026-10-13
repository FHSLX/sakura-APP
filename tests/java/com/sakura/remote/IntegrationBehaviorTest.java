package com.sakura.remote;

/**
 * 第三轮集成修正（INT-01..INT-04）的**模型/策略级** JVM 测试。
 *
 * 重要（INT-07 复审）：
 *   本文件**不再**用测试里手写的 ServiceLifecycleDriver / ConsentActivityDriver
 *   去"复制"生产控制流。那种驱动只调用纯策略，无法发现 CaptureService /
 *   CapturePermissionActivity / OverlayTouchRegionController 的真实接线错误，
 *   因此不能作为实际适配层证据。
 *
 *   真实适配层行为（真实生产类 + 最小 Android 替身 + mutation 证据）在：
 *     - tests/java/com/sakura/remote/RealAdapterBehaviorTest.java
 *     - tests/real_adapter_behavior_check.ps1
 *     - tests/real_adapter_mutation_check.ps1
 *
 *   这里保留的是**真实纯 Java 生产类**（RegionSessionState / RegionSessionGate /
 *   RegionSnapshotStore / 策略类）的行为断言 —— 它们本身就是生产代码，不含复制品。
 *
 * 用法（见 tests/integration_behavior_check.ps1）：
 *   javac -d <out> <product sources> IntegrationBehaviorTest.java
 *   java -cp <out> com.sakura.remote.IntegrationBehaviorTest
 */
public final class IntegrationBehaviorTest {

    private static int failures = 0;

    public static void main(String[] args) {
        startPolicyDecisions();
        consentPolicyDeadlinePrecedesPrerequisites();
        regionStateAtomicSubmitAndLateLookup();
        regionSessionSwitchDropsOldSnapshot();
        regionSessionDuplicateRegistrationIsIdempotent();
        regionSessionLateEventCannotRestoreOldRegion();
        regionSessionOldEndDoesNotClearNew();
        regionSessionStaleRevisionAfterDuplicateRegistration();
        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (model/policy level: real pure-Java production classes only)");
    }

    // ================= 纯策略（真实生产类，非复制） =================

    private static void startPolicyDecisions() {
        // 有活动 run 时，拒绝只丢弃这一次 start（不得停服务）
        check("有活动 run 的陈旧启动是 DROP_START_ONLY",
                CaptureStartPolicy.onRejected(true) == CaptureStartPolicy.RejectAction.DROP_START_ONLY);
        check("无活动 run 的空启动才 STOP_EMPTY_START",
                CaptureStartPolicy.onRejected(false)
                        == CaptureStartPolicy.RejectAction.STOP_EMPTY_START);
        check("空 requestId 被拒",
                CaptureStartPolicy.decide(false, "", "", false, false)
                        == CaptureStartPolicy.Decision.REJECT_INVALID);
        check("不活动的新请求被拒（陈旧）",
                CaptureStartPolicy.decide(false, "", "B", false, false)
                        == CaptureStartPolicy.Decision.REJECT_STALE);
        check("空闲时接受活动请求",
                CaptureStartPolicy.decide(false, "", "B", true, false)
                        == CaptureStartPolicy.Decision.ACCEPT);
        check("同一请求重复 start 幂等",
                CaptureStartPolicy.decide(true, "A", "A", true, true)
                        == CaptureStartPolicy.Decision.ACCEPT_IDEMPOTENT);
        check("当前 run 仍活动时拒绝覆盖（忙）",
                CaptureStartPolicy.decide(true, "A", "B", true, true)
                        == CaptureStartPolicy.Decision.REJECT_BUSY);
        check("当前 run 已失效时显式替换",
                CaptureStartPolicy.decide(true, "A", "B", true, false)
                        == CaptureStartPolicy.Decision.REPLACE_STALE);
    }

    private static void consentPolicyDeadlinePrecedesPrerequisites() {
        check("超时后即使前提满足也失败",
                CaptureConsentPolicy.nextWaitAction(false, true, true, 3000L, 3000L)
                        == CaptureConsentPolicy.WaitAction.FAIL_NO_YIELD);
        check("期限内前提满足才申请",
                CaptureConsentPolicy.nextWaitAction(false, true, true, 2999L, 3000L)
                        == CaptureConsentPolicy.WaitAction.REQUEST_CONSENT);
        check("暂停（未 resumed）时不申请",
                CaptureConsentPolicy.nextWaitAction(false, false, true, 100L, 3000L)
                        == CaptureConsentPolicy.WaitAction.WAIT_FOCUS);
        check("悬浮窗未让位时不申请",
                CaptureConsentPolicy.nextWaitAction(true, true, true, 100L, 3000L)
                        == CaptureConsentPolicy.WaitAction.WAIT_OVERLAY);
    }

    // ================= INT-04：真实 RegionSessionState =================

    private static RegionSnapshotStore.Snapshot snapshot(String sessionId, long revision) {
        return new RegionSnapshotStore.Snapshot(
                sessionId, revision, 393f, 700f, new float[][]{{10f, 10f, 40f, 400f}}, false);
    }

    private static void regionStateAtomicSubmitAndLateLookup() {
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        long generationA = state.generation();
        state.beginSession("page-B");
        RegionSnapshotStore.Snapshot b = snapshot("page-B", 1L);
        check("B revision=1 原子提交被接受",
                state.submit("page-B", 1L, b).accepted());
        check("旧代际查询被拒绝", state.applicableSnapshot(generationA, "page-A") == null);
        check("旧代际查询没有清掉最新有效的 B 快照", state.latest() == b, state.latest());
        check("B 快照仍可应用", state.isApplicable(b, state.generation()));
        check("被拒提交不落库", !state.submit("page-B", 1L, snapshot("page-B", 1L)).accepted());
    }

    private static void regionSessionSwitchDropsOldSnapshot() {
        RegionSessionState state = new RegionSessionState();
        check("登记 A 是真实换页", state.beginSession("page-A"));
        check("A 的 revision=1 被接受",
                state.acceptRevision("page-A", 1L) == RegionSessionGate.Decision.ACCEPT);
        state.store(snapshot("page-A", 1L));
        check("A 的快照已保存", state.latest() != null && "page-A".equals(state.latest().sessionId));
        long generationA = state.generation();

        // 换页到 B
        check("登记 B 是真实换页", state.beginSession("page-B"));
        check("换页后 A 的快照被清掉（按旧会话清）", state.latest() == null, state.latest());
        check("代际已推进", state.generation() != generationA,
                new long[]{generationA, state.generation()});
        check("B revision=1 可接受",
                state.acceptRevision("page-B", 1L) == RegionSessionGate.Decision.ACCEPT);
        check("A 的旧 revision 仍然被拒",
                state.acceptRevision("page-A", 9999L)
                        == RegionSessionGate.Decision.STALE_SESSION);

        // 迟到的旧 A 载荷（在 B 已登记后到达）不得被接受
        check("换页后 A 载荷被拒",
                state.acceptRevision("page-A", 10000L)
                        == RegionSessionGate.Decision.STALE_SESSION);

        // 再次 enable/attach/layout 触发的重复登记不得让旧快照复活
        state.store(snapshot("page-B", 1L));
        check("B 的快照已保存", state.latest() != null);
    }

    private static void regionSessionDuplicateRegistrationIsIdempotent() {
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-B");
        check("B revision=1 接受",
                state.acceptRevision("page-B", 1L) == RegionSessionGate.Decision.ACCEPT);
        state.store(snapshot("page-B", 1L));

        // 同编号重复登记：enable / attach / layout 都会走到这里
        check("同编号重复登记是无操作", !state.beginSession("page-B"));
        check("重复登记不清快照", state.latest() != null);
        check("重复登记不推进代际", state.generation() == 1L, state.generation());
        check("重复登记后 lastRevision 保持",
                state.lastRevision() == 1L, state.lastRevision());
        check("同 B 再登记后陈旧 revision=1 仍被拒",
                state.acceptRevision("page-B", 1L)
                        == RegionSessionGate.Decision.STALE_REVISION);
        check("同 B 更高的 revision 正常接受",
                state.acceptRevision("page-B", 2L) == RegionSessionGate.Decision.ACCEPT);
        check("空编号登记是无操作", !state.beginSession("") && !state.beginSession(null));
    }

    private static void regionSessionLateEventCannotRestoreOldRegion() {
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        state.acceptRevision("page-A", 1L);
        state.store(snapshot("page-A", 1L));
        long generationA = state.generation();
        RegionSnapshotStore.Snapshot snapshotA = state.latest();

        // 换页 B：旧 A 的异步应用任务此时才轮到执行
        state.beginSession("page-B");
        check("旧 A 事件在换页后不可应用",
                !state.isApplicable(snapshotA, generationA));
        check("代际不符时取不到可应用快照",
                state.applicableSnapshot(generationA, "page-A") == null);

        // B 建立自己的快照后，旧 A 的事件仍然不能覆盖
        state.acceptRevision("page-B", 1L);
        RegionSnapshotStore.Snapshot snapshotB = snapshot("page-B", 1L);
        state.store(snapshotB);
        long generationB = state.generation();
        check("B 的快照可应用", state.isApplicable(snapshotB, generationB));
        check("旧 A 的快照仍不可应用", !state.isApplicable(snapshotA, generationA));
        check("用旧代际取快照会失效", state.applicableSnapshot(generationA, "page-B") == null);

        // 注销 B（pagehide）后，任何在途事件都不可应用
        long generationB2 = state.generation();
        check("注销 B 是真实的", state.endSession("page-B"));
        check("注销后 B 的快照被清", state.latest() == null);
        check("注销后 B 事件不可应用", !state.isApplicable(snapshotB, generationB2));
    }

    private static void regionSessionOldEndDoesNotClearNew() {
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        state.beginSession("page-B");
        state.acceptRevision("page-B", 1L);
        state.store(snapshot("page-B", 1L));

        check("旧 A 注销不影响 B", !state.endSession("page-A"));
        check("B 仍是当前会话", state.isCurrent("page-B"));
        check("B 的快照仍在", state.latest() != null
                && "page-B".equals(state.latest().sessionId));
        check("B 的 lastRevision 未被重置", state.lastRevision() == 1L, state.lastRevision());
        check("B 注销生效", state.endSession("page-B") && !state.isCurrent("page-B"));
        check("B 注销后无会话", state.session().isEmpty(), state.session());
    }

    private static void regionSessionStaleRevisionAfterDuplicateRegistration() {
        // 回归：旧实现在 beginSession 里先切换 gate 再 dropIfSession(gate.session())，
        // 「按新会话清快照」永远匹配不到旧快照。这里断言真正换页后不会留下旧快照。
        RegionSessionState state = new RegionSessionState();
        state.beginSession("page-A");
        state.acceptRevision("page-A", 5L);
        state.store(snapshot("page-A", 5L));
        state.beginSession("page-B");
        check("换页后不残留旧会话快照", state.latest() == null, state.latest());
        state.store(snapshot("page-A", 5L));   // 迟到的旧快照（模拟入队后才执行）
        check("迟到旧快照不可应用",
                state.applicableSnapshot(state.generation(), "page-B") == null);
    }

    // ================= 辅助 =================

    private static void check(String name, boolean condition) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name);
        if (!condition) {
            failures++;
        }
    }

    private static void check(String name, boolean condition, Object detail) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name
                + (condition ? "" : " -> " + String.valueOf(detail)));
        if (!condition) {
            failures++;
        }
    }
}
