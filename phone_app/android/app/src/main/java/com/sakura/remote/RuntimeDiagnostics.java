package com.sakura.remote;

import android.util.Log;

/**
 * 现场可读的**去敏、限频**运行时诊断（FIX-RUNTIME-05）。
 *
 * 为什么需要：
 *   修复前，渲染可用性诊断只能通过网页的 JS getter
 *   （overlayRenderDiagnostics）读取 —— 真机现场一旦立绘不显示，用户拿不到
 *   任何原生侧证据，只能看到「什么都没发生」。本类把必要的状态写进
 *   logcat（release 也保留），让现场排障不再靠猜。
 *
 * 允许记录的字段（白名单，全部是稳定代码/布尔/计数）：
 *   阶段 phase、bootOk、portraitVisible、信号是否被接受与去重结果、
 *   失败分类 reason、主线程标识、心跳是否在跑、窗口形态 plan、
 *   区域结果（applied/revision/rects/fallback/error 码）。
 *
 * **禁止**记录：URL、query、token、页面文本、聊天内容、图片 dataURL、
 * console 正文、会话 id 原文。因此所有入参都只接受上述枚举/布尔/数字；
 * 没有 String 直通入口。
 *
 * 限频：同一去重键（默认 code）在窗口内只输出一次；窗口内总条数也有上限，
 * 避免「限频前先刷屏」。
 */
public final class RuntimeDiagnostics {

    /** 线程序号日志用标签（不含业务信息）。 */
    public static final String TAG = "SakuraOverlay";

    /** 注入时钟，便于确定性断言限频行为。 */
    public interface Clock {
        long now();
    }

    /** 同一去重键的最小输出间隔。 */
    static final long DEFAULT_MIN_INTERVAL_MS = 1_000L;
    /** 一个窗口内最多输出多少条。 */
    static final int DEFAULT_MAX_MESSAGES = 40;
    /** 限频窗口长度。 */
    static final long DEFAULT_WINDOW_MS = 10_000L;

    private final Clock clock;
    private final long minIntervalMs;
    private final int maxMessages;
    private final long windowMs;

    private long windowStart;
    private int windowMessages;
    private String lastKey = "";
    private long lastAt = Long.MIN_VALUE;
    private long suppressed;
    private long emitted;

