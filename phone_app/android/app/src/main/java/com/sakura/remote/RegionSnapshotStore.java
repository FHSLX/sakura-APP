package com.sakura.remote;

/**
 * 最近一次有效输入区域快照的保存（RV-04）。
 *
 * 旧实现 {@code onViewDetachedFromWindow} 直接把 pendingRegion 清空，
 * 而 {@code applyPayload} 又会在 attach 之前清掉 pending，于是
 * 「截图隐藏 → 重新 attach」后没有任何几何可重建，窗口就保持在
 * 「上一次 Region 或整窗」的旧状态，必须等 JS 几何再次变化才恢复。
 *
 * 现在把「最新有效载荷」与「surface 是否 ready」分开：
 *   - detach 只表示 surface 暂时不可用，不清快照；
 *   - attach / layout 就绪时用最新快照重建并应用；
 *   - 真正换页（会话注销）或服务销毁时才丢弃快照。
 *
 * 纯 Java 无 Android 依赖，可用普通 JVM 验证「detach 不丢、reattach 可重建」。
 */
final class RegionSnapshotStore {

    /** 一次已通过校验的载荷快照（CSS 像素矩形，应用时再按当前视图尺寸重建）。 */
    static final class Snapshot {
        final String sessionId;
        final long revision;
        final float cssWidth;
        final float cssHeight;
        final float[][] rects;
        final boolean approximated;
        /** 网页使用的轮廓简化级别（0 = 未聚合）；仅用于诊断。 */
        final int level;
        /** 网页如实上报的边缘容差（CSS 像素）；仅用于诊断。 */
        final float tolerance;

        Snapshot(String sessionId, long revision, float cssWidth, float cssHeight,
                 float[][] rects, boolean approximated) {
            this(sessionId, revision, cssWidth, cssHeight, rects, approximated, 0, 0f);
        }

        Snapshot(String sessionId, long revision, float cssWidth, float cssHeight,
                 float[][] rects, boolean approximated, int level, float tolerance) {
            this.sessionId = sessionId == null ? "" : sessionId;
            this.revision = revision;
            this.cssWidth = cssWidth;
            this.cssHeight = cssHeight;
            this.rects = rects == null ? new float[0][] : rects;
            this.approximated = approximated;
            this.level = Math.max(0, level);
            this.tolerance = (Float.isNaN(tolerance) || Float.isInfinite(tolerance)
                    || tolerance < 0f) ? 0f : tolerance;
        }

        int rectCount() {
            return rects.length;
        }
    }

    private Snapshot latest;

    /** 保存/覆盖最新有效快照。 */
    synchronized void store(Snapshot snapshot) {
        if (snapshot != null) {
            latest = snapshot;
        }
    }

    /** 最新有效快照；没有则为 null。detach 不会清掉它。 */
    synchronized Snapshot latest() {
        return latest;
    }

    /** 会话注销/换页：只有匹配会话才丢弃，避免旧页注销清掉新页快照。 */
    synchronized boolean dropIfSession(String sessionId) {
        if (latest == null) {
            return false;
        }
        if (sessionId != null && !sessionId.isEmpty()
                && !sessionId.equals(latest.sessionId)) {
            return false;
        }
        latest = null;
        return true;
    }

    synchronized void clear() {
        latest = null;
    }

    synchronized boolean hasSnapshot() {
        return latest != null;
    }
}
