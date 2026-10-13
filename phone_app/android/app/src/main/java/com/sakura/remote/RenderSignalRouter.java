package com.sakura.remote;

import android.os.Handler;
import android.os.Looper;

/**
 * 把网页桥线程的渲染信号**封送到主线程**再落地的路由器（FIX-RUNTIME-01）。
 *
 * 缺陷背景（现场真机失败的直接原因之一）：
 *   {@code notifyOverlayPageSession/BootOk/BootFailed/PortraitVisible} 是
 *   {@code @JavascriptInterface} 方法，运行在桥线程。旧实现直接同步调用
 *   ServiceBridgeHost → OverlayService.onRender* → PetWindow.onRender*，
 *   在桥线程里改 WebView Visibility 并同步 addView/removeView。View 层次只能在
 *   创建它的线程（主线程）上触碰，桥线程触碰会抛异常或被系统静默丢弃 ——
 *   现场就是「所有信号都到了原生，窗口却始终不挂」。app.js 的 notifyRenderSession
 *   又捕获异常后静默返回 false，把现场失败完全掩盖。
 *
 * 本类的职责边界（唯一）：
 *   1. 接收桥线程调用；
 *   2. 把真正的落点 work post 到主线程；
 *   3. 记录去敏的去重/接纳/计数诊断，供 renderDiagnostics() 与限频 Log 使用。
 *   落点实现（{@link RenderSignalSink}）只会在主线程被调用。
 *
 * 诊断只含稳定代码/计数/布尔：kind、去重与接纳结果、主线程标识、
 * 时间戳与序号。**不含** URL、query、token、页面文本、聊天或图片内容。
 */
public final class RenderSignalRouter {

    /** 主线程的 post 能力（生产实现是 Handler(Looper.getMainLooper())）。 */
    public interface Poster {
        void post(Runnable task);

        /** 当前调用是否已经在主线程。 */
        boolean onMainThread();

        /** 主线程标识（去敏诊断用：只记录「信号是否在主线程接纳」）。 */
        long mainThreadId();
    }

    public static final String KIND_PAGE_SESSION = "page_session";
    public static final String KIND_BOOT_OK = "boot_ok";
    public static final String KIND_BOOT_FAILED = "boot_failed";
    public static final String KIND_PORTRAIT_VISIBLE = "portrait_visible";
    public static final String KIND_INVALIDATE = "invalidate";

    public static final String RESULT_QUEUED = "queued";
    public static final String RESULT_DEDUPED = "deduped";

    private final Poster poster;
    private final RenderSignalSink sink;

    /** 最近一次接收到的会话 id（主线程与桥线程都会读）。 */
    private volatile String lastSessionId = "";
    /** 最近一次接收到的信号类别（去敏稳定代码）。 */
    private volatile String lastSignalKind = "";
    /** 最近一次接收结果：queued / deduped。 */
    private volatile String lastSignalResult = "";
    /** 同一会话+类别的重复信号（桥线程重放/页面重复上报）。 */
    private volatile long duplicateSignals;
    /** 已封送到主线程的信号总数。 */
    private volatile long queuedSignals;
    /** 已在主线程落地的信号总数。 */
    private volatile long appliedSignals;
    /** 落地时确实位于主线程的次数（必须恒等于 appliedSignals）。 */
    private volatile long mainThreadSignals;
    /** 最后一次落地的信号类别（主线程写）。 */
    private volatile String lastAppliedKind = "";
    /** 最后一次落地所在线程标识。 */
    private volatile long lastAppliedThreadId = -1L;
    /** 落地过程中出现的异常次数（去敏：只计数，不记录正文）。 */
    private volatile long applyErrors;

    private String dedupeKey = "";
    private long sequence;

    /**
     * @param sink 主线程落点；为 null 时路由器退化为「只记诊断」，
     *             保证桥方法永不抛异常回网页（网页会静默，问题更难查）。
     */
    public RenderSignalRouter(RenderSignalSink sink) {
        this(sink, null);
    }

    /** 供行为测试注入替身 Poster（可确定性推进入队/主线程执行两个时刻）。 */
    public RenderSignalRouter(RenderSignalSink sink, Poster injectedPoster) {
        this.sink = sink;
        this.poster = injectedPoster != null ? injectedPoster
                : new HandlerPoster(new Handler(Looper.getMainLooper()));
    }

    /** 页面 boot / 导航开始。 */
    public void pageSession(String pageSessionId) {
        dispatch(KIND_PAGE_SESSION, normalize(pageSessionId), false);
    }

    /** 网页 boot 完成。 */
    public void bootOk(String pageSessionId) {
        dispatch(KIND_BOOT_OK, normalize(pageSessionId), true);
    }

    /** 网页 boot 失败。 */
    public void bootFailed(String pageSessionId) {
        dispatch(KIND_BOOT_FAILED, normalize(pageSessionId), true);
    }

    /** 立绘可见：唯一允许挂完整窗口的信号。 */
    public void portraitVisible(String pageSessionId) {
        dispatch(KIND_PORTRAIT_VISIBLE, normalize(pageSessionId), true);
    }

    /** 页面卸载 / 导航开始：注销会话。 */
    public void invalidatePageSession(String pageSessionId) {
        dispatch(KIND_INVALIDATE, normalize(pageSessionId), false);
    }

