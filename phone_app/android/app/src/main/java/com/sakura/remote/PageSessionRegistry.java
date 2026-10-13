package com.sakura.remote;

import java.util.HashMap;
import java.util.Map;

/**
 * 真实页面会话登记表（RV-02）。
 *
 * 为什么不能再拿 URL 当会话 id：
 *   旧实现把随机 {@code PAGE_SESSION_ID} 和 {@code lastReportedUrl} 做字符串比较，
 *   两者永不相等，结果必然被拒绝；而且「同一个 URL 重新加载」时 URL 完全没变，
 *   旧页的迟到结果会被误判成仍然有效。
 *
 * 现在由页面在 boot 时主动登记自己的会话 id，原生按 (pageId → sessionId) 记录：
 *   - 新会话登记即替换旧会话，同 URL reload 也会换新 id，旧结果自然失效；
 *   - 页面在 pagehide / 导航开始时主动注销，原生立刻让旧会话失效；
 *   - 结果投递瞬间用 {@link #isCurrent} 复核，旧回调不会污染新任务。
 *
 * pageId 用来区分「App 的 WebView」与「悬浮窗的 WebView」两个不同宿主。
 * 纯 Java 无 Android 依赖，可用普通 JVM 做行为测试。
 */
final class PageSessionRegistry {

    /** 登记结果：某个 pageId 当前有效的会话。 */
    static final class Session {
        final String pageId;
        final String sessionId;
        final long generation;

        Session(String pageId, String sessionId, long generation) {
            this.pageId = pageId;
            this.sessionId = sessionId;
            this.generation = generation;
        }
    }

    private final Object lock = new Object();
    private final Map<String, Session> active = new HashMap<>();
    private long generation;

    /**
     * 登记（或幂等确认）一个页面会话。
     *
     * @return 参数合法时 true；pageId/sessionId 为空时 false（不改变现状）
     */
    boolean register(String pageId, String sessionId) {
        if (isBlank(pageId) || isBlank(sessionId)) {
            return false;
        }
        synchronized (lock) {
            Session current = active.get(pageId);
            if (current != null && sessionId.equals(current.sessionId)) {
                // 同一会话重复登记（例如 boot 重入）不推进 generation
                return true;
            }
            active.put(pageId, new Session(pageId, sessionId, ++generation));
            return true;
        }
    }

    /** 该 pageId 当前登记的会话是否就是 sessionId。 */
    boolean isCurrent(String pageId, String sessionId) {
        if (isBlank(pageId) || isBlank(sessionId)) {
            return false;
        }
        synchronized (lock) {
            Session current = active.get(pageId);
            return current != null && sessionId.equals(current.sessionId);
        }
    }

    /** 当前会话 id；没有登记时返回空串。 */
    String current(String pageId) {
        if (isBlank(pageId)) {
            return "";
        }
        synchronized (lock) {
            Session current = active.get(pageId);
            return current == null ? "" : current.sessionId;
        }
    }

    long generation(String pageId) {
        synchronized (lock) {
            Session current = active.get(pageId);
            return current == null ? -1L : current.generation;
        }
    }

    /**
     * 注销会话（导航/刷新开始、页面关闭）。
     *
     * sessionId 为空表示「无条件注销该 pageId 的当前会话」；非空时只有匹配才注销，
     * 避免旧页的迟到注销把新页刚登记的会话清掉。
     *
     * @return 是否真的注销了一个会话
     */
    boolean invalidate(String pageId, String sessionId) {
        if (isBlank(pageId)) {
            return false;
        }
        synchronized (lock) {
            Session current = active.get(pageId);
            if (current == null) {
                return false;
            }
            if (!isBlank(sessionId) && !sessionId.equals(current.sessionId)) {
                return false;
            }
            active.remove(pageId);
            return true;
        }
    }

    boolean invalidate(String pageId) {
        return invalidate(pageId, null);
    }

    // ---- 测试辅助 --------------------------------------------------

    int activeCount() {
        synchronized (lock) {
            return active.size();
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
