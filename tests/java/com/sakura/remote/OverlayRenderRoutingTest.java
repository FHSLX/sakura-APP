package com.sakura.remote;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真机运行时显示链路修复（FIX-RUNTIME-01/02/03/05）的**真实生产逻辑**行为测试。
 *
 * 覆盖复审判定要求的四件事：
 *   1. 桥线程 → 主线程封送：从**真实后台线程**调用**真实生产 RemoteBridge**
 *      的 @JavascriptInterface 方法，断言落点（RenderSignalSink）绝不在桥线程执行，
 *      必须先入队、再由主线程接纳后才触碰 UI。
 *   2. 权威代际与重试链路：真实 PetWindow 的 load → register → retry → ready
 *      全链路；重试必须推进代际，重试之后网页送来的 boot/立绘信号必须被接纳。
 *   3. 加载期必须有界可见卡片承载预加载：真实 OverlaySurfaceHost +
 *      OverlayVisibilityController + OverlaySurface 断言卡片有界可见、
 *      绝不出现透明整窗，READY 后升级为 FULL。
 *   4. 去敏限频诊断：release 也保留 Log，但同一状态限频、且敏感原文被白名单拦掉。
 *
 * 另外专门包含「旧生产实现失败 / 修复后通过」的对照用例，见
 * {@link #oldImplementationRetryGenerationFails()}.
 *
 * 运行：tests/runtimefix_behavior_check.ps1
 */
public final class OverlayRenderRoutingTest {

    private static int failures;
    private static int checks;

    /** 本类在 JVM 主线程加载，等价于 Android 的主线程。 */
    private static final long MAIN_THREAD_ID = Thread.currentThread().getId();

    public static void main(String[] args) throws Exception {
        bridgeBackgroundThreadCallsAreMarshaledToMainThread();
        bridgeSignalsAreDeduplicatedWithoutLosingFirstDelivery();
        routerWithoutMainThreadNeverTouchesSink();
        routerDiagnosticsCarryNoSensitiveContent();
        realAdapterRetryKeepsGenerationAndReachesReady();
        realAdapterFirstLoadColdAndCacheHitBothReachReady();
        loadingWindowIsBoundedVisibleCardThenUpgradesToFull();
        diagnosticsLoggingIsRateLimitedAndSanitized();
        diagnosticsSnapshotCarriesFailureBranchAndKeepsTransitions();
        oldImplementationRetryGenerationFails();

        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " / " + checks + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (" + checks + " checks; real sources: RemoteBridge/"
                + "RenderSignalRouter/RenderSignalSink/PetWindow/RenderAvailabilityState/"
                + "OverlaySurfaceHost/OverlayVisibilityController/OverlaySurface/RuntimeDiagnostics)");
    }

    // ================= 用例 1：桥线程 → 主线程封送 =================

    private static void bridgeBackgroundThreadCallsAreMarshaledToMainThread() throws Exception {
        RecordingSink sink = new RecordingSink();
        FakePoster poster = new FakePoster(MAIN_THREAD_ID);
        RenderSignalRouter router = new RenderSignalRouter(sink, poster);
        RemoteBridge bridge = new RemoteBridge(new NoopHost());
        bridge.setRenderSignalRouterForTest(router);

        final CountDownLatch called = new CountDownLatch(1);
        final AtomicReference<String> callerThread = new AtomicReference<>("");
        Thread bridgeThread = new Thread(new Runnable() {
            @Override
            public void run() {
                callerThread.set(Thread.currentThread().getName());
                // 真实 @JavascriptInterface 调用所在的桥线程
                bridge.notifyOverlayPageSession("ps-A");
                bridge.notifyOverlayBootOk("ps-A");
                bridge.notifyOverlayPortraitVisible("ps-A");
                called.countDown();
            }
        }, "JSBridge");
        bridgeThread.start();
        if (!called.await(5, TimeUnit.SECONDS)) {
            check("桥线程调用在超时前返回", false, "timeout");
        }
        bridgeThread.join(2000);

        check("桥线程确实不是主线程", !"JSBridge".equals(Thread.currentThread().getName()),
                callerThread.get());
        check("桥线程调用后落点一次都没有执行（必须先入队）",
                sink.appliedKinds.isEmpty(), sink.appliedKinds);
        check("三个信号都已入队", poster.pending() == 3, poster.pending());
        check("路由器记录了入队类别与结果",
                RenderSignalRouter.KIND_PORTRAIT_VISIBLE.equals(router.lastSignalKind())
                        && RenderSignalRouter.RESULT_QUEUED.equals(router.lastSignalResult()),
                router.lastSignalKind() + "/" + router.lastSignalResult());

        // 主线程接纳：模拟 Android 主线程 Looper 排空
        poster.drainAllOnMainThread();

        check("三个信号都在主线程落地",
                sink.appliedKinds.size() == 3 && sink.sinkThreadIds.size() == 3, sink.appliedKinds);
        boolean allMain = true;
        for (Long id : sink.sinkThreadIds) {
            if (id == null || id != MAIN_THREAD_ID) {
                allMain = false;
            }
        }
        check("落点执行线程恒等于主线程（桥线程绝不直接改 UI）", allMain, sink.sinkThreadIds);
        check("落地顺序与调用顺序一致",
                sink.appliedKinds.get(0).equals(RenderSignalRouter.KIND_PAGE_SESSION)
                        && sink.appliedKinds.get(1).equals(RenderSignalRouter.KIND_BOOT_OK)
                        && sink.appliedKinds.get(2).equals(RenderSignalRouter.KIND_PORTRAIT_VISIBLE),
                sink.appliedKinds);
        check("会话 id 原样传递（桥方法只转发，不加工）",
                sink.pageSessionIds.get(0).equals("ps-A"), sink.pageSessionIds);
        check("路由器诊断确认全部在主线程落地",
                router.allAppliedOnMainThread() && router.appliedSignals() == 3,
                router.appliedSignals() + "/" + router.mainThreadSignals());
        check("主线程标识被记录", router.lastAppliedThreadId() == MAIN_THREAD_ID,
                router.lastAppliedThreadId());
    }

    // ================= 用例 2：去重（幂等但不丢首投）=================

    private static void bridgeSignalsAreDeduplicatedWithoutLosingFirstDelivery() {
        RecordingSink sink = new RecordingSink();
        FakePoster poster = new FakePoster(MAIN_THREAD_ID);
        RenderSignalRouter router = new RenderSignalRouter(sink, poster);
        RemoteBridge bridge = new RemoteBridge(new NoopHost());
        bridge.setRenderSignalRouterForTest(router);

        bridge.notifyOverlayPortraitVisible("ps-A");
        bridge.notifyOverlayPortraitVisible("ps-A");
        bridge.notifyOverlayPortraitVisible("ps-A");
        poster.drainAllOnMainThread();

        check("同一会话的重复 ready 只落地一次", sink.appliedKinds.size() == 1, sink.appliedKinds);
        check("重复信号被记为 deduped",
                router.duplicateSignals() == 2
                        && RenderSignalRouter.RESULT_DEDUPED.equals(router.lastSignalResult()),
                router.duplicateSignals() + "/" + router.lastSignalResult());

        // 新会话（reload）必须重新落地：不能因为去重键相同就吞掉新页面
        bridge.notifyOverlayPortraitVisible("ps-B");
        poster.drainAllOnMainThread();
        check("换会话后的 ready 仍会落地", sink.appliedKinds.size() == 2, sink.appliedKinds);
    }

    // ================= 用例 3：没有主线程语义时不触碰落点 =================

    private static void routerWithoutMainThreadNeverTouchesSink() {
        RecordingSink sink = new RecordingSink();
        // onMainThread 恒为 false：模拟封送失败（例如 Looper 未就绪）
        FakePoster poster = new FakePoster(MAIN_THREAD_ID, false);
        RenderSignalRouter router = new RenderSignalRouter(sink, poster);
        RemoteBridge bridge = new RemoteBridge(new NoopHost());
        bridge.setRenderSignalRouterForTest(router);

        bridge.notifyOverlayPortraitVisible("ps-A");
        poster.drainAllOnMainThread();

        check("封送失败时绝不触碰 UI 落点", sink.appliedKinds.isEmpty(), sink.appliedKinds);
        check("封送失败被计数（去敏）", router.applyErrors() == 1, router.applyErrors());
        check("未在主线程落地的信号不会被记为已应用",
                router.appliedSignals() == 0 && router.allAppliedOnMainThread(),
                router.appliedSignals());
    }

    // ================= 用例 4：路由器诊断不含敏感内容 =================

    private static void routerDiagnosticsCarryNoSensitiveContent() {
        RecordingSink sink = new RecordingSink();
        FakePoster poster = new FakePoster(MAIN_THREAD_ID);
        RenderSignalRouter router = new RenderSignalRouter(sink, poster);
        RemoteBridge bridge = new RemoteBridge(new NoopHost());
        bridge.setRenderSignalRouterForTest(router);

        // 会话 id 即使被塞进敏感形状，也绝不能出现在诊断 JSON 里
        bridge.notifyOverlayPortraitVisible("https://host/page?token=SUPERSECRET&chat=hello");
        poster.drainAllOnMainThread();
        String diag = router.diagnosticsFields();

        check("诊断不含完整会话 id/URL", !diag.contains("SUPERSECRET") && !diag.contains("https://"),
                diag);
        check("诊断只报告「会话是否存在」", diag.contains("\"renderSignalSessionPresent\":true"), diag);
        check("诊断含主线程状态字段",
                diag.contains("renderSignalsAllMainThread")
                        && diag.contains("renderSignalLastThreadId"), diag);
    }

    // ================= 用例 5：重试推进权威代际并到达 READY =================

    private static void realAdapterRetryKeepsGenerationAndReachesReady() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = new RenderAvailabilityState(clock, 8_000L, 6_000L,
                8_000L, RenderAvailabilityState.DEFAULT_AUTO_RETRY_BUDGET);
        PetWindow window = new PetWindow(new android.content.Context(), state);

        window.load("https://host/overlay?mode=overlay");
        long generationBefore = window.loadGeneration();
        check("首次加载有权威代际", generationBefore > 0, generationBefore);

        // 加载失败并安排了一次自动重试（替身 Handler 无延迟语义，撤销排期以观察失败态）
        window.onPageBootFailed();
        if (window.retryScheduled()) {
            window.cancelScheduledRetry();
        }
        check("失败后不再可挂载", !state.isRenderAttachable(), state.phase());

        // 真实重试路径：必须推进权威代际（旧实现在这里不推进，见对照用例）
        boolean retried = window.retryNow();
        long generationAfter = window.loadGeneration();
        check("重试被接受", retried, retried);
        check("重试推进了权威代际（状态机与 PetWindow 同一代际）",
                generationAfter == generationBefore + 1,
                generationBefore + " -> " + generationAfter);
        check("PetWindow 与状态机共享同一代际",
                window.loadGeneration() == state.generation(),
                window.loadGeneration() + "/" + state.generation());

        // 重试后的新页面送信号：必须被接纳（修复前全部因代际不匹配被丢弃）
        window.onPageBootOk();
        window.onPortraitVisible();
        check("重试后的 bootOk 被接纳", state.bootOk(), state.bootOk());
        check("重试后的立绘可见被接纳", state.visiblePortrait(), state.visiblePortrait());
        check("重试后阶段到达 READY",
                state.phase() == RenderAvailabilityState.Phase.READY, state.phase());
        check("重试后可挂载完整窗口", state.isRenderAttachable(), state.phase());

        // 迟到信号（重试之前的代际）仍必须被丢弃
        PetWindow stale = new PetWindow(new android.content.Context(), state);
        check("重试前的旧代际信号被拒绝",
                !window.onPortraitVisibleForGeneration(generationBefore), generationBefore);
        stale.destroy();
        window.destroy();
    }

    // ================= 用例 6：首次加载/冷缓存/缓存命中都能 READY =================

    private static void realAdapterFirstLoadColdAndCacheHitBothReachReady() {
        // 冷缓存：文档加载 → bootOk → 图片解码完成 → ready
        FakeClock coldClock = new FakeClock();
        RenderAvailabilityState cold = new RenderAvailabilityState(coldClock, 8_000L, 6_000L,
                8_000L, RenderAvailabilityState.DEFAULT_AUTO_RETRY_BUDGET);
        PetWindow coldWindow = new PetWindow(new android.content.Context(), cold);
        coldWindow.load("https://host/overlay?mode=overlay");
        coldWindow.onPageBootOk();
        coldWindow.onPortraitVisible();
        check("冷缓存首次加载可到达 READY（不需要先有可见窗口）",
                cold.phase() == RenderAvailabilityState.Phase.READY, cold.phase());
        coldWindow.destroy();

        // 缓存命中：同一位置立即上报 ready（图片 load 事件可能在 boot 之前）
        FakeClock hitClock = new FakeClock();
        RenderAvailabilityState hit = new RenderAvailabilityState(hitClock, 8_000L, 6_000L,
                8_000L, RenderAvailabilityState.DEFAULT_AUTO_RETRY_BUDGET);
        PetWindow hitWindow = new PetWindow(new android.content.Context(), hit);
        hitWindow.load("https://host/overlay?mode=overlay");
        hitWindow.onPortraitVisible();
        hitWindow.onPageBootOk();
        check("缓存命中（ready 早于 bootOk）也能到达 READY",
                hit.phase() == RenderAvailabilityState.Phase.READY, hit.phase());
        check("缓存命中的立绘可见状态成立", hit.visiblePortrait(), hit.visiblePortrait());
        hitWindow.destroy();
    }

    // ================= 用例 7：加载期有界可见卡片 → FULL =================

    private static void loadingWindowIsBoundedVisibleCardThenUpgradesToFull() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = new RenderAvailabilityState(clock, 8_000L, 6_000L,
                8_000L, RenderAvailabilityState.DEFAULT_AUTO_RETRY_BUDGET);
        android.content.Context context = new android.content.Context();
        PetWindow window = new PetWindow(context, state);
        RecordingWindowHost windowHost = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(windowHost);
        android.widget.FrameLayout root = new android.widget.FrameLayout(context);
        OverlaySurface surface = new OverlaySurface(context, window.view());
        surface.bindContainer(root);
        RecordingLayoutHost layoutHost = new RecordingLayoutHost();
        OverlaySurfaceHost host = new OverlaySurfaceHost(window, surface, controller, layoutHost);
        // 与 OverlayService 相同的初始化顺序：先让协调器同步「加载期允许卡片」，
        // 再宣告服务存活（否则首次裁决会是 DETACHED，见 FIX-RUNTIME-03）。
        host.renderCardNow();
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        drain();

        check("加载期期望形态是有界卡片（不是 DETACHED，也不是 FULL）",
                controller.desiredPlan() == OverlayVisibilityController.WindowPlan.CARD,
                controller.desiredPlan());
        check("加载期窗口确实以卡片挂载",
                windowHost.attached
                        && windowHost.plan == OverlayVisibilityController.WindowPlan.CARD,
                windowHost.trace);
        check("加载期卡片尺寸有界（不超过屏幕的 2/3 宽 / 400px 高）",
                surface.cardWidthPx() < 1080 * 2 / 3 && surface.cardHeightPx() < 400,
                surface.cardWidthPx() + "x" + surface.cardHeightPx());

        // ---- 真 parent/attach 断言（旧实现 removeAllViews 会摘掉页面视图）----
        // 模拟窗口被 WindowManager 挂上：根 View attached。替身的
        // isAttachedToWindow 已改为沿父链继承，孤立子 View 不再被误判为已 attach。
        root.attached = true;
        check("CARD 时页面视图仍在 root 视图树里（真实 parent，不是孤立子 View）",
                window.view().getParent() == root, window.view().getParent());
        check("CARD 时 WebView 沿父链判定为已 attach",
                window.webView().isAttachedToWindow(), window.webView().getParent());
        check("CARD 时卡片层挂在 root 上且位于页面之上",
                surface.cardViewForTest().getParent() == root
                        && root.indexOfChild(surface.cardViewForTest())
                        > root.indexOfChild(window.view()),
                root.getChildCount());

        // 立绘ready：升级成完整窗口（先加载，再送 bootOk/可见信号）
        window.load("https://host/overlay?mode=overlay");
        check("加载期 WebView 可见（预加载必须能推进）",
                window.webView().getVisibility() == android.view.View.VISIBLE,
                window.webView().getVisibility());
        window.onPageBootOk();
        window.onPortraitVisible();
        drain();
        check("立绘可见后升级为 FULL",
                windowHost.attached
                        && windowHost.plan == OverlayVisibilityController.WindowPlan.FULL,
                windowHost.trace);
        check("升级后内容是完整页面而不是卡片", !host.cardVisible(), surface.mode());
        check("FULL 只移除卡片层：页面仍在 root 且已 attach（无重复 parent/无替换）",
                surface.cardViewForTest().getParent() == null
                        && window.view().getParent() == root
                        && window.webView().isAttachedToWindow()
                        && root.getChildCount() == 1,
                root.getChildCount());

        // 用户关闭必须优先于渲染信号
        controller.setUserEnabled(false);
        drain();
        check("用户关闭后窗口被移除（卡片也不能留下）", !windowHost.attached, windowHost.trace);

        // 截图隐藏同样优先
        controller.setUserEnabled(true);
        drain();
        controller.acquireCaptureHidden("cap-1");
        drain();
        check("截图隐藏期间窗口移除", !windowHost.attached, windowHost.trace);
        controller.releaseCaptureHidden("cap-1");
        drain();
        check("释放截图隐藏后回到 FULL",
                windowHost.attached
                        && windowHost.plan == OverlayVisibilityController.WindowPlan.FULL,
                windowHost.trace);

        window.destroy();
    }

    // ================= 用例 8：去敏限频 Log =================

    private static void diagnosticsLoggingIsRateLimitedAndSanitized() {
        android.util.Log.resetForTest();
        FakeClock clock = new FakeClock();
        RuntimeDiagnostics diag = new RuntimeDiagnostics(clock, 1_000L, 5, 10_000L);

        diag.renderPhaseChanged("LOADING", "", true, true, true);
        int afterFirst = android.util.Log.lines;
        check("首个状态输出一条日志", afterFirst == 1, afterFirst);

        // 同一限频窗口内重复输出：被抑制
        diag.renderPhaseChanged("LOADING", "", true, true, true);
        diag.renderPhaseChanged("LOADING", "", true, true, true);
        check("同一键在限频窗口内不重复输出", android.util.Log.lines == afterFirst,
                android.util.Log.lines);
        check("被抑制的条数被计数", diag.suppressed() == 2, diag.suppressed());

        // 超过窗口后允许再输出一次（内容真的变化）
        clock.advance(1_500L);
        diag.renderPhaseChanged("WARNING", "portrait_missing", true, true, true);
        check("窗口后允许输出新的阶段", android.util.Log.lines == 2, android.util.Log.lines);

        // 窗口内总条数上限：连续换键也不能刷屏（用一个很长窗口隔离「上限」本身）
        android.util.Log.resetForTest();
        FakeClock capClock = new FakeClock();
        RuntimeDiagnostics capped = new RuntimeDiagnostics(capClock, 0L, 5, 3_600_000L);
        for (int i = 0; i < 20; i++) {
            capped.renderSignal(RenderSignalRouter.KIND_BOOT_OK, true, false, true, "");
        }
        check("窗口内总条数受上限约束（不刷屏）", android.util.Log.lines == 5,
                android.util.Log.lines);
        check("超出上限的部分被计为抑制", capped.suppressed() == 15, capped.suppressed());

        // 白名单：非稳定代码一律替换，绝不把任意字符串写进 logcat
        android.util.Log.resetForTest();
        RuntimeDiagnostics strict = new RuntimeDiagnostics(clock, 0L, 5, 10_000L);
        strict.renderSignal("token=SUPERSECRET", true, false, true,
                "https://host/page?token=SUPERSECRET");
        check("非法信号类别被拒绝输出", android.util.Log.lines == 0, android.util.Log.lines);
        strict.renderPhaseChanged("LOADING", "https://host/page?token=SUPERSECRET",
                true, true, true);
        check("非白名单失败码被替换为 unknown_code",
                android.util.Log.lastMessage != null
                        && android.util.Log.lastMessage.contains("reason=unknown_code")
                        && !android.util.Log.lastMessage.contains("SUPERSECRET"),
                android.util.Log.lastMessage);
        check("日志里不含 URL 片段", !android.util.Log.lastMessage.contains("https://"),
                android.util.Log.lastMessage);
    }

    // ============ 用例 9：失败分支去敏快照（网络/HTTP/JS/重试）============

    /**
     * 现场只有「卡片 + 连接失败」文案时无法判断失败分支。快照必须白名单写出
     * 网络码/HTTP 状态/JS 类型与行号/重试进度/形态，且关键状态转换不被限频吞掉。
     */
    private static void diagnosticsSnapshotCarriesFailureBranchAndKeepsTransitions() {
        android.util.Log.resetForTest();
        FakeClock clock = new FakeClock();
        RuntimeDiagnostics diag = new RuntimeDiagnostics(clock, 1_000L, 40, 10_000L);

        PetWindow.Diagnostics net = new PetWindow.Diagnostics(
                "WARNING", RenderAvailabilityState.REASON_LOAD_FAILED, "ERROR", "app.js",
                false, true, true, false, true, 3, -2, 1, 404, 2, 42, 4, 6, -1, 7L);
        diag.renderSnapshot(net, true, "card");
        int afterFirst = android.util.Log.lines;
        check("快照写出失败分支码（网络/HTTP/JS/重试）",
                afterFirst == 1 && android.util.Log.lastMessage.contains("net=3")
                        && android.util.Log.lastMessage.contains("lastNet=-2")
                        && android.util.Log.lastMessage.contains("lastHttp=404")
                        && android.util.Log.lastMessage.contains("lastJs=ERROR")
                        && android.util.Log.lastMessage.contains("retry=4/6"),
                android.util.Log.lastMessage);

        diag.renderSnapshot(net, true, "card");
        check("同一阶段/分类的重复快照被限频", android.util.Log.lines == afterFirst,
                android.util.Log.lines);

        PetWindow.Diagnostics failed = new PetWindow.Diagnostics(
                "FAILED", RenderAvailabilityState.REASON_RETRY_EXHAUSTED, "", "",
                false, true, false, false, true, 7, -2, 0, -1, 0, -1, 6, 6, -1, 9L);
        diag.renderSnapshot(failed, true, "card");
        check("失败分类转换在限频窗口内仍然输出（关键转换不被吞掉）",
                android.util.Log.lines == afterFirst + 1
                        && android.util.Log.lastMessage.contains("phase=FAILED")
                        && android.util.Log.lastMessage.contains("reason=retry_exhausted"),
                android.util.Log.lastMessage);

        PetWindow.Diagnostics hostile = new PetWindow.Diagnostics(
                "LOADING", "https://host/page?token=SUPERSECRET", "TRACE", "../../evil/secret.js",
                false, true, false, false, false, 0, -1, 0, -1, 0, -1, 0, 6, -1, 1L);
        diag.renderSnapshot(hostile, false, "token=SUPERSECRET");
        String last = android.util.Log.lastMessage;
        check("非白名单失败码/类型/来源/形态被替换",
                last.contains("reason=unknown_code") && last.contains("lastJs=unknown_type")
                        && last.contains("jsSource=unknown_source") && last.contains("plan=unknown_plan"),
                last);
        check("快照里绝不含敏感原文",
                !last.contains("SUPERSECRET") && !last.contains("https://")
                        && !last.contains("evil") && !last.contains("?token="),
                last);
    }

    // ================= 对照用例：旧生产实现必须失败 =================

    /**
     * **旧生产实现的确定性失败模式**（对照，证明本轮修的是真缺陷）。
     *
     * 旧代码在重试时只递增 PetWindow 自己的 loadGeneration，状态机代际不动；
     * 于是重试后的 bootOk/portraitVisible 因代际不匹配被状态机丢弃，
     * 窗口永远停在卡片（现场就是「页面加载了、立绘不显示」）。
     *
     * 这里用一个"旧式"计数器复现同一场景：只要两者不同步，
     * 重试后的 ready 必然被拒。修复后的真实实现已在用例 5 中通过。
     */
    private static void oldImplementationRetryGenerationFails() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = new RenderAvailabilityState(clock, 8_000L, 6_000L,
                8_000L, RenderAvailabilityState.DEFAULT_AUTO_RETRY_BUDGET);
        state.generation();   // 状态机当前代际

        // 旧实现的 PetWindow 私有计数器（重试时自增，不通知状态机）
        long oldStyleWindowCounter = 0L;
        oldStyleWindowCounter++;                 // load()
        state.onSessionStarted();                // load() 里状态机推进一次

        // 失败并重试：旧实现只动自己的计数器
        oldStyleWindowCounter++;
        state.onRetryRequested(false);           // 状态机接纳重试，但代际不变

        // 重试后的新页面送 ready：旧实现把状态机代际当作期望值之外的数字
        state.onBootOk(oldStyleWindowCounter);   // 旧实现会传这个数字
        state.onPortraitVisible(oldStyleWindowCounter);

        check("旧实现：重试后 bootOk 被代际不匹配丢弃", !state.bootOk(), state.bootOk());
        check("旧实现：重试后立绘可见被丢弃", !state.visiblePortrait(), state.visiblePortrait());
        check("旧实现：窗口永远升不到 READY",
                state.phase() != RenderAvailabilityState.Phase.READY, state.phase());

        // 修复后的语义：以状态机代际为准，信号立即被接纳
        state.onBootOk(state.generation());
        state.onPortraitVisible(state.generation());
        check("修复后：同一场景以权威代际送达即到达 READY",
                state.phase() == RenderAvailabilityState.Phase.READY, state.phase());
    }

    // ================= 公共替身 =================

    private static void check(String name, boolean condition, Object actual) {
        checks++;
        if (condition) {
            System.out.println("[PASS] " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name + " -> " + actual);
        }
    }

    private static void drain() {
        for (int i = 0; i < 20000; i++) {
            if (!Handler.drainOne()) {
                return;
            }
        }
    }

    private static final class FakeClock implements RenderAvailabilityState.Clock,
            RuntimeDiagnostics.Clock {
        long now = 1_000_000L;

        @Override
        public long now() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /**
     * 替身 Poster：把入队与「主线程执行」两个时刻分开。
     *
     * 关键点：{@link #drainAllOnMainThread()} 在**测试主线程**执行队列，
     * 因此在调用线程（JSBridge）上 sink 绝不会被执行——这正是要断言的语义。
     */
    private static final class FakePoster implements RenderSignalRouter.Poster {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        private final long mainThreadId;
        private final boolean mainThreadSemantics;

        FakePoster(long mainThreadId) {
            this(mainThreadId, true);
        }

        FakePoster(long mainThreadId, boolean mainThreadSemantics) {
            this.mainThreadId = mainThreadId;
            this.mainThreadSemantics = mainThreadSemantics;
        }

        @Override
        public void post(Runnable task) {
            queue.addLast(task);
        }

        @Override
        public boolean onMainThread() {
            return mainThreadSemantics && Thread.currentThread().getId() == mainThreadId;
        }

        @Override
        public long mainThreadId() {
            return mainThreadId;
        }

        int pending() {
            return queue.size();
        }

        void drainAllOnMainThread() {
            while (!queue.isEmpty()) {
                queue.pollFirst().run();
            }
        }
    }

    /** 记录落点执行线程与类别的 RenderSignalSink 替身。 */
    private static final class RecordingSink implements RenderSignalSink {
        final List<String> appliedKinds = new ArrayList<>();
        final List<Long> sinkThreadIds = new ArrayList<>();
        final List<String> pageSessionIds = new ArrayList<>();

        @Override
        public void onRenderPageSession(String pageSessionId) {
            record(RenderSignalRouter.KIND_PAGE_SESSION, pageSessionId);
        }

        @Override
        public void onRenderBootOk(String pageSessionId) {
            record(RenderSignalRouter.KIND_BOOT_OK, pageSessionId);
        }

        @Override
        public void onRenderBootFailed(String pageSessionId) {
            record(RenderSignalRouter.KIND_BOOT_FAILED, pageSessionId);
        }

        @Override
        public void onRenderPortraitVisible(String pageSessionId) {
            record(RenderSignalRouter.KIND_PORTRAIT_VISIBLE, pageSessionId);
        }

        @Override
        public void onInvalidatePageSession(String pageSessionId) {
            record(RenderSignalRouter.KIND_INVALIDATE, pageSessionId);
        }

        private void record(String kind, String sessionId) {
            appliedKinds.add(kind);
            sinkThreadIds.add(Thread.currentThread().getId());
            pageSessionIds.add(sessionId);
        }
    }

    /** RemoteBridge 其余宿主能力的最小替身（本测试只走渲染信号）。 */
    private static final class NoopHost implements RemoteBridge.Host {
        @Override
        public void loadRemote(String url) {
        }

        @Override
        public void setPetScale(float scale) {
        }

        @Override
        public float getPetScale() {
            return 1f;
        }

        @Override
        public boolean isOverlayRunning() {
            return false;
        }

        @Override
        public boolean startOverlay() {
            return false;
        }

        @Override
        public void stopOverlay() {
        }

        @Override
        public void requestBatteryExemption() {
        }

        @Override
        public boolean isIgnoringBatteryOptimizations() {
            return false;
        }

        @Override
        public void saveServer(String server, String token) {
        }

        @Override
        public void rememberRemoteUrl(String url, boolean overlayPage) {
        }

        @Override
        public void enterOverlayMode() {
        }

        @Override
        public void setKeyboardOpen(boolean open) {
        }

        @Override
        public void setBubbleMode(boolean bubble) {
        }

        @Override
        public void setTouchable(boolean touchable) {
        }

        @Override
        public void setDragging(boolean dragging) {
        }

        @Override
        public void moveWindowBy(float dx, float dy) {
        }

        @Override
        public void setWindowPositionPx(float x, float y) {
        }

        @Override
        public String overlayLayoutInfo() {
            return "{}";
        }

        @Override
        public void setWindowSizePx(float width, float height) {
        }

        @Override
        public void requestScreenshot(String pageSessionId) {
        }

        @Override
        public void requestScreenPermission(String pageSessionId) {
        }

        @Override
        public boolean hasScreenPermission() {
            return false;
        }

        @Override
        public void deliverScreenshot(String dataUrl, String error) {
        }

        @Override
        public void registerPageSession(String pageSessionId) {
        }

        @Override
        public void invalidatePageSession(String pageSessionId) {
        }

        @Override
        public String petStatus() {
            return "{}";
        }

        @Override
        public String setTouchRegion(String json) {
            return "unsupported";
        }

        @Override
        public void clearTouchRegion() {
        }

        @Override
        public boolean isTouchRegionSupported() {
            return false;
        }

        @Override
        public void setPassThroughEnabled(boolean enabled) {
        }

        @Override
        public String touchRegionStatus() {
            return "{}";
        }

        @Override
        public String renderDiagnostics() {
            return "{}";
        }
    }

    /** 记录 attach/detach 与形态的可见性宿主替身。 */
    private static final class RecordingWindowHost implements OverlayVisibilityController.Host {
        boolean attached;
        OverlayVisibilityController.WindowPlan plan;
        final List<String> trace = new ArrayList<>();

        @Override
        public boolean isWindowAttached() {
            return attached;
        }

        @Override
        public OverlayVisibilityController.WindowPlan attachedPlan() {
            return attached ? plan : null;
        }

        @Override
        public boolean attachWindow(OverlayVisibilityController.WindowPlan target) {
            attached = true;
            plan = target;
            trace.add("attach:" + target);
            return true;
        }

        @Override
        public void detachWindow() {
            attached = false;
            plan = null;
            trace.add("detach");
        }
    }

    private static final class RecordingLayoutHost implements OverlaySurfaceHost.LayoutHost {
        final List<OverlaySurface.Mode> shapes = new ArrayList<>();

        @Override
        public boolean applyShape(OverlaySurface.Mode mode) {
            shapes.add(mode);
            return true;
        }

        @Override
        public void requestStopOverlay() {
        }
    }
}
