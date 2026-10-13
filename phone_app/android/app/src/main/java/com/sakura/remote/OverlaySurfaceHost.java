package com.sakura.remote;

import android.view.View;

/**
 * 把「渲染可用性」桥接到「窗口挂载」的协调器（FIX-BLANK-01）。
 *
 * 职责边界：
 *   - {@link RenderAvailabilityState} 判断页面里到底有没有可见内容；
 *   - {@link OverlayVisibilityController} 裁决窗口挂不挂、以什么形态挂；
 *   - 本类只做**信号转发与形态呈现**：把 PetWindow 的可用性回调翻译成
 *     renderAttachable 门控与有界卡片内容，并在形态变化时请求宿主调整窗口尺寸。
 *
 * 它不直接调用 WindowManager（宿主 {@link LayoutHost} 负责），
 * 因此可以在普通 JVM 里用真实 OverlayVisibilityController + 替身宿主测试。
 */
public final class OverlaySurfaceHost implements PetWindow.AvailabilityListener {

    /** 窗口形态与尺寸调整的宿主。 */
    public interface LayoutHost {
        /** 按给定形态调整窗口尺寸/内容；失败返回 false。 */
        boolean applyShape(OverlaySurface.Mode mode);

        /** 用户点卡片「关闭」：停止桌面立绘。 */
        void requestStopOverlay();
    }

    private final PetWindow petWindow;
    private final OverlaySurface surface;
    private final OverlayVisibilityController visibility;
    private final LayoutHost layoutHost;
    /** 每次渲染可用性通知的去敏快照记录器（服务使用；可为 null，测试无需提供）。 */
    private AvailabilityRecorder availabilityRecorder;

    /**
     * 渲染可用性通知的记录钩子。
     *
     * 为什么单独一个接口而不是塞进 {@link LayoutHost}：布局宿主是「改窗口形态」
     * 的职责，而快照记录只是旁观。分开后既有测试替身不必为新方法全部改签名，
     * 也不会把诊断写进布局决策路径。
     */
    public interface AvailabilityRecorder {
        /** PetWindow 的 notifyAvailability/tick 通知到达（去敏快照落点）。 */
        void onAvailabilityNotified();
    }

    public void setAvailabilityRecorder(AvailabilityRecorder recorder) {
        this.availabilityRecorder = recorder;
    }

    private void recordAvailability() {
        AvailabilityRecorder recorder = availabilityRecorder;
        if (recorder != null) {
            recorder.onAvailabilityNotified();
        }
    }

    private boolean closeRequested;
    /** 防止 renderCard → notifyAvailability → onRenderAvailability → renderCard 递归。 */
    private boolean notifyingAvailability;

    public OverlaySurfaceHost(PetWindow petWindow,
                              OverlaySurface surface,
                              OverlayVisibilityController visibility,
                              LayoutHost layoutHost) {
        this.petWindow = petWindow;
        this.surface = surface;
        this.visibility = visibility;
        this.layoutHost = layoutHost;
        this.surface.setActions(new OverlaySurface.Actions() {
            @Override
            public void onRetryTapped() {
                manualRetry();
            }

            @Override
            public void onCloseTapped() {
                closeRequested = true;
                if (OverlaySurfaceHost.this.layoutHost != null) {
                    OverlaySurfaceHost.this.layoutHost.requestStopOverlay();
                }
            }
        });
        petWindow.setAvailabilityListener(this);
        // 初始卡片：还没确认可见内容时窗口不可能是透明大窗
        surface.setMode(OverlaySurface.Mode.CARD);
        renderCard();
    }

    /**
     * 新页面会话开始：推进权威代际并让窗口回到有界加载卡片。
     *
     * @return 推进后的权威代际（同 {@link PetWindow#loadGeneration()}）
     */
    public long onNewPageSession() {
        return petWindow.onNewPageSession();
    }

    public boolean closeRequested() {
        return closeRequested;
    }

    public OverlaySurface surface() {
        return surface;
    }

    // ---- PetWindow.AvailabilityListener ---------------------------------

    @Override
    public void onRenderAvailability(boolean attachable, String phase, String reason) {
        // 先记去敏快照：现场需要看到网络/HTTP/JS/重试分支，而不只是阶段变化。
        recordAvailability();
        visibility.setRenderAttachable(attachable);
        // FIX-RUNTIME-03：READY 以外一律允许有界可见卡片。
        // 关键差别：加载中/自动恢复期**也要**挂卡片，否则初始
        // 「DETACHED + INVISIBLE WebView」会形成「等可见才挂载」与
        // 「先挂载才能渲染」的死循环，立绘预加载与解码无法推进。
        // 卡片有界可见（固定 dp 尺寸、不透明背景），且任何隐藏理由仍优先。
        boolean card = !attachable;
        visibility.setCardAllowed(card);
        OverlaySurface.Mode mode = attachable ? OverlaySurface.Mode.FULL : OverlaySurface.Mode.CARD;
        if (!attachable) {
            renderCard();
        }
        if (surface.mode() != mode) {
            surface.setMode(mode);
            // 只有窗口真的会以卡片形态出现时才需要调整窗口尺寸；
            // DETACHED 期间（用户关闭/截图隐藏）不请求 `applyShape`。
            if (layoutHost != null && (attachable || card)) {
                layoutHost.applyShape(mode);
            }
        }
    }

