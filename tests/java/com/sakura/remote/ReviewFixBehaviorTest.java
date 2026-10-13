package com.sakura.remote;

/**
 * 第二轮复审修正（RV-01..RV-09）的纯 Java 行为测试（普通 JVM，无需 Android/JUnit）。
 *
 * 覆盖的是真实决策代码，不是源码字符串搜索：
 *   - PageSessionRegistry：真实页面会话登记 / 同 URL reload 换会话 / 旧会话失效；
 *   - RegionSessionGate：revision 只在同会话内单调，新会话从 1 开始可用，
 *     旧会话任何高 revision 被拒，未登记会话不被接受；
 *   - RegionSnapshotStore：detach 不丢最新快照，reattach 可重建；换页才丢弃；
 *   - CaptureConsentPolicy：授权 Activity 旋转不取消、返回才取消、授权弹窗只开一次、
 *     让位/focus 有门槛且超时明确终结；
 *   - CaptureStartPolicy：重复 start 幂等、活动请求不被覆盖、陈旧启动被拒；
 *   - CaptureRequestRegistry.expireStale：授权阶段超时有界终结，不再永久 busy。
 */
public final class ReviewFixBehaviorTest {

    private static int failures = 0;

    public static void main(String[] args) {
        pageSessionSameUrlReload();
        pageSessionInvalidateOnlyMatching();
        regionNewSessionStartsAtOne();
        regionOldSessionHighRevisionRejected();
        regionUnregisteredSessionRejected();
        regionSameSessionStaleRevisionRejected();
        snapshotSurvivesDetach();
        snapshotDroppedOnlyForMatchingSession();
        consentRotationKeepsRequest();
        consentBackCancelsRequest();
        consentDialogOpenedOnce();
        consentWaitGateAndTimeout();
        startPolicyDecisions();
        consentTimeoutFreesRegistry();
        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ---- RV-02 页面会话 --------------------------------------------

    private static void pageSessionSameUrlReload() {
        PageSessionRegistry registry = new PageSessionRegistry();
        check("登记会话成功", registry.register("app", "ps-old"));
        check("当前会话匹配", registry.isCurrent("app", "ps-old"));
        // 同一个 URL reload：页面生成新会话并登记
        check("reload 后登记新会话", registry.register("app", "ps-new"));
        check("旧会话立即失效", !registry.isCurrent("app", "ps-old"));
        check("新会话有效", registry.isCurrent("app", "ps-new"));
        check("按 pageId 隔离",
                registry.register("overlay", "ps-overlay")
                        && registry.isCurrent("overlay", "ps-overlay")
                        && !registry.isCurrent("app", "ps-overlay"));
    }

    private static void pageSessionInvalidateOnlyMatching() {
        PageSessionRegistry registry = new PageSessionRegistry();
        registry.register("app", "ps-1");
        check("旧会话注销不影响新会话",
                !registry.invalidate("app", "ps-stale") && registry.isCurrent("app", "ps-1"));
        check("匹配会话注销成功", registry.invalidate("app", "ps-1"));
        check("注销后 nothing current", "".equals(registry.current("app")));
        check("注销后 isCurrent 为 false", !registry.isCurrent("app", "ps-1"));
        registry.register("app", "ps-2");
        registry.invalidate("app");
        check("无条件注销清空", "".equals(registry.current("app")));
    }

    // ---- RV-03 区域会话/revision ----------------------------------

    private static void regionNewSessionStartsAtOne() {
        RegionSessionGate gate = new RegionSessionGate();
        gate.beginSession("page-old");
        check("旧会话 revision 9999 接受",
                gate.accept("page-old", 9999L) == RegionSessionGate.Decision.ACCEPT);
        // 换页：新会话从 1 开始，不能被旧会话的高 revision 挡住
        gate.beginSession("page-new");
        check("新会话 revision 1 接受",
                gate.accept("page-new", 1L) == RegionSessionGate.Decision.ACCEPT);
        check("新会话 lastRevision 归位", gate.lastRevision() == 1L);
    }

    private static void regionOldSessionHighRevisionRejected() {
        RegionSessionGate gate = new RegionSessionGate();
        gate.beginSession("page-new");
        gate.accept("page-new", 5L);
        check("当前会话高 revision 正常",
                gate.accept("page-new", 6L) == RegionSessionGate.Decision.ACCEPT);
        // 旧页任何高 revision 都拒绝
        check("旧会话高 revision 被拒",
                gate.accept("page-old", 99999L) == RegionSessionGate.Decision.STALE_SESSION);
        check("被拒旧会话不推进当前 revision", gate.lastRevision() == 6L);
    }

    private static void regionUnregisteredSessionRejected() {
        RegionSessionGate gate = new RegionSessionGate();
        check("未登记会话不被接受",
                gate.accept("stranger", 1L) == RegionSessionGate.Decision.STALE_SESSION);
        check("空会话 id 标记未登记",
                gate.accept("", 1L) == RegionSessionGate.Decision.NOT_REGISTERED);
        gate.beginSession("page-a");
        check("注销后旧会话失效", gate.endSession("page-a") && gate.session().isEmpty());
        check("注销后旧载荷被拒",
                gate.accept("page-a", 2L) == RegionSessionGate.Decision.STALE_SESSION);
    }

    private static void regionSameSessionStaleRevisionRejected() {
        RegionSessionGate gate = new RegionSessionGate();
        gate.beginSession("page-a");
        gate.accept("page-a", 3L);
        check("同会话相同 revision 拒绝",
                gate.accept("page-a", 3L) == RegionSessionGate.Decision.STALE_REVISION);
        check("同会话更低 revision 拒绝",
                gate.accept("page-a", 2L) == RegionSessionGate.Decision.STALE_REVISION);
        check("同会话更高 revision 接受",
                gate.accept("page-a", 4L) == RegionSessionGate.Decision.ACCEPT);
    }

    // ---- RV-04 快照保留 --------------------------------------------

    private static void snapshotSurvivesDetach() {
        RegionSnapshotStore store = new RegionSnapshotStore();
        RegionSnapshotStore.Snapshot snapshot = new RegionSnapshotStore.Snapshot(
                "page-a", 7L, 100f, 200f, new float[][]{{1, 2, 3, 4}}, false);
        store.store(snapshot);
        // detach：控制器只停重试，不调用 clear/drop
        check("detach 后最新快照仍在", store.hasSnapshot() && store.latest() == snapshot);
        check("快照 revision 可重建", store.latest() != null && store.latest().revision == 7L);
    }

    private static void snapshotDroppedOnlyForMatchingSession() {
        RegionSnapshotStore store = new RegionSnapshotStore();
        store.store(new RegionSnapshotStore.Snapshot(
                "page-new", 1L, 100f, 200f, new float[][]{{1, 2, 3, 4}}, false));
        check("旧页注销不清新页快照", !store.dropIfSession("page-old") && store.hasSnapshot());
        check("换页注销丢弃匹配快照", store.dropIfSession("page-new") && !store.hasSnapshot());
    }

    // ---- RV-01 / RV-08 授权 Activity 生命周期 -----------------------

    private static void consentRotationKeepsRequest() {
        check("旋转（配置变化）保留请求",
                CaptureConsentPolicy.onDestroy(false, true)
                        == CaptureConsentPolicy.DestroyDecision.KEEP_REQUEST);
    }

    private static void consentBackCancelsRequest() {
        check("用户返回/被结束取消请求",
                CaptureConsentPolicy.onDestroy(false, false)
                        == CaptureConsentPolicy.DestroyDecision.CANCEL_REQUEST);
        check("已拿到结果什么都不做",
                CaptureConsentPolicy.onDestroy(true, false)
                        == CaptureConsentPolicy.DestroyDecision.NONE);
        check("旋转且已拿到结果什么都不做",
                CaptureConsentPolicy.onDestroy(true, true)
                        == CaptureConsentPolicy.DestroyDecision.NONE);
    }

    private static void consentDialogOpenedOnce() {
        check("首次请求授权", CaptureConsentPolicy.shouldRequestConsent(false));
        check("旋转后不重复弹授权框", !CaptureConsentPolicy.shouldRequestConsent(true));
        check("陈旧请求直接结束",
                CaptureConsentPolicy.onCreate(false)
                        == CaptureConsentPolicy.CreateDecision.FINISH_STALE);
        check("有效请求继续",
                CaptureConsentPolicy.onCreate(true)
                        == CaptureConsentPolicy.CreateDecision.PROCEED);
    }

    private static void consentWaitGateAndTimeout() {
        check("悬浮窗未移除时继续等",
                CaptureConsentPolicy.onWaitTick(true, true, true, 0, 40)
                        == CaptureConsentPolicy.WaitDecision.WAIT_OVERLAY);
        check("悬浮窗已移除但未 RESUMED/focus 时继续等",
                CaptureConsentPolicy.onWaitTick(false, true, false, 0, 40)
                        == CaptureConsentPolicy.WaitDecision.WAIT_FOCUS);
        check("让位+resumed+focus 才申请授权",
                CaptureConsentPolicy.onWaitTick(false, true, true, 0, 40)
                        == CaptureConsentPolicy.WaitDecision.REQUEST_CONSENT);
        check("超上限明确失败，而不是照样申请授权",
                CaptureConsentPolicy.onWaitTick(true, true, true, 39, 40)
                        == CaptureConsentPolicy.WaitDecision.FAIL_NO_YIELD);
        check("focus 迟迟不来也明确失败",
                CaptureConsentPolicy.onWaitTick(false, true, false, 39, 40)
                        == CaptureConsentPolicy.WaitDecision.FAIL_NO_YIELD);
    }

    // ---- RV-07 服务启动裁决 ----------------------------------------

    private static void startPolicyDecisions() {
        check("全新请求接受",
                CaptureStartPolicy.decide(false, "", "req-1", true, false)
                        == CaptureStartPolicy.Decision.ACCEPT);
        check("同 requestId 重复 start 幂等",
                CaptureStartPolicy.decide(true, "req-1", "req-1", true, true)
                        == CaptureStartPolicy.Decision.ACCEPT_IDEMPOTENT);
        check("当前 run 仍活动时拒绝覆盖",
                CaptureStartPolicy.decide(true, "req-1", "req-2", true, true)
                        == CaptureStartPolicy.Decision.REJECT_BUSY);
        check("旧 run 已取消时显式替换",
                CaptureStartPolicy.decide(true, "req-1", "req-2", true, false)
                        == CaptureStartPolicy.Decision.REPLACE_STALE);
        check("陈旧启动被拒（不释放新任务隐藏理由）",
                CaptureStartPolicy.decide(false, "", "req-old", false, false)
                        == CaptureStartPolicy.Decision.REJECT_STALE);
        check("空 requestId 视为非法",
                CaptureStartPolicy.decide(false, "", "", true, false)
                        == CaptureStartPolicy.Decision.REJECT_INVALID);
    }

    // ---- RV-08 授权阶段超时 ----------------------------------------

    private static void consentTimeoutFreesRegistry() {
        CaptureRequestRegistry registry = new CaptureRequestRegistry();
        CaptureRequestRegistry.Request request = registry.begin("APP", "page-x", 1000L);
        check("授权超时前不终结",
                registry.expireStale(CaptureRequestRegistry.STATE_CONSENT,
                        90_000L, 50_000L, "超时") == null);
        check("仍处于 busy", registry.isBusy());
        CaptureRequestRegistry.Request expired = registry.expireStale(
                CaptureRequestRegistry.STATE_CONSENT, 90_000L, 91_001L, "等待授权超时");
        check("授权超时后明确终结", expired == request);
        check("超时后不再 busy", !registry.isBusy());
        check("超时结果状态 FAILED", registry.lastResult() != null
                && CaptureRequestRegistry.STATE_FAILED.equals(registry.lastResult().state)
                && "等待授权超时".equals(registry.lastResult().error));
        // 状态不匹配时不误伤
        CaptureRequestRegistry.Request waiting = registry.begin("APP", "page-y", 200_000L);
        registry.setState(waiting.requestId, CaptureRequestRegistry.STATE_WAITING_FRAME);
        check("只对指定状态超时",
                registry.expireStale(CaptureRequestRegistry.STATE_CONSENT,
                        1000L, 999_999L, "超时") == null);
        check("WAITING_FRAME 仍活动", registry.isBusy());
        // 超时后允许开始下一次
        registry.finish(waiting.requestId, CaptureRequestRegistry.STATE_COMPLETED, "");
        check("超时后可以开始新请求",
                registry.begin("APP", "page-y", 1_000_000L) != null);
    }

    private static void check(String name, boolean condition) {
        System.out.println("[" + (condition ? "PASS" : "FAIL") + "] " + name);
        if (!condition) {
            failures++;
        }
    }
}
