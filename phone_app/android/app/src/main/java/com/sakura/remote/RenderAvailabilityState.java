package com.sakura.remote;

/**
 * 悬浮窗「渲染可用性」的纯状态机（FIX-BLANK-01 核心）。
 *
 * 为什么必须有这个类：
 *   旧实现里 PetWindow 在主文档失败时只把 WebView 设成 INVISIBLE，
 *   外层 WindowManager 窗口仍然挂载、仍然 alpha=1 且整矩形接收触摸 ——
 *   用户看到的是「什么都没有」，系统看到的却是一块盖住桌面的大矩形。
 *   透明空白不允许再以「隐藏视图」冒充安全降级。
 *
 * 本类的唯一职责：根据**页面真实进度信号**判断「现在窗口里到底有没有
 * 可见内容」。窗口挂载与否由 {@link OverlayVisibilityController} 依据它的结论裁决：
 *   - READY          页面已 boot 且确认有可见立绘 —— 允许挂完整窗口；
 *   - LOADING        正在加载/还没有可见内容 —— 只允许有界可见的加载卡片；
 *   - WARNING        超过软超时仍无内容 —— 显示明确的状态卡片（仍可自动重试）；
 *   - AUTO_RECOVERY  明确失败 —— 显示有界恢复卡片，并在一段时间后自动重试一次；
 *   - FAILED         重试预算耗尽 —— 停在有界恢复卡片，等待用户手动重试/关闭。
 *
 * 它把「失败原因」和「页面代际」一起记住：
 *   - 代际（{@link #onSessionStarted}）在每次导航开始时推进；旧页面的迟到
 *     ready/failed 信号因为代际不匹配被丢弃，不能把新窗口恢复出来；
 *   - 失败原因只保存**去敏分类**（{@link #REASON_LOAD_FAILED} 等），
 *     不保存 URL、query、token、聊天或图片 dataURL。
 *
 * 时间全部通过 {@link Clock} 注入，行为测试可以确定性推进，不依赖 sleep。
 * 纯 Java 无 Android 依赖，可用普通 JVM 直接构造与断言。
 */
public final class RenderAvailabilityState {

    /** 注入时钟，便于确定性测试。 */
    public interface Clock {
        long now();
    }

    /** 状态机结论：窗口里此刻有没有可见内容。 */
    public enum Phase {
        /** 页面已 boot 且立绘可见：唯一允许挂完整透明窗口的状态。 */
        READY,
        /** 正在加载：只允许有界可见的加载卡片。 */
        LOADING,
        /** 超过软超时仍没有可见内容：显示明确状态卡片，仍未放弃自动重试。 */
        WARNING,
        /** 明确失败：显示有界恢复卡片，稍后自动重试一次。 */
        AUTO_RECOVERY,
        /** 重试预算耗尽：停在恢复卡片，等待用户手动重试或关闭。 */
        FAILED
    }

    // ---- 去敏失败分类（只允许这些稳定代码进入诊断/状态展示）----------

    public static final String REASON_NONE = "";
    /** 主文档网络层失败（含连接被拒、DNS、超时）。 */
    public static final String REASON_LOAD_FAILED = "load_failed";
    /** 主文档 HTTP 状态被拒（4xx/5xx）。 */
    public static final String REASON_HTTP_REJECTED = "http_rejected";
    /** 文档已加载但迟迟没有可见立绘（图片失败/未配置/始终未解码）。 */
    public static final String REASON_PORTRAIT_MISSING = "portrait_missing";
    /** 页面 boot 主动上报失败（初始化异常/拿不到状态）。 */
    public static final String REASON_BOOT_FAILED = "boot_failed";
    /** 自动重试预算耗尽。 */
    public static final String REASON_RETRY_EXHAUSTED = "retry_exhausted";
    /** 主文档 SSL 失败。 */
    public static final String REASON_SSL_FAILED = "ssl_failed";

    /** 加载多久还没有可见内容就从 LOADING 进 WARNING。 */
    public static final long DEFAULT_WARNING_AFTER_MS = 8_000L;
    /** WARNING 之后再多久进 AUTO_RECOVERY。 */
    public static final long DEFAULT_RECOVERY_AFTER_MS = 6_000L;
    /** 恢复之后多久自动重试一次。 */
    public static final long DEFAULT_AUTO_RETRY_AFTER_MS = 8_000L;
    /** 自动重试最多几次（之后进入 FAILED，不再自动挂窗）。 */
    public static final int DEFAULT_AUTO_RETRY_BUDGET = 6;
    /**
     * AUTO_RECOVERY 期间即使没有自动重试到点也要定期 tick 的上限。
     *
     * 为什么必须有：WARNING→AUTO_RECOVERY 与「自动重试到点」都靠心跳推进。
     * 旧实现的 {@code nextTickDelayMs()} 在 AUTO_RECOVERY 且未到点时返回 0/-1，
     * 心跳就没有下一次 —— 状态机停在 AUTO_RECOVERY，永远等不到自动重试，
     * 也永远进不了 FAILED 恢复卡片。这里给一个有上限的兜底间隔。
     */
    public static final long DEFAULT_RECOVERY_TICK_MS = 1_000L;