    public RuntimeDiagnostics() {
        this(new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        }, DEFAULT_MIN_INTERVAL_MS, DEFAULT_MAX_MESSAGES, DEFAULT_WINDOW_MS);
    }

    public RuntimeDiagnostics(Clock clock, long minIntervalMs, int maxMessages, long windowMs) {
        this.clock = clock != null ? clock : new Clock() {
            @Override
            public long now() {
                return System.currentTimeMillis();
            }
        };
        this.minIntervalMs = Math.max(0L, minIntervalMs);
        this.maxMessages = Math.max(0, maxMessages);
        this.windowMs = Math.max(1L, windowMs);
        this.windowStart = this.clock.now();
    }

    // ---- 状态日志 -----------------------------------------------------

    /** 渲染阶段变化（去敏稳定代码）。 */
    public synchronized void renderPhaseChanged(String phase, String reason, boolean cardAllowed,
                                                boolean windowAttached, boolean heartbeat) {
        if (!isPhase(phase)) {
            return;
        }
        emit("phase", "phase=" + phase
                + " reason=" + reasonOrNone(reason)
                + " cardAllowed=" + cardAllowed
                + " window=" + windowAttached
                + " heartbeat=" + heartbeat);
    }

    /**
     * 一次渲染信号的处理结果。
     *
     * @param kind      page_session / boot_ok / boot_failed / portrait_visible / invalidate
     * @param accepted  该信号是否被状态机接纳（代际匹配且会话仍有效）
     * @param deduped   是否因重复而被去重
     * @param onMain   落地时是否位于主线程
     */
    public synchronized void renderSignal(String kind, boolean accepted, boolean deduped,
                                          boolean onMain, String reason) {
        if (!isSignalKind(kind)) {
            return;
        }
        emit("signal", "signal=" + kind
                + " accepted=" + accepted
                + " deduped=" + deduped
                + " mainThread=" + onMain
                + " reason=" + reasonOrNone(reason));
    }

    /** 桥线程封送结果（只记「是否在主线程落地」，不记线程名）。 */
    public synchronized void renderRoute(boolean onMain, long queued, long applied,
                                         long onMainThread, long errors) {
        emit("route", "route queued=" + queued + " applied=" + applied
                + " onMain=" + onMainThread + " allMain=" + (applied == onMainThread)
                + " onMainThread=" + onMain + " errors=" + errors);
    }

    /** 窗口形态/挂载结果（plan=detached/card/full）。 */
    public synchronized void windowPlan(String plan, boolean attached) {
        if (!isPlan(plan)) {
            return;
        }
        emit("plan", "plan=" + plan + " attached=" + attached);
    }

    /**
     * 穿透窗口 alpha 状态（去敏限频）。
     *
     * <p>真机「关闭穿透后仍半透明」缺少现场证据：JS getter 只在网页里可读，
     * logcat 里看不到。这里只写数字/布尔/稳定代码：
     * desired / active / windowAlpha / originalAlpha / safeMax / reduced / result。
     * 调用方只在状态签名变化时调用，且 {@link #emit} 自带限频与总条数上限。
     */
    public synchronized void alphaState(boolean desired, boolean active, float windowAlpha,
                                        float originalAlpha, float safeMax, boolean reduced,
                                        String resultCode) {
        emit("alpha", "alpha desired=" + desired + " active=" + active
                + " window=" + round3(windowAlpha) + " original=" + round3(originalAlpha)
                + " safeMax=" + round3(safeMax) + " reduced=" + reduced
                + " result=" + alphaResultOrNone(resultCode));
    }

    private static float round3(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        return Math.round(value * 1000f) / 1000f;
    }

    /** 精确穿透区域结果（去敏：只有 applied/revision/rects/fallback/error 码）。 */
    public synchronized void touchRegion(boolean applied, long revision, int rectCount,
                                         boolean fallbackFull, String errorCode) {
        touchRegion(applied, revision, rectCount, fallbackFull, errorCode, 0, 0f);
    }

    /**
     * 带简化级别/边缘容差的区域结果。
     *
     * <p>简化级别与容差是稳定数值（没有坐标），用于现场判断「是否退化成整图近似」；
     * 调用方只在结果真的变化时才调用本方法，避免每秒重复刷 null-region 成功日志。
     */
    public synchronized void touchRegion(boolean applied, long revision, int rectCount,
                                         boolean fallbackFull, String errorCode,
                                         int level, float tolerance) {
        float safeTolerance = (Float.isNaN(tolerance) || Float.isInfinite(tolerance) || tolerance < 0f)
                ? 0f : tolerance;
        emit("region", "region applied=" + applied + " revision=" + revision
                + " rects=" + rectCount + " level=" + Math.max(0, level)
                + " tolerance=" + (Math.round(safeTolerance * 100f) / 100f)
                + " fallback=" + fallbackFull
                + " error=" + reasonOrNone(errorCode));
    }

    /**
     * 渲染可用性去敏快照（网络 / HTTP / JS / 重试 / 形态）。
     *
     * 现场的问题是：旧日志只有阶段变化，失败时看不到「哪一条分支失败」——
     * 是主文档网络错误、HTTP 被拒、JS/boot 失败，还是重试预算耗尽。
     * 这里把白名单字段一次性写清，现场只看 logcat 就能定位分支。
     *
     * 限频键**包含阶段与失败分类**：关键状态转换（phase/reason 变化）永远是不同的
     * 限频键，因此不会被「同键 1 秒抑制」吞掉；只有完全相同的重复快照才被抑制。
     * 仍然受窗口内总条数上限约束，避免刷屏。绝不记录 URL/query/token/正文。
     */
    public synchronized void renderSnapshot(PetWindow.Diagnostics snapshot,
                                           boolean windowAttached, String plan) {
        if (snapshot == null || !isPhase(snapshot.phase)) {
            return;
        }
        String reason = reasonOrNone(snapshot.reason);
        String safePlan = isPlan(plan) ? plan : "unknown_plan";
        String consoleType = consoleTypeOrNone(snapshot.consoleType);
        String consoleSource = consoleSourceOrNone(snapshot.consoleSource);
        // 键含 phase+reason：状态转换不被同键限频吞掉。
        emit("snapshot|" + snapshot.phase + "|" + reason,
                "snapshot phase=" + snapshot.phase
                        + " reason=" + reason
                        + " attachable=" + snapshot.attachable
                        + " cardAllowed=" + snapshot.cardAllowed
                        + " plan=" + safePlan
                        + " window=" + windowAttached
                        + " heartbeat=" + snapshot.heartbeat
                        + " retry=" + snapshot.retryCount + "/" + snapshot.maxRetries
                        + " retryScheduled=" + snapshot.retryScheduled
                        + " gen=" + snapshot.generation
                        + " net=" + snapshot.netErrors + " lastNet=" + snapshot.lastNetCode
                        + " mainFrame=" + snapshot.netMainFrame
                        + " http=" + snapshot.httpErrors + " lastHttp=" + snapshot.lastHttpStatus
                        + " js=" + snapshot.consoleErrors + " lastJs=" + consoleType
                        + " jsLine=" + snapshot.consoleLine
                        + " jsSource=" + consoleSource
                        + " ssl=" + snapshot.sslError);
    }

    // ---- 限频核心 -----------------------------------------------------

    private void emit(String key, String message) {
        long now = clock.now();
        if (now - windowStart >= windowMs) {
            windowStart = now;
            windowMessages = 0;
        }
        if (windowMessages >= maxMessages) {
            suppressed++;
            return;
        }
        if (key.equals(lastKey) && now - lastAt < minIntervalMs) {
            suppressed++;
            return;
        }
        lastKey = key;
        lastAt = now;
        windowMessages++;
        emitted++;
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // 诊断绝不能影响主流程
        }
    }

    // ---- 查询（测试与诊断复用）----------------------------------------

    public synchronized long emitted() {
        return emitted;
    }

    public synchronized long suppressed() {
        return suppressed;
    }

    // ---- 白名单 -------------------------------------------------------

    private static final String[] SIGNAL_KINDS = {
            RenderSignalRouter.KIND_PAGE_SESSION,
            RenderSignalRouter.KIND_BOOT_OK,
            RenderSignalRouter.KIND_BOOT_FAILED,
            RenderSignalRouter.KIND_PORTRAIT_VISIBLE,
            RenderSignalRouter.KIND_INVALIDATE
    };

    private static final String[] PHASES = {
            "READY", "LOADING", "WARNING", "AUTO_RECOVERY", "FAILED", "none"
    };

    private static final String[] PLANS = {"detached", "card", "full", "DETACHED", "CARD", "FULL"};

    /**
     * 穿透 alpha 结果的稳定代码白名单。
     *
     * <p>任何非白名单字符串一律折叠成 {@code unknown_code}，避免把 URL/文本
     * 从「恢复结果」字段漏进 logcat。
     */
    private static final String[] ALPHA_RESULTS = {
            "init", "new-window", "dimmed", "no-dim-needed", "restored",
            "restored-unsupported", "unsupported", "degraded", "no-window",
            "already-normal", "none"
    };

    /** Android ConsoleMessage.MessageLevel 的稳定名称（白名单）。 */
    private static final String[] CONSOLE_TYPES = {
            "TIP", "LOG", "DEBUG", "INFO", "WARNING", "WARN", "ERROR"
    };

    private static final String[] REASONS = {
            "", RenderAvailabilityState.REASON_LOAD_FAILED,
            RenderAvailabilityState.REASON_HTTP_REJECTED,
            RenderAvailabilityState.REASON_PORTRAIT_MISSING,
            RenderAvailabilityState.REASON_BOOT_FAILED,
            RenderAvailabilityState.REASON_RETRY_EXHAUSTED,
            RenderAvailabilityState.REASON_SSL_FAILED,
            "queued", "deduped", "stale_session", "stale_generation",
            "budget", "no_url", "disabled", "unsupported", "invalid", "rejected",
            "diag_error", "no_window"
    };

    private static boolean isSignalKind(String kind) {
        for (String allowed : SIGNAL_KINDS) {
            if (allowed.equals(kind)) {
                return true;
            }
        }
        return false;
    }

    /** 穿透 alpha 结果码白名单（非白名单折叠为 unknown_code）。 */
    static String alphaResultOrNone(String code) {
        if (code == null || code.isEmpty()) {
            return "none";
        }
        for (String allowed : ALPHA_RESULTS) {
            if (allowed.equals(code)) {
                return code;
            }
        }
        return "unknown_code";
    }

    private static boolean isPhase(String phase) {
        for (String allowed : PHASES) {
            if (allowed.equals(phase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlan(String plan) {
        for (String allowed : PLANS) {
            if (allowed.equals(plan)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 失败/结果码白名单。
     *
     * 这是「限频之外的第二道闸」：即使调用方误传 URL、token 或页面文本，
     * 也会被替换成 unknown_code，绝不落进 logcat。
     */
    static String reasonOrNone(String code) {
        if (code == null || code.isEmpty()) {
            return "none";
        }
        for (String allowed : REASONS) {
            if (allowed.equals(code)) {
                return code;
            }
        }
        return "unknown_code";
    }

    /**
     * console 异常类型的白名单（Android ConsoleMessage.MessageLevel 的稳定名称）。
     *
     * 非白名单值一律替换为 unknown_type —— 正文/任意字符串绝不进 logcat。
     */
    static String consoleTypeOrNone(String type) {
        if (type == null || type.isEmpty()) {
            return "none";
        }
        for (String allowed : CONSOLE_TYPES) {
            if (allowed.equals(type)) {
                return type;
            }
        }
        return "unknown_type";
    }

    /**
     * console 来源：只允许**纯文件名尾段**。
     *
     * 任何带路径分隔符、query、hash 或协议的值一律替换为 unknown_source，
     * 长度超过 48 也拒绝 —— 避免 URL/query/token 从 sourceId 泄出。
     */
    static String consoleSourceOrNone(String source) {
        if (source == null || source.isEmpty()) {
            return "none";
        }
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '/' || c == '\\' || c == '?' || c == '#' || c == ':') {
                return "unknown_source";
            }
        }
        if (source.length() > 48) {
            return "unknown_source";
        }
        return source;
    }
}