    @Override
    public void onRetryResult(boolean accepted, String reason) {
        recordAvailability();
        renderCard();
    }

    // ---- 卡片内容（去敏文案）------------------------------------------

    /** 用户手动点「重试」：预算清零后重新加载。 */
    void manualRetry() {
        petWindow.markNextRetryManual();
        petWindow.retryNow();
        renderCard();
    }

    private void renderCard() {
        RenderAvailabilityState render = petWindow.renderState();
        RenderAvailabilityState.Phase phase = render.phase();
        String reason = render.reason();
        boolean retryEnabled = true;
        String title;
        String message;
        switch (phase) {
            case READY:
                // 已经可用：卡片不该在显示，但保持稳定文案
                surface.updateCard("桌宠已就绪", "立绘已显示。", false);
                return;
            case WARNING:
                title = "桌面立绘加载中…";
                message = "暂时没看到立绘，仍在自动重试。";
                break;
            case AUTO_RECOVERY:
                title = titleForReason(reason);
                message = "正在自动重试；也可以点「重试」立即重来。";
                break;
            case FAILED:
                title = titleForReason(reason);
                message = "已多次重试仍未成功，请检查电脑端连接后手动重试。";
                retryEnabled = true;
                break;
            case LOADING:
            default:
                title = "桌面立绘连接中…";
                message = "正在加载页面与立绘。";
                break;
        }
        surface.updateCard(title, message, retryEnabled);
    }

    /**
     * FIX-RUNTIME-03：构造完成后立即把「加载期允许卡片」落到可见性控制器。
     *
     * 为什么不能等第一次渲染信号：控制器在 OverlayService 里是紧接着被
     * setServiceAlive(true) 触发裁决的，如果此时卡片许可还没写进去，初始形态
     * 就是 DETACHED —— 窗口与 WebView 都不参与布局，
     * 「等可见才挂载」与「先挂载才能渲染」互相等待，立绘预加载无法推进。
     *
     * 与 {@link #onRenderAvailability} 的差别：这里只负责「把一个已确认的阶段
     * 同步给控制器」，不做形态切换决策，因此不会与回调路径互相递归。
     */
    public void renderCardNow() {
        renderCard();
        if (notifyingAvailability) {
            return;
        }
        RenderAvailabilityState render = petWindow.renderState();
        boolean attachable = render.isRenderAttachable();
        visibility.setRenderAttachable(attachable);
        visibility.setCardAllowed(!attachable);
    }

    /**
     * 宿主在页面会话变化/状态变化后主动刷新卡片文案。
     *
     * 与 {@link #renderCardNow()} 同义（保留旧入口名，避免调用点遗漏）。
     */
    public void refreshCard() {
        renderCardNow();
    }

    private static String titleForReason(String reason) {
        if (RenderAvailabilityState.REASON_HTTP_REJECTED.equals(reason)) {
            return "电脑端拒绝了页面请求";
        }
        if (RenderAvailabilityState.REASON_BOOT_FAILED.equals(reason)) {
            return "页面启动失败";
        }
        if (RenderAvailabilityState.REASON_PORTRAIT_MISSING.equals(reason)) {
            return "没能获取到立绘图片";
        }
        if (RenderAvailabilityState.REASON_SSL_FAILED.equals(reason)) {
            return "连接安全校验失败";
        }
        if (RenderAvailabilityState.REASON_RETRY_EXHAUSTED.equals(reason)) {
            return "需要检查电脑端连接";
        }
        return "桌面立绘连不上电脑端";
    }

    /** 卡片是否处于可见状态（测试与诊断用）。 */
    public boolean cardVisible() {
        return surface.mode() == OverlaySurface.Mode.CARD;
    }

    public View cardViewForTest() {
        return surface.cardViewForTest();
    }

    /** 卡片当前标题（测试用，只读）。 */
    public String cardTitleForTest() {
        return surface.cardTitleForTest();
    }

    /** 卡片当前正文（测试用，只读）。 */
    public String cardMessageForTest() {
        return surface.cardMessageForTest();
    }

    /** 渲染状态机当前阶段（测试用，只读）。 */
    public RenderAvailabilityState.Phase phaseForTest() {
        return petWindow.renderState().phase();
    }
}
