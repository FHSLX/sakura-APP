package com.sakura.remote;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * 空白悬浮窗修复（FIX-BLANK-01 / FIX-BLANK-02）的**真实生产逻辑**行为测试。
 *
 * 与 RealAdapterBehaviorTest 的关系：
 *   - 同样编译并实例化**真实生产类**（{@link OverlayVisibilityController}、
 *     {@link RenderAvailabilityState}、{@link PetWindow}、{@link OverlaySurface}、
 *     {@link OverlaySurfaceHost}），Android 界面/WebView 接口由
 *     tests/androidstub-overlay 的最小替身提供；
 *   - 测试里**没有**复制任何生产控制流：失败升降级、重试预算、代际丢弃、
 *     卡片形态与窗口挂载全部由真实类执行，测试只驱动它们的公开回调并断言结果。
 *
 * 覆盖方案要求的生产故障用例：
 *   加载失败 → 恢复、HTTP 拒绝、JS 未 ready、图片失败 → 恢复、
 *   截图隐藏/用户关闭期间的迟到恢复信号、重复事件、旧代际事件。
 *
 * 运行：tests/blank_overlay_real_adapter_check.ps1
 */
public final class BlankOverlayRealAdapterTest {

    private static int failures;
    private static int checks;

    public static void main(String[] args) {
        // ---- 纯状态机 ----
        stateMachineReadyOnlyOnVisiblePortrait();
        stateMachineWatchdogEscalatesToCardAndRecovers();
        stateMachineStaleGenerationIsDropped();
        stateMachineManualRetryResetsBudget();

        // ---- 可见性控制器（窗口挂载裁决）----
        controllerNeverAttachesWhileRenderNotReady();
        controllerDetachesImmediatelyWhenRenderBecomesUnavailable();
        controllerCardIsBoundedAndNeverFullWhileLoading();
        controllerHiddenReasonsBeatLateRenderRecovery();
        controllerUserDisabledIsNotUndoneByReadySignal();
        controllerCaptureHiddenOwnerSemanticsPreserved();

        // ---- 生产适配层：PetWindow + OverlaySurfaceHost ----
        adapterLoadFailureDetachesAndExhaustsBudget();
        adapterHttpRejectionIsFailureNotFullWindow();
        adapterJsNotReadyStaysBoundedCard();
        adapterPortraitFailureThenRecoveryReattaches();
        adapterRepeatedPortraitSignalsAreIdempotent();
        adapterStalePageEventsCannotRestoreNewWindow();
        adapterUserCloseDuringRecoveryKeepsWindowDetached();
        adapterCaptureHiddenDuringRecoveryRespectsOwner();
        adapterDiagnosticsAreSanitized();
        adapterCardKeepsPageAttachedUnderCard();
        adapterDiagnosticsCarryFailureBranchCodes();

        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " / " + checks + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (" + checks + " checks; real sources: "
                + "OverlayVisibilityController/RenderAvailabilityState/PetWindow/OverlaySurface/OverlaySurfaceHost)");
    }

    // ================= 公共替身与工具 =================

    private static void check(String name, boolean condition, Object actual) {
        checks++;
        if (condition) {
            System.out.println("[PASS] " + name);
        } else {
            failures++;
            System.out.println("[FAIL] " + name + " -> " + actual);
        }
    }

    /** 可控时钟：测试显式推进，不 sleep。 */
    private static final class FakeClock implements RenderAvailabilityState.Clock {
        long now = 1_000_000L;

        @Override
        public long now() {
            return now;
        }

        void advance(long ms) {
            now += ms;
        }
    }

    /** 记录 attach/detach 与形态变化的 VisibilityController 宿主替身。 */
    private static final class RecordingWindowHost implements OverlayVisibilityController.Host {
        boolean attached;
        OverlayVisibilityController.WindowPlan plan;
        int attachCalls;
        int detachCalls;
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
            attachCalls++;
            attached = true;
            plan = target;
            trace.add("attach:" + target);
            return true;
        }