    // ---- 去敏诊断 -----------------------------------------------------

    public String lastSignalKind() {
        return lastSignalKind;
    }

    public String lastSignalResult() {
        return lastSignalResult;
    }

    public String lastSessionId() {
        return lastSessionId;
    }

    public long duplicateSignals() {
        return duplicateSignals;
    }

    public long queuedSignals() {
        return queuedSignals;
    }

    public long appliedSignals() {
        return appliedSignals;
    }

    /** 在主线程落地的信号数（必须恒等于 appliedSignals，否则说明封送有漏）。 */
    public long mainThreadSignals() {
        return mainThreadSignals;
    }

    /** 所有落点是否都在主线程执行（FIX-RUNTIME-01 的验收判据）。 */
    public boolean allAppliedOnMainThread() {
        return appliedSignals == mainThreadSignals;
    }

    public String lastAppliedKind() {
        return lastAppliedKind;
    }

    public long lastAppliedThreadId() {
        return lastAppliedThreadId;
    }

    public long applyErrors() {
        return applyErrors;
    }

    /** 去敏诊断片段（JSON 字段，不含花括号，便于宿主拼接）。 */
    public String diagnosticsFields() {
        return "\"renderSignalKind\":" + org.json.JSONObject.quote(lastSignalKind)
                + ",\"renderSignalResult\":" + org.json.JSONObject.quote(lastSignalResult)
                + ",\"renderSignalSessionPresent\":" + !lastSessionId.isEmpty()
                + ",\"renderSignalDuplicates\":" + duplicateSignals
                + ",\"renderSignalsQueued\":" + queuedSignals
                + ",\"renderSignalsApplied\":" + appliedSignals
                + ",\"renderSignalsOnMainThread\":" + mainThreadSignals
                + ",\"renderSignalsAllMainThread\":" + allAppliedOnMainThread()
                + ",\"renderSignalLastAppliedKind\":" + org.json.JSONObject.quote(lastAppliedKind)
                + ",\"renderSignalMainThreadId\":" + poster.mainThreadId()
                + ",\"renderSignalLastThreadId\":" + lastAppliedThreadId
                + ",\"renderSignalApplyErrors\":" + applyErrors;
    }

    // ---- 内部 ---------------------------------------------------------

    /**
     * 同一会话的重复信号只落地一次（重复上报是幂等的，但不该反复改窗口）。
     * 空会话 id 视为「无会话上下文」，不参与去重。
     */
    private synchronized void dispatch(String kind, String sessionId, boolean dedupe) {
        lastSignalKind = kind;
        lastSessionId = sessionId;
        if (dedupe && !sessionId.isEmpty()) {
            String key = kind + "#" + sessionId;
            if (key.equals(dedupeKey)) {
                duplicateSignals++;
                lastSignalResult = RESULT_DEDUPED;
                return;
            }
            dedupeKey = key;
        }
        lastSignalResult = RESULT_QUEUED;
        queuedSignals++;
        final long seq = ++sequence;
        final RenderSignalSink target = sink;
        poster.post(new Runnable() {
            @Override
            public void run() {
                apply(seq, kind, sessionId, target);
            }
        });
    }

    private void apply(long seq, String kind, String sessionId, RenderSignalSink target) {
        if (!poster.onMainThread()) {
            // 封送失败（例如注入的 Poster 没有主线程语义）：只记诊断，不改 UI。
            applyErrors++;
            return;
        }
        mainThreadSignals++;
        lastAppliedThreadId = Thread.currentThread().getId();
        lastAppliedKind = kind;
        try {
            if (target != null) {
                if (KIND_PAGE_SESSION.equals(kind)) {
                    target.onRenderPageSession(sessionId);
                } else if (KIND_BOOT_OK.equals(kind)) {
                    target.onRenderBootOk(sessionId);
                } else if (KIND_BOOT_FAILED.equals(kind)) {
                    target.onRenderBootFailed(sessionId);
                } else if (KIND_PORTRAIT_VISIBLE.equals(kind)) {
                    target.onRenderPortraitVisible(sessionId);
                } else if (KIND_INVALIDATE.equals(kind)) {
                    target.onInvalidatePageSession(sessionId);
                }
            }
        } catch (Throwable error) {
            // 去敏：只累计次数，不记录异常正文（可能夹带 URL/页面文本）。
            applyErrors++;
        }
        appliedSignals++;
    }

    private static String normalize(String value) {
        return value == null ? "" : value;
    }

    /** 生产 Poster：真实主线程 Handler。 */
    private static final class HandlerPoster implements Poster {
        private final Handler handler;
        private final long mainThreadId;

        HandlerPoster(Handler handler) {
            this.handler = handler;
            this.mainThreadId = Looper.getMainLooper() == null
                    ? -1L : Looper.getMainLooper().getThread().getId();
        }

        @Override
        public void post(Runnable task) {
            handler.post(task);
        }

        @Override
        public boolean onMainThread() {
            return Looper.myLooper() != null && Looper.myLooper() == Looper.getMainLooper();
        }

        @Override
        public long mainThreadId() {
            return mainThreadId;
        }
    }
}