    private final Clock clock;
    private final long warningAfterMs;
    private final long recoveryAfterMs;
    private final long autoRetryAfterMs;
    private final int autoRetryBudget;

    /** 测试观察用的事件记录（去敏，只有分类/代际/时间）。 */
    private final java.util.List<String> events = new java.util.ArrayList<>();

    private long generation;
    private Phase phase = Phase.LOADING;
    private String reason = REASON_NONE;
    private long phaseSince;
    private long lastSignal;
    private boolean bootOk;
    private boolean visible;
    private int autoRetriesUsed;
    private long autoRetryAt;
    private boolean autoRetryDue;

    public RenderAvailabilityState() {
        this(new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        }, DEFAULT_WARNING_AFTER_MS, DEFAULT_RECOVERY_AFTER_MS,
                DEFAULT_AUTO_RETRY_AFTER_MS, DEFAULT_AUTO_RETRY_BUDGET);
    }

    public RenderAvailabilityState(Clock clock,
                                   long warningAfterMs,
                                   long recoveryAfterMs,
                                   long autoRetryAfterMs,
                                   int autoRetryBudget) {
        this.clock = clock == null ? new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        } : clock;
        this.warningAfterMs = Math.max(1L, warningAfterMs);
        this.recoveryAfterMs = Math.max(1L, recoveryAfterMs);
        this.autoRetryAfterMs = Math.max(1L, autoRetryAfterMs);
        this.autoRetryBudget = Math.max(0, autoRetryBudget);
        long now = this.clock.now();
        this.phaseSince = now;
        this.lastSignal = now;
        this.autoRetryAt = now + this.autoRetryAfterMs;
    }

    // ---- 信号入口（全部去敏）------------------------------------------

    /**
     * 推进一次页面代际**但不清理自动重试预算**（重试尝试专用）。
     *
     * 为什么与 {@link #onSessionStarted()} 分开：
     *   {@link PetWindow#retryNow()} 会发起一次新的 loadUrl，必须同时推进页面代际 ——
     *   否则旧实现里「PetWindow 的 loadGeneration 加了 1、状态机代际没动」，
     *   本次重试后网页送来的 boot/立绘信号全部因代际不匹配被丢弃，
     *   窗口永远升不到 READY（现场表现：新页面已经渲染，立绘就是不出现）。
     *   而自动重试的预算不能因为「同一页面的再一次尝试」被重置，否则重试
     *   预算永远耗不尽，FAILED 恢复卡片也永远不出现。
     *
     * @return 推进后的新代际
     */
    public synchronized long onAttemptStarted() {
        generation++;
        bootOk = false;
        visible = false;
        reason = REASON_NONE;
        clearAutoRetry();
        long now = clock.now();
        phase = Phase.LOADING;
        phaseSince = now;
        lastSignal = now;
        events.add("attempt_started#g" + generation);
        return generation;
    }

    /**
     * 新的页面加载开始：推进代际、清掉 ready。
     *
     * 旧页面之后送来的 ready/failed 信号代际不匹配，一律丢弃 ——
     * 「不因旧 page 事件恢复新 window」的落点。
     */
    public synchronized void onSessionStarted() {
        generation++;
        bootOk = false;
        visible = false;
        reason = REASON_NONE;
        // 新页面有自己的自动重试预算：不继承上一页耗掉的额度，
        // 否则同 URL reload 一次就把后续自动恢复全部钉死。
        autoRetriesUsed = 0;
        clearAutoRetry();
        long now = clock.now();
        phase = Phase.LOADING;
        phaseSince = now;
        lastSignal = now;
        events.add("session_started#g" + generation);
    }

    /**
     * 主文档加载失败（网络层）。
     *
     * @param expectedGeneration 调用方捕获的代际；与当前不一致则丢弃
     */
    public synchronized void onLoadFailed(long expectedGeneration, String reasonCode) {
        if (!accept(expectedGeneration)) {
            return;
        }
        visible = false;
        enterFailure(reasonCode == null || reasonCode.isEmpty()
                ? REASON_LOAD_FAILED : reasonCode);
        events.add("load_failed#" + this.reason + "#g" + generation);
    }

    /** 主文档 HTTP 状态被拒。 */
    public synchronized void onHttpRejected(long expectedGeneration, int httpStatus) {
        if (!accept(expectedGeneration)) {
            return;
        }
        visible = false;
        enterFailure(REASON_HTTP_REJECTED);
        events.add("http_rejected#" + httpStatus + "#g" + generation);
    }

    /** 页面 boot 主动上报失败。 */
    public synchronized void onBootFailed(long expectedGeneration) {
        if (!accept(expectedGeneration)) {
            return;
        }
        visible = false;
        enterFailure(REASON_BOOT_FAILED);
        events.add("boot_failed#g" + generation);
    }

    /** 页面 boot 完成（DOM 就绪、桥可用），但立绘可能还没解码。 */
    public synchronized void onBootOk(long expectedGeneration) {
        if (!accept(expectedGeneration)) {
            return;
        }
        bootOk = true;
        lastSignal = clock.now();
        events.add("boot_ok#g" + generation);
    }

    /** 确认有可见且已解码的立绘：这是完整窗口唯一允许挂载的依据。 */
    public synchronized void onPortraitVisible(long expectedGeneration) {
        if (!accept(expectedGeneration)) {
            return;
        }
        bootOk = true;
        visible = true;
        reason = REASON_NONE;
        clearAutoRetry();
        long now = clock.now();
        phase = Phase.READY;
        phaseSince = now;
        lastSignal = now;
        events.add("portrait_visible#g" + generation);
    }

    /** 页面主动注销（pagehide / 导航开始）：立刻视为没有可见内容。 */
    public synchronized void onSessionInvalidated(long expectedGeneration) {
        if (!accept(expectedGeneration)) {
            return;
        }
        visible = false;
        bootOk = false;
        enterFailure(REASON_LOAD_FAILED);
        events.add("session_invalidated#g" + generation);
    }

    /** 自动重试预算耗尽（由重试方在超出预算时告知）。 */
    public synchronized void onRetriesExhausted(long expectedGeneration) {
        if (!accept(expectedGeneration)) {
            return;
        }
        visible = false;
        enterFailed(REASON_RETRY_EXHAUSTED);
        events.add("retries_exhausted#g" + generation);
    }

    /**
     * 重试方发起了一次新的尝试（刷新页面）。
     *
     * @return true 表示本次尝试被接受（预算内/用户手动），状态回到 LOADING
     */
    public synchronized boolean onRetryRequested(boolean manual) {
        // 手动重试永远允许，并**重置自动重试预算**：用户明确要求再试一次时，
        // 不能让之前耗尽的自动预算把后续自动重试也永久钉死。
        if (!manual && autoRetriesUsed >= autoRetryBudget) {
            enterFailed(REASON_RETRY_EXHAUSTED);
            events.add("retry_refused#budget#g" + generation);
            return false;
        }
        if (manual) {
            autoRetriesUsed = 0;
        } else {
            autoRetriesUsed++;
        }
        bootOk = false;
        visible = false;
        reason = REASON_NONE;
        clearAutoRetry();
        long now = clock.now();
        phase = Phase.LOADING;
        phaseSince = now;
        lastSignal = now;
        events.add("retry#" + (manual ? "manual" : "auto") + "#g" + generation);
        return true;
    }

    /**
     * 推进入口：每次 Handler 心跳/信号后调用一次。
     *
     * @return true 表示状态发生了变化，调用方需要重新裁决窗口挂载
     */
    public synchronized boolean tick() {
        long now = clock.now();
        boolean changed = false;
        if (phase == Phase.LOADING && now - phaseSince >= warningAfterMs) {
            phase = Phase.WARNING;
            if (REASON_NONE.equals(reason)) {
                reason = REASON_PORTRAIT_MISSING;
            }
            phaseSince = now;
            changed = true;
            events.add("warning#" + reason + "#g" + generation);
        } else if (phase == Phase.WARNING && now - phaseSince >= recoveryAfterMs) {
            phase = Phase.AUTO_RECOVERY;
            if (REASON_NONE.equals(reason)) {
                reason = REASON_PORTRAIT_MISSING;
            }
            phaseSince = now;
            autoRetryAt = now + autoRetryAfterMs;
            changed = true;
            events.add("auto_recovery#" + reason + "#g" + generation);
        }
        // 自动重试到点：独立判断，不复用上面分支的结果。
        // 只有 AUTO_RECOVERY 会自动重试；FAILED 是终点，等用户手动重试。
        if (phase == Phase.AUTO_RECOVERY && now >= autoRetryAt) {
            // 暴露「该自动重试了」；真正的重试动作由调用方执行 onRetryRequested，
            // 时间推进与动作分离，便于确定性测试。
            if (!autoRetryDue) {
                autoRetryDue = true;
                changed = true;
                events.add("auto_retry_due#g" + generation);
            }
        }
        return changed;
    }

    /** 下一次需要 tick 的时间（用于 post 延迟心跳）。 */
    public synchronized long nextTickDelayMs() {
        long now = clock.now();
        if (phase == Phase.LOADING) {
            return Math.max(1L, warningAfterMs - (now - phaseSince));
        }
        if (phase == Phase.WARNING) {
            return Math.max(1L, recoveryAfterMs - (now - phaseSince));
        }
        if (phase == Phase.AUTO_RECOVERY) {
            if (autoRetryDue) {
                // 已经暴露「该重试了」：不再需要周期心跳，等重试动作推进代际。
                return -1L;
            }
            // 到点必须被看见；未到点也要有上限兜底，避免心跳彻底停摆。
            return Math.max(1L, Math.min(autoRetryAt - now, DEFAULT_RECOVERY_TICK_MS));
        }
        return -1L;
    }

    /**
     * AUTO_RECOVERY 期间是否仍需要周期心跳。
     *
     * 与 {@link #nextTickDelayMs()} 一致：FAILED 是终点、READY 已可挂载，
     * 两者都不再需要周期 tick；LOADING/WARNING/AUTO_RECOVERY 需要。
     */
    public synchronized boolean needsPeriodicTick() {
        return phase == Phase.LOADING || phase == Phase.WARNING
                || (phase == Phase.AUTO_RECOVERY && !autoRetryDue);
    }

    /** 是否已到「该自动重试」的时间点（调用方据此发起重试并 post 心跳）。 */
    public synchronized boolean autoRetryPending() {
        return autoRetryDue;
    }

    // ---- 查询 ---------------------------------------------------------

    public synchronized Phase phase() {
        return phase;
    }

    /** 是否允许挂载真正显示页面的完整窗口。 */
    public synchronized boolean isRenderAttachable() {
        return phase == Phase.READY;
    }

    /**
     * 是否允许有界可见的状态/加载卡片参与窗口挂载（FIX-RUNTIME-03）。
     *
     * 只有 READY 不允许：READY 时窗口必须升级成完整页面，不能退成卡片。
     * 其余阶段一律允许 —— 关键就在这里：**加载中/自动恢复期窗口不能完全移除**，
     * 否则初次的 DETACHED + INVISIBLE WebView 会形成死循环
     * （「等可见才挂载」和「先挂载才能渲染」互相等待），网页的立绘预加载
     * 与图片解码无法推进，ready 信号永远不来，窗口永远升不到完整形态。
     *
     * 安全性：卡片尺寸固定且有界、背景不透明、绝不铺满屏幕，因此不会变成
     * 「看不到内容却整矩形拦点击」的透明大窗；任何隐藏理由（用户关闭 / 授权隐藏 /
     * 截图隐藏）仍然优先，一律 DETACHED。
     */
    public synchronized boolean isCardAllowed() {
        return phase != Phase.READY;
    }

    public synchronized String reason() {
        return reason;
    }

    public synchronized long generation() {
        return generation;
    }

    public synchronized boolean bootOk() {
        return bootOk;
    }

    public synchronized boolean visiblePortrait() {
        return visible;
    }

    public synchronized int autoRetriesUsed() {
        return autoRetriesUsed;
    }

    public synchronized long phaseAgeMs() {
        return clock.now() - phaseSince;
    }

    public synchronized long lastSignalAgeMs() {
        return clock.now() - lastSignal;
    }

    /** 去敏事件轨迹快照（测试与诊断用）。 */
    public synchronized java.util.List<String> events() {
        return new java.util.ArrayList<>(events);
    }

    // ---- 内部 ---------------------------------------------------------

    private boolean accept(long expectedGeneration) {
        return expectedGeneration == generation;
    }

    private void enterFailure(String reasonCode) {
        reason = reasonCode == null ? REASON_NONE : reasonCode;
        long now = clock.now();
        phase = Phase.AUTO_RECOVERY;
        phaseSince = now;
        lastSignal = now;
        autoRetryAt = now + autoRetryAfterMs;
        autoRetryDue = false;
    }

    private void enterFailed(String reasonCode) {
        reason = reasonCode == null ? REASON_NONE : reasonCode;
        long now = clock.now();
        phase = Phase.FAILED;
        phaseSince = now;
        lastSignal = now;
        // FAILED 是终点：不再自动重试，等待用户显式重试/关闭。
        autoRetryAt = Long.MAX_VALUE;
        autoRetryDue = false;
    }

    private void clearAutoRetry() {
        autoRetryDue = false;
    }
}