        @Override
        public void detachWindow() {
            detachCalls++;
            attached = false;
            plan = null;
            trace.add("detach");
        }
    }

    /** OverlaySurfaceHost 的 LayoutHost 替身：记录形态请求，不碰真实 WindowManager。 */
    private static final class RecordingLayoutHost implements OverlaySurfaceHost.LayoutHost {
        final List<OverlaySurface.Mode> shapes = new ArrayList<>();
        int stopCalls;

        @Override
        public boolean applyShape(OverlaySurface.Mode mode) {
            shapes.add(mode);
            return true;
        }

        @Override
        public void requestStopOverlay() {
            stopCalls++;
        }
    }

    private static RenderAvailabilityState state(FakeClock clock) {
        return new RenderAvailabilityState(clock, 8_000L, 6_000L, 8_000L, 6);
    }

    /** 窗口当前是否以「完整透明窗口」形态挂载（本轮要彻底消除的状态）。 */
    private static boolean isFullWindow(Adapter adapter) {
        return adapter.host.attached
                && adapter.host.plan == OverlayVisibilityController.WindowPlan.FULL;
    }

    private static void drain() {
        Handler.drainAll();
    }

    // ================= 1. 纯状态机 =================

    private static void stateMachineReadyOnlyOnVisiblePortrait() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = state(clock);
        state.onSessionStarted();
        check("新会话默认不可挂完整窗口", !state.isRenderAttachable(), state.phase());
        state.onBootOk(state.generation());
        check("boot 完成但立绘未确认时仍不可挂完整窗口", !state.isRenderAttachable(), state.phase());
        state.onPortraitVisible(state.generation());
        check("立绘可见后允许挂完整窗口", state.isRenderAttachable(), state.phase());
        check("立绘可见后阶段为 READY", state.phase() == RenderAvailabilityState.Phase.READY, state.phase());
    }

    private static void stateMachineWatchdogEscalatesToCardAndRecovers() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = state(clock);
        state.onSessionStarted();
        clock.advance(8_000L);
        state.tick();
        check("8 秒没有可见内容 → WARNING（有界卡片）",
                state.phase() == RenderAvailabilityState.Phase.WARNING, state.phase());
        check("WARNING 仍不可挂完整窗口", !state.isRenderAttachable(), state.phase());
        clock.advance(6_000L);
        state.tick();
        check("再 6 秒 → AUTO_RECOVERY",
                state.phase() == RenderAvailabilityState.Phase.AUTO_RECOVERY, state.phase());
        check("AUTO_RECOVERY 原因标记立绘缺失",
                RenderAvailabilityState.REASON_PORTRAIT_MISSING.equals(state.reason()), state.reason());
        clock.advance(8_000L);
        state.tick();
        check("恢复到点后暴露「该自动重试」", state.autoRetryPending(), state.autoRetryPending());
        check("恢复期间仍然不可挂完整窗口", !state.isRenderAttachable(), state.phase());
    }

    private static void stateMachineStaleGenerationIsDropped() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = state(clock);
        state.onSessionStarted();
        long oldGeneration = state.generation();
        state.onSessionStarted();      // 新页面
        state.onPortraitVisible(oldGeneration);   // 旧页面迟到信号
        check("旧代际的 ready 不能恢复新页面", !state.isRenderAttachable(), state.phase());
        state.onPortraitVisible(state.generation());
        check("当前代际的 ready 仍然生效", state.isRenderAttachable(), state.phase());
    }

    private static void stateMachineManualRetryResetsBudget() {
        FakeClock clock = new FakeClock();
        RenderAvailabilityState state = state(clock);
        state.onSessionStarted();
        // 耗光自动预算
        for (int i = 0; i < 6; i++) {
            state.onRetryRequested(false);
            state.onLoadFailed(state.generation(), RenderAvailabilityState.REASON_LOAD_FAILED);
        }
        check("自动重试耗尽后拒绝再次自动重试",
                !state.onRetryRequested(false), state.phase());
        check("耗尽后阶段为 FAILED",
                state.phase() == RenderAvailabilityState.Phase.FAILED, state.phase());
        check("手动重试仍然被接受", state.onRetryRequested(true), state.phase());
        check("手动重试重置预算后可再自动重试", state.onRetryRequested(false), state.phase());
    }

    // ================= 2. 可见性控制器 =================

    private static void controllerNeverAttachesWhileRenderNotReady() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        controller.setRenderAttachable(true);   // 需要先可挂载
        drain();
        check("可挂载时挂完整窗口", host.attached && host.plan == OverlayVisibilityController.WindowPlan.FULL,
                host.trace);

        controller.setRenderAttachable(false);
        drain();
        check("渲染不可用后窗口被移除（不是隐藏视图）", !host.attached, host.trace);
        check("移除后不再残留形态", host.plan == null, host.plan);
    }

    private static void controllerDetachesImmediatelyWhenRenderBecomesUnavailable() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        controller.setRenderAttachable(true);
        drain();
        int detachesBefore = host.detachCalls;

        controller.setRenderAttachable(false);
        drain();
        check("渲染转不可用时恰好一次 detach",
                host.detachCalls == detachesBefore + 1, host.detachCalls);
        check("detach 之后窗口确实不在 WindowManager 上", !host.attached, host.attached);
    }

    private static void controllerCardIsBoundedAndNeverFullWhileLoading() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        // 渲染不可用且未允许卡片形态：窗口必须完全移除，不留下任何形态
        check("渲染不可用且未允许卡片时期望 DETACHED",
                controller.desiredPlan() == OverlayVisibilityController.WindowPlan.DETACHED,
                controller.desiredPlan());
        controller.reconcile();
        drain();
        check("未允许卡片时不挂任何窗口", !host.attached, host.trace);

        // FIX-RUNTIME-03：加载期也允许**有界可见卡片**（承载预加载），
        // 但绝不允许 FULL —— 这是「不能形成等可见才挂载死循环」与
        // 「不能透明整窗拦点击」两条约束的交点。
        controller.setCardAllowed(true);
        drain();
        check("加载期挂载的是有界 CARD 而不是 FULL",
                host.attached && host.plan == OverlayVisibilityController.WindowPlan.CARD,
                host.trace);

        controller.setRenderAttachable(true);
        drain();
        check("确认可见立绘后升级为 FULL",
                host.attached && host.plan == OverlayVisibilityController.WindowPlan.FULL, host.trace);
        check("恢复控件理由不影响渲染可用裁决", controller.isRenderAttachable(),
                controller.isRenderAttachable());
    }

    private static void controllerHiddenReasonsBeatLateRenderRecovery() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        controller.setRenderAttachable(true);
        drain();

        controller.acquireCaptureHidden("req-1");
        drain();
        check("截图隐藏立即移除窗口", !host.attached, host.trace);
        check("截图隐藏期间期望 DETACHED",
                controller.desiredPlan() == OverlayVisibilityController.WindowPlan.DETACHED,
                controller.desiredPlan());

        // 截图期间渲染可能再次上报可用：不得因此把窗口挂回来
        controller.setRenderAttachable(false);
        controller.setRenderAttachable(true);
        drain();
        check("截图隐藏期间迟到 ready 不能恢复窗口", !host.attached, host.trace);

        controller.releaseCaptureHidden("req-1");
        drain();
        check("释放截图隐藏后按当前渲染状态恢复 FULL",
                host.attached && host.plan == OverlayVisibilityController.WindowPlan.FULL, host.trace);
    }

    private static void controllerUserDisabledIsNotUndoneByReadySignal() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setRenderAttachable(true);
        controller.setUserEnabled(false);
        drain();
        check("用户关闭后不挂窗口", !host.attached, host.trace);

        controller.setRenderAttachable(true);
        drain();
        check("用户关闭期间 ready 不能重开窗口", !host.attached, host.trace);
        check("用户关闭时 shouldAttach 为 false", !controller.shouldAttach(), controller.shouldAttach());
    }

    private static void controllerCaptureHiddenOwnerSemanticsPreserved() {
        RecordingWindowHost host = new RecordingWindowHost();
        OverlayVisibilityController controller = new OverlayVisibilityController(host);
        controller.setServiceAlive(true);
        controller.setUserEnabled(true);
        controller.acquireCaptureHidden("B");
        drain();
        // 旧任务 A 结束时释放自己：不能清掉 B 的隐藏理由
        controller.releaseCaptureHidden("A");
        check("旧 token 释放无效", controller.isCaptureHidden(), controller.captureHiddenOwner());
        controller.releaseCaptureHidden("B");
        check("持有者释放有效", !controller.isCaptureHidden(), controller.captureHiddenOwner());
    }

    // ================= 3. 生产适配层 =================

    /** 生产 PetWindow + 真实控制器 + 真实形态协调器。 */
    private static final class Adapter {
        final FakeClock clock = new FakeClock();
        final Context context = new Context();
        final RecordingWindowHost host = new RecordingWindowHost();
        final OverlayVisibilityController controller = new OverlayVisibilityController(host);
        final RenderAvailabilityState state = state(clock);
        final PetWindow petWindow = new PetWindow(context, state);
        final FrameLayout rootView = new FrameLayout(context);
        final OverlaySurface surface = new OverlaySurface(context, petWindow.view());
        final RecordingLayoutHost layoutHost = new RecordingLayoutHost();
        final OverlaySurfaceHost surfaceHost;
        /** 当前 WebView 加载代际（旧事件判别用）。 */
        long loadGeneration;

        Adapter() {
            surface.bindContainer(rootView);
            controller.setServiceAlive(true);
            controller.setUserEnabled(true);
            surfaceHost = new OverlaySurfaceHost(petWindow, surface, controller, layoutHost);
            drain();
        }

        WebViewClient client() {
            return petWindow.webView().clientForTest();
        }

        WebView webView() {
            return petWindow.webView();
        }

        void syncLoadGeneration() {
            loadGeneration = petWindow.loadGeneration();
        }

        /** 加载页面并记录本次加载代际（旧事件判别基准）。 */
        void load(String url) {
            petWindow.load(url);
            syncLoadGeneration();
            drain();
        }

        /**
         * 注入一次主文档网络失败。
         *
         * 真实设备上失败后要等 {@link PetWindow#RETRY_MS} 才重试；替身 Handler 没有
         * 延迟语义，若直接排空队列会把自动重试也执行掉、立刻回到 LOADING，
         * 观察不到失败状态。这里在回调后撤销刚排期的重试，并把窗口裁决任务
         * 留给调用方的 drain()，从而把「安排重试」与「执行重试」分开。
         */
        void failMainFrame(int netErrorCode) {
            client().onReceivedError(webView(),
                    new WebResourceRequest(true), new WebResourceError(netErrorCode));
            if (petWindow.retryScheduled()) {
                petWindow.cancelScheduledRetry();
            }
            drain();
        }

        void failSubresource(int netErrorCode) {
            client().onReceivedError(webView(),
                    new WebResourceRequest(false), new WebResourceError(netErrorCode));
            drain();
        }

        void httpReject(int status) {
            client().onReceivedHttpError(webView(),
                    new WebResourceRequest(true), new WebResourceResponse(status));
            if (petWindow.retryScheduled()) {
                petWindow.cancelScheduledRetry();
            }
            drain();
        }

        void pageStarted() {
            client().onPageStarted(webView(), "https://host/page?token=secret", null);
            drain();
        }

        void pageFinished() {
            client().onPageFinished(webView(), "https://host/page?token=secret");
            drain();
        }

        void portraitVisible() {
            // 真实设备上「立即恢复」与「自动重试到点」不会同时发生：替身没有延迟，
            // 这里先撤销待执行的重试，避免它把刚恢复的窗口又重启掉。
            if (petWindow.retryScheduled()) {
                petWindow.cancelScheduledRetry();
            }
            petWindow.onPortraitVisible();
            drain();
        }

        /** 立绘恢复：boot 就绪 + 确认可见。 */
        void recoverPortrait() {
            if (petWindow.retryScheduled()) {
                petWindow.cancelScheduledRetry();
            }
            petWindow.onPageBootOk();
            petWindow.onPortraitVisible();
            drain();
        }
    }

    private static void adapterLoadFailureDetachesAndExhaustsBudget() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        boolean attachedInitially = adapter.host.attached;
        check("加载开始阶段绝不挂完整窗口", !isFullWindow(adapter), adapter.host.trace);
        check("加载开始不挂任何透明窗口或只挂有界卡片",
                !adapter.host.attached || adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD,
                adapter.host.trace);

        adapter.pageStarted();
        adapter.failMainFrame(-2);   // ERR_INTERNET_DISCONNECTED 一类的网络失败
        check("主文档失败后不再有完整透明窗口",
                !isFullWindow(adapter), adapter.host.trace);
        check("失败原因分类为主文档加载失败",
                RenderAvailabilityState.REASON_LOAD_FAILED.equals(adapter.state.reason()),
                adapter.state.reason());

        // 超过自动重试预算的失败次数：最后一轮预算耗尽，停在恢复卡片
        for (int i = 0; i < PetWindow.MAX_RETRIES; i++) {
            adapter.failMainFrame(-2);
        }
        check("重试预算耗尽后阶段 FAILED",
                adapter.state.phase() == RenderAvailabilityState.Phase.FAILED, adapter.state.phase());
        check("预算耗尽后不再有完整透明窗口", !isFullWindow(adapter), adapter.host.trace);
        check("预算耗尽后有界恢复卡片可见", adapter.surfaceHost.cardVisible()
                && adapter.host.attached
                && adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD,
                adapter.host.trace);
        check("恢复卡片宽度有界（不超过屏幕宽度的 2/3）",
                adapter.surface.cardWidthPx() < 1080 * 2 / 3, adapter.surface.cardWidthPx());
        check("恢复卡片高度有界", adapter.surface.cardHeightPx() < 400, adapter.surface.cardHeightPx());
    }

    private static void adapterHttpRejectionIsFailureNotFullWindow() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.httpReject(404);
        check("主文档 404 后不再有完整透明窗口", !isFullWindow(adapter), adapter.host.trace);
        check("HTTP 拒绝被识别", adapter.state.reason() != null
                && !RenderAvailabilityState.REASON_NONE.equals(adapter.state.reason()),
                adapter.state.reason());
        check("诊断记录 HTTP 状态 404",
                adapter.petWindow.diagnosticsSnapshot().contains("\"lastHttpStatus\":404"),
                adapter.petWindow.diagnosticsSnapshot());
        check("404 时仍是卡片而不是透明整窗",
                adapter.surfaceHost.cardVisible()
                        && (!adapter.host.attached
                        || adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD),
                adapter.host.trace);
    }

    private static void adapterJsNotReadyStaysBoundedCard() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.pageFinished();      // 文档 200 完成，但页面 JS 从未 ready
        check("文档完成但 JS 未 ready：不挂完整窗口",
                !adapter.host.attached
                        || adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD,
                adapter.host.trace);
        adapter.clock.advance(8_000L);
        adapter.petWindow.tick();
        drain();
        check("JS 未 ready 超时进入 WARNING（明确状态卡片）",
                adapter.state.phase() == RenderAvailabilityState.Phase.WARNING, adapter.state.phase());
        check("JS 未 ready 时没有透明大窗",
                !adapter.host.attached
                        || adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD,
                adapter.host.trace);
        check("卡片文案明确（不是空白）",
                adapter.surfaceHost.cardTitleForTest() != null
                        && adapter.surfaceHost.cardTitleForTest().length() > 0,
                adapter.surfaceHost.cardTitleForTest());
    }

    private static void adapterPortraitFailureThenRecoveryReattaches() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.pageFinished();
        // 图片失败：子资源错误 + 网页上报 boot 失败（没有可见旧图）
        adapter.failSubresource(-2);
        adapter.petWindow.onPageBootFailed();
        drain();
        check("立绘失败后不再有完整透明窗口", !isFullWindow(adapter), adapter.host.trace);
        check("立绘失败归类为 boot 失败",
                RenderAvailabilityState.REASON_BOOT_FAILED.equals(adapter.state.reason()),
                adapter.state.reason());

        // 恢复：图片重新加载成功 → 立绘可见
        adapter.recoverPortrait();
        check("恢复后重新挂上完整窗口", isFullWindow(adapter), adapter.host.trace);
        check("恢复后阶段为 READY",
                adapter.state.phase() == RenderAvailabilityState.Phase.READY, adapter.state.phase());
        check("恢复后内容是完整页面（不是卡片）",
                !adapter.surfaceHost.cardVisible(), adapter.surface.mode());
    }

    private static void adapterRepeatedPortraitSignalsAreIdempotent() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.pageFinished();
        adapter.portraitVisible();
        int attaches = adapter.host.attachCalls;
        adapter.portraitVisible();
        adapter.portraitVisible();
        adapter.pageFinished();
        drain();
        check("重复 ready 信号不重复 addView", adapter.host.attachCalls == attaches,
                adapter.host.attachCalls + " vs " + attaches);
        check("重复 ready 后仍是完整窗口", isFullWindow(adapter), adapter.host.trace);
    }

    private static void adapterStalePageEventsCannotRestoreNewWindow() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.portraitVisible();
        check("第一页正常显示完整窗口", isFullWindow(adapter), adapter.host.trace);

        // 新页面会话：旧 WebView 代际作废，旧内容立即不可信
        long staleGeneration = adapter.loadGeneration;
        adapter.surfaceHost.onNewPageSession();
        adapter.syncLoadGeneration();
        drain();
        check("新页面会话后窗口被移除/降为卡片",
                !adapter.host.attached
                        || adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD,
                adapter.host.trace);

        // 旧页面迟到的「立绘可见」：代际已变，必须被丢弃
        check("旧代际迟到 ready 信号被拒绝",
                !adapter.petWindow.onPortraitVisibleForGeneration(staleGeneration),
                adapter.loadGeneration);
        drain();
        check("旧代际迟到 ready 不能恢复新窗口",
                !isFullWindow(adapter) && adapter.host.plan != OverlayVisibilityController.WindowPlan.FULL,
                adapter.host.trace);
        check("旧代际信号后阶段不是 READY",
                adapter.surfaceHost.phaseForTest() != RenderAvailabilityState.Phase.READY,
                adapter.surfaceHost.phaseForTest());

        // 新页面自己的可见信号仍然有效（合法恢复，不是旧事件恢复）
        adapter.petWindow.onPortraitVisibleForGeneration(adapter.loadGeneration);
        drain();
        check("新代际自己的 ready 仍能恢复完整窗口", isFullWindow(adapter), adapter.host.trace);
    }

    private static void adapterUserCloseDuringRecoveryKeepsWindowDetached() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.failMainFrame(-2);
        // FIX-RUNTIME-03：恢复期不再「窗口完全移除」，而是有界可见的恢复卡片——
        // 加载/恢复期窗口必须存在，否则「等可见才挂载」与「先挂载才能渲染」
        // 会互相等待，立绘预加载永远无法推进。
        check("失败恢复期是有界可见卡片（不是透明整窗）",
                adapter.host.attached
                        && adapter.host.plan == OverlayVisibilityController.WindowPlan.CARD
                        && !isFullWindow(adapter),
                adapter.host.trace);

        // 用户在恢复卡片上点「关闭」
        adapter.controller.setUserEnabled(false);
        drain();
        adapter.portraitVisible();   // 页面此刻又报可见
        drain();
        check("用户关闭后迟到可见信号不能重开窗口", !adapter.host.attached, adapter.host.trace);
        check("用户关闭意图保持", !adapter.controller.shouldAttach(), adapter.controller.shouldAttach());
    }

    private static void adapterCaptureHiddenDuringRecoveryRespectsOwner() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=secret");
        adapter.pageStarted();
        adapter.failMainFrame(-2);
        check("失败恢复期不再有完整透明窗口", !isFullWindow(adapter), adapter.host.trace);
        adapter.controller.acquireCaptureHidden("cap-1");
        drain();
        check("截图隐藏期间窗口不在", !adapter.host.attached, adapter.host.trace);

        adapter.portraitVisible();
        drain();
        check("截图隐藏期间恢复信号不能挂窗", !adapter.host.attached, adapter.host.trace);

        adapter.controller.releaseCaptureHidden("cap-1");
        drain();
        check("释放截图隐藏且渲染已可用 → 挂完整窗口", isFullWindow(adapter), adapter.host.trace);
    }

    private static void adapterDiagnosticsAreSanitized() {
        Adapter adapter = new Adapter();
        adapter.load("https://host/overlay?token=SUPERSECRET&chat=hello");
        adapter.pageStarted();
        // 控制台错误里塞入敏感内容：产品只允许记录类型/行号/文件名
        adapter.webView().chromeClientForTest().onConsoleMessage(new ConsoleMessage(
                ConsoleMessage.MessageLevel.ERROR,
                "token=SUPERSECRET data:image/png;base64,AAAA chat=hello",
                "https://host/static/app.js?token=SUPERSECRET", 4242));
        adapter.failMainFrame(-2);
        drain();
        String snapshot = adapter.petWindow.diagnosticsSnapshot();
        check("诊断含阶段与原因字段",
                snapshot.contains("\"phase\"") && snapshot.contains("\"reason\""), snapshot);
        check("诊断含 console 类型", snapshot.contains("\"lastConsoleType\":\"ERROR\""), snapshot);
        check("诊断含 console 行号", snapshot.contains("\"lastConsoleLine\":4242"), snapshot);
        check("诊断不含完整 URL/query", !snapshot.contains("https://"), snapshot);
        check("诊断不含 token 值", !snapshot.contains("SUPERSECRET"), snapshot);
        check("诊断不含聊天内容", !snapshot.contains("chat=hello"), snapshot);
        check("诊断不含图片 dataURL", !snapshot.contains("data:image"), snapshot);
        check("console 来源只保留文件名", snapshot.contains("app.js") && !snapshot.contains("?token="), snapshot);
    }

    /**
     * 本轮现场缺陷的真 parent/attach 行为测试。
     *
     * 旧实现 setMode(CARD) 会 {@code removeAllViews()} 再只 add 卡片，页面视图
     * （内含 WebView）因此脱离视图树。此前只断言 {@code setVisibility(VISIBLE)}
     * 不足以发现问题 —— 孤立子 View 的 visibility 依然是 VISIBLE。
     *
     * 这里断言的是**父链**：页面视图必须是 root 的真实子视图，WebView 必须沿
     * 父链继承 attach（替身的 isAttachedToWindow 已改为继承父链，不再自欺），
     * 卡片必须覆盖在页面之上；READY 升级 FULL 只摘卡片层，页面不被销毁/替换。
     */
    private static void adapterCardKeepsPageAttachedUnderCard() {
        Adapter adapter = new Adapter();
        // 模拟窗口真的被 WindowManager 挂上：根 View attached。
        adapter.rootView.attached = true;
        adapter.load("https://host/overlay?token=secret");

        check("CARD 时窗口根已 attach", adapter.rootView.isAttachedToWindow(),
                adapter.rootView.isAttachedToWindow());
        check("CARD 时页面视图仍在 root 视图树里（真实 parent，不是孤立子 View）",
                adapter.petWindow.view().getParent() == adapter.rootView,
                adapter.petWindow.view().getParent());
        check("CARD 时 WebView 沿父链判定为已 attach",
                adapter.webView().isAttachedToWindow(), adapter.webView().getParent());
        check("CARD 时页面可见（预加载能推进，非 GONE/INVISIBLE）",
                adapter.webView().getVisibility() == View.VISIBLE,
                adapter.webView().getVisibility());
        check("CARD 时卡片层挂在 root 上",
                adapter.surface.cardViewForTest().getParent() == adapter.rootView,
                adapter.surface.cardViewForTest().getParent());
        check("卡片层位于页面层之上（后添加 = 更上层）",
                adapter.rootView.indexOfChild(adapter.surface.cardViewForTest())
                        > adapter.rootView.indexOfChild(adapter.petWindow.view()),
                adapter.rootView.getChildCount());

        // 立绘 ready → FULL：只移除卡片层，页面视图不销毁/不替换、保持单一 parent。
        adapter.recoverPortrait();
        check("READY 后形态为 FULL", adapter.surface.mode() == OverlaySurface.Mode.FULL,
                adapter.surface.mode());
        check("FULL 后卡片层已移除", adapter.surface.cardViewForTest().getParent() == null,
                adapter.surface.cardViewForTest().getParent());
        check("FULL 后页面视图仍在 root 且已 attach（未被销毁/替换）",
                adapter.petWindow.view().getParent() == adapter.rootView
                        && adapter.webView().isAttachedToWindow(),
                adapter.petWindow.view().getParent());
        check("FULL 后页面视图只有一个 parent（无重复挂载）",
                adapter.rootView.indexOfChild(adapter.petWindow.view()) >= 0
                        && adapter.rootView.getChildCount() == 1,
                adapter.rootView.getChildCount());
        check("FULL 后 WebView 仍可见", adapter.webView().getVisibility() == View.VISIBLE,
                adapter.webView().getVisibility());
    }

    /**
     * 失败分支必须能进入去敏诊断快照。
     *
     * 现场只有「卡片 + 连接失败」文案，无法判断到底是网络不可达、HTTP 被拒还是
     * 页面 JS 失败。这里断言真实回调路径把分支码写进 {@link PetWindow.Diagnostics}，
     * 服务据此写限频 logcat。
     */
    private static void adapterDiagnosticsCarryFailureBranchCodes() {
        Adapter http = new Adapter();
        http.load("https://host/overlay?token=secret");
        http.httpReject(404);
        PetWindow.Diagnostics httpDiag = http.petWindow.diagnostics();
        check("HTTP 拒绝分支进入诊断快照",
                httpDiag.lastHttpStatus == 404 && httpDiag.httpErrors >= 1,
                httpDiag.lastHttpStatus + "/" + httpDiag.httpErrors);
        check("HTTP 拒绝的失败分类可读",
                RenderAvailabilityState.REASON_HTTP_REJECTED.equals(httpDiag.reason),
                httpDiag.reason);

        Adapter net = new Adapter();
        net.load("https://host/overlay?token=secret");
        net.failMainFrame(-2);
        PetWindow.Diagnostics netDiag = net.petWindow.diagnostics();
        check("主文档网络失败分支进入诊断快照",
                netDiag.lastNetCode == -2 && netDiag.netErrors >= 1 && netDiag.netMainFrame,
                netDiag.lastNetCode + "/" + netDiag.netErrors);

        Adapter js = new Adapter();
        js.load("https://host/overlay?token=secret");
        js.webView().chromeClientForTest().onConsoleMessage(new ConsoleMessage(
                ConsoleMessage.MessageLevel.ERROR,
                "token=SUPERSECRET", "https://host/static/app.js?token=SUPERSECRET", 77));
        PetWindow.Diagnostics jsDiag = js.petWindow.diagnostics();
        check("JS 异常分支进入诊断快照",
                jsDiag.consoleErrors >= 1 && "ERROR".equals(jsDiag.consoleType)
                        && jsDiag.consoleLine == 77,
                jsDiag.consoleErrors + "/" + jsDiag.consoleType + "/" + jsDiag.consoleLine);
        check("JS 来源只保留文件名（去敏）",
                "app.js".equals(jsDiag.consoleSource), jsDiag.consoleSource);
    }
}
