package com.sakura.remote;

/**
 * 输入区域的「会话 + revision」裁决（RV-03 / INT-04）。
 *
 * 旧实现无条件比较 {@code revision <= lastRevision}：换页/换角色后新会话从 1 开始，
 * 而旧会话可能已经到 9999，于是新页永远提交不上去；反过来，见到陌生会话又可能误收。
 *
 * 现在的规则：
 *   - revision 只在**同一个 session 内**单调递增；
 *   - 新 session 先由原生登记（页面 boot / 导航完成时），登记时把 lastRevision 归零；
 *   - 未登记会话一律 {@link Decision#STALE_SESSION}（不能「见陌生 session 就接受」）；
 *   - 已登记会话里 revision 不大于已接受值 → {@link Decision#STALE_REVISION}；
 *   - **同一个 session 重复登记是无操作的**（{@link #isCurrent}）：
 *     否则 enable/attach/layout 每次都会把 revision 归零，于是旧 revision 又能被接受。
 *
 * 纯 Java 无 Android 依赖，可用普通 JVM 做跨会话/reload 行为测试。
 */
final class RegionSessionGate {

    enum Decision {
        /** 接受并推进 revision。 */
        ACCEPT,
        /** 会话未登记或与当前会话不符：旧页任何高 revision 都拒绝。 */
        STALE_SESSION,
        /** 同会话内的陈旧 revision。 */
        STALE_REVISION,
        /** 载荷没有携带会话 id。 */
        NOT_REGISTERED
    }

    private String session = "";
    private long lastRevision = -1L;

    /** 该会话是否就是当前已登记会话（INT-04：同编号登记必须无操作）。 */
    synchronized boolean isCurrent(String sessionId) {
        return sessionId != null && !sessionId.isEmpty() && session.equals(sessionId);
    }

    /** 登记当前页会话；新会话从 revision 0 重新开始。 */
    synchronized void beginSession(String sessionId) {
        session = sessionId == null ? "" : sessionId;
        lastRevision = -1L;
    }

    /**
     * 注销会话（导航/刷新开始）。只有匹配当前会话（或未指定）才生效，
     * 避免旧页的迟到注销清掉新页。
     */
    synchronized boolean endSession(String sessionId) {
        if (session.isEmpty()) {
            return false;
        }
        if (sessionId != null && !sessionId.isEmpty() && !sessionId.equals(session)) {
            return false;
        }
        session = "";
        lastRevision = -1L;
        return true;
    }

    /** 裁决一次载荷提交；接受时立刻推进 lastRevision。 */
    synchronized Decision accept(String sessionId, long revision) {
        if (sessionId == null || sessionId.isEmpty()) {
            return Decision.NOT_REGISTERED;
        }
        if (session.isEmpty() || !session.equals(sessionId)) {
            return Decision.STALE_SESSION;
        }
        if (revision <= lastRevision) {
            return Decision.STALE_REVISION;
        }
        lastRevision = revision;
        return Decision.ACCEPT;
    }

    synchronized String session() {
        return session;
    }

    synchronized long lastRevision() {
        return lastRevision;
    }

    /** 服务销毁时彻底复位。 */
    synchronized void reset() {
        session = "";
        lastRevision = -1L;
    }
}
