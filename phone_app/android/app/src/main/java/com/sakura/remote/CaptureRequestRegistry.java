package com.sakura.remote;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 截图请求的纯状态机（不依赖任何 Android 类型）。
 *
 * 把它从 {@link CaptureCoordinator} 里拆出来，是为了能用普通 JVM 跑真实的行为
 * 测试（单活动请求、陈旧结果隔离、恰好一次终结、有界结果记录），
 * 而不是只能在真机上碰运气，也不是搜索源码字符串。
 *
 * 约束：
 *   - 同时最多一个非终结请求；
 *   - 只有 requestId 匹配且仍在进行的请求才会被终结（迟到/重复回调被忽略）；
 *   - 最近一次结果有界保留（默认 60s），只含状态与错误码，不含图片数据。
 */
final class CaptureRequestRegistry {

    static final String STATE_CONSENT = "CONSENT";
    static final String STATE_STARTING_SERVICE = "STARTING_SERVICE";
    static final String STATE_WAITING_FRAME = "WAITING_FRAME";
    static final String STATE_COMPLETED = "COMPLETED";
    static final String STATE_CANCELLED = "CANCELLED";
    static final String STATE_FAILED = "FAILED";

    static final long DEFAULT_RESULT_TTL_MS = 60_000L;

    /** 一次请求的标识 + 状态。 */
    static final class Request {
        final String requestId;
        final String origin;
        final String pageSessionId;
        final long startedAt;
        private String state = "IDLE";

        Request(String requestId, String origin, String pageSessionId, long startedAt) {
            this.requestId = requestId;
            this.origin = origin;
            this.pageSessionId = pageSessionId == null ? "" : pageSessionId;
            this.startedAt = startedAt;
        }

        String state() {
            return state;
        }

        boolean isTerminal() {
            String value = state;
            return STATE_COMPLETED.equals(value)
                    || STATE_CANCELLED.equals(value)
                    || STATE_FAILED.equals(value);
        }

        void setState(String value) {
            state = value;
        }
    }

    /** 有界保留的最近一次结果。 */
    static final class Result {
        final String requestId;
        final String origin;
        final String pageSessionId;
        final String state;
        final String error;
        final boolean success;
        final long finishedAt;

        Result(Request request, String state, boolean success, String error) {
            this.requestId = request.requestId;
            this.origin = request.origin;
            this.pageSessionId = request.pageSessionId;
            this.state = state;
            this.success = success;
            this.error = error == null ? "" : error;
            this.finishedAt = System.currentTimeMillis();
        }
    }

    private final Object lock = new Object();
    private final AtomicInteger counter = new AtomicInteger(0);
    private final long resultTtlMs;

    private Request active;
    private Result lastResult;

    CaptureRequestRegistry() {
        this(DEFAULT_RESULT_TTL_MS);
    }

    CaptureRequestRegistry(long resultTtlMs) {
        this.resultTtlMs = resultTtlMs;
    }

    /** 新请求的 requestId：origin-序号-时间戳36进制。 */
    String nextRequestId(String origin, long nowMillis) {
        return (origin == null ? "unknown" : origin.toLowerCase())
                + "-" + counter.incrementAndGet()
                + "-" + Long.toString(nowMillis, 36);
    }

    /**
     * 开始一个请求；已有进行中的请求时返回 null（调用方给「忙」的可见反馈）。
     */
    Request begin(String origin, String pageSessionId, long nowMillis) {
        synchronized (lock) {
            expireLocked();
            if (active != null && !active.isTerminal()) {
                return null;
            }
            Request request = new Request(nextRequestId(origin, nowMillis),
                    origin, pageSessionId, nowMillis);
            request.setState(STATE_CONSENT);
            active = request;
            return request;
        }
    }

    boolean isBusy() {
        synchronized (lock) {
            return active != null && !active.isTerminal();
        }
    }

    Request active() {
        synchronized (lock) {
            return active;
        }
    }

    boolean isActive(String requestId) {
        synchronized (lock) {
            return active != null && !active.isTerminal()
                    && active.requestId.equals(requestId);
        }
    }

    /** 更新活动请求的状态；不匹配时什么都不做。 */
    boolean setState(String requestId, String state) {
        synchronized (lock) {
            if (active == null || active.isTerminal() || !active.requestId.equals(requestId)) {
                return false;
            }
            active.setState(state);
            return true;
        }
    }

    /**
     * 终结请求：恰好一次。
     *
     * @return 被终结的请求；requestId 不匹配（迟到/重复）时返回 null
     */
    Request finish(String requestId, String state, String error) {
        synchronized (lock) {
            if (active == null || active.isTerminal() || !active.requestId.equals(requestId)) {
                return null;
            }
            Request request = active;
            request.setState(state);
            boolean success = STATE_COMPLETED.equals(state);
            lastResult = new Result(request, state, success, error);
            active = null;
            return request;
        }
    }

    /**
     * 有界超时（RV-08）：请求停在某个状态太久就明确终结。
     *
     * 主要用途是「授权阶段没有回调」：silent BAL / 系统弹窗被压掉时，
     * 不能让 registry 永远 busy，否则之后所有截图都被「已有任务进行中」挡住。
     *
     * @param expectedState 只对该状态生效（传 null 表示任意非终结状态）
     * @return 被终结的请求；不满足条件时返回 null
     */
    Request expireStale(String expectedState, long timeoutMs, long nowMillis, String error) {
        synchronized (lock) {
            if (active == null || active.isTerminal()) {
                return null;
            }
            if (expectedState != null && !expectedState.equals(active.state())) {
                return null;
            }
            if (nowMillis - active.startedAt <= timeoutMs) {
                return null;
            }
            Request request = active;
            request.setState(STATE_FAILED);
            lastResult = new Result(request, STATE_FAILED, false,
                    error == null ? "请求超时" : error);
            active = null;
            return request;
        }
    }

    /** 按页面会话取消（WebView reload / 页面销毁）。 */
    Request cancelPageSession(String pageSessionId) {
        if (pageSessionId == null || pageSessionId.isEmpty()) {
            return null;
        }
        synchronized (lock) {
            if (active == null || active.isTerminal()
                    || !pageSessionId.equals(active.pageSessionId)) {
                return null;
            }
            Request request = active;
            request.setState(STATE_CANCELLED);
            lastResult = new Result(request, STATE_CANCELLED, false, "页面已刷新或关闭");
            active = null;
            return request;
        }
    }

    Result lastResult() {
        synchronized (lock) {
            expireLocked();
            return lastResult;
        }
    }

    private void expireLocked() {
        if (lastResult != null
                && System.currentTimeMillis() - lastResult.finishedAt > resultTtlMs) {
            lastResult = null;
        }
    }
}
