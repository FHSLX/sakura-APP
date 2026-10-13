package com.sakura.remote;

import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 悬浮窗「精确局部穿透」的原生控制器。
 *
 * 背景：
 *   Android 13 / API 33 起有公开的
 *   {@link android.view.AttachedSurfaceControl#setTouchableRegion(Region)}。
 *   通过 {@code rootView.getRootSurfaceControl()} 在主线程调用，可以把窗口的
 *   **输入区域**限制为「立绘 alpha 轮廓 + 可见控件矩形」的并集；区域之外的一次
 *   DOWN/UP 由系统直接路由给下层窗口 —— 真正的局部穿透。
 *
 * 坐标契约：
 *   - JS 提交的矩形是 **WebView 内容坐标系（CSS 像素）**；
 *   - 原生用 inputView 实际像素 / JS 报告的 CSS 视口尺寸校准；
 *   - Region 是 **surface 本地设备像素**，原点 = 窗口左上角，不叠加窗口 x/y。
 *
 * 本轮（RV-03 / RV-04 / RV-06）修正：
 *   - revision 只在**同一会话内**单调；会话由原生登记，未登记/换页旧会话一律拒绝
 *     （{@link RegionSessionGate}），不再出现「新页 revision 从 1 开始被旧页高值挡住」；
 *   - attach/detach 不再丢弃最新有效载荷（{@link RegionSnapshotStore}），
 *     attach / 布局就绪时自动重建并应用新 surface，surface 未 ready 时有界重试；
 *   - 关闭/不可用时用 {@code setTouchableRegion(null)} 恢复整窗，而不是退化的 1×1；
 *   - {@link #applyPayload} 只返回 queued（已受理待应用），真正的 applied 由
 *     {@link Host#onTouchRegionApplied} 异步 ACK 给网页，失败绝不报成功。
 *
 * 本轮（INT-04）修正：
 *   - 会话/revision/快照/代际状态集中到 {@link RegionSessionState}（纯 Java、可离线测）：
 *     同编号登记为无操作（不再把 revision 归零），真正换页时按**旧会话**清快照、
 *     待应用任务与 ACK，然后才切换会话；
 *   - 区域代际随换页/注销自增：applyPayload 的异步应用、attach/layout 重建、
 *     applyLatest 都必须复核代际与会话，旧事件不能恢复旧区域；
 *   - 旧 A 注销不影响新登记的 B（按会话匹配），换页后 A 的快照无论如何都不会回来。
 *
 * 安全约束：
 *   - API < 33 完全不调用本接口，功能降级，不伪造穿透；
 *   - 任何一次计算结果为空都回退到「整窗可交互」；
 *   - surface 未 attach 时不触碰它；所有 surface 操作都在主线程。
 */
public final class OverlayTouchRegionController {

    /** 单次提交允许的最大矩形数：防止畸形/恶意载荷把 Region 运算拖爆。 */
    private static final int MAX_RECTS = 1536;
    /** 允许的 CSS 视口尺寸范围。 */
    private static final float MIN_VIEWPORT = 32f;
    private static final float MAX_VIEWPORT = 8192f;
    /** 载荷 JSON 字符上限：前端更保守（90000 字节），这里做最后一道闸。 */
    private static final int MAX_PAYLOAD_CHARS = 98304;
    /** 网页可上报的简化级别上限（仅诊断，不参与几何计算）。 */
    private static final int MAX_LEVEL = 16;

    /** surface 暂未 ready 时的有界重试。 */
    private static final int MAX_RETRIES = 40;
    private static final long RETRY_STEP_MS = 50L;

    public interface Host {
        /**
         * 区域成功应用到 surface（overlay 报状态用）。
         *
         * @param revision    applied 的 revision；-1 表示恢复整窗
         * @param fallbackFull 轮廓为空、退回了整窗
         */
        void onTouchRegionApplied(long revision, int rectCount, boolean fallbackFull);

        /** 载荷被拒绝或应用失败，reason 是稳定的错误码。 */
        void onTouchRegionRejected(String reason);

        /** 系统/机型不支持时的通知（只报一次）。 */
        void onTouchRegionUnsupported(String reason);
    }

    private final View rootView;
    private final View inputView;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private final RegionSessionState sessions = new RegionSessionState();

    private volatile boolean passThroughEnabled;
    private boolean unsupportedReported;
    /** 机型在 setTouchableRegion 上抛异常后永久降级。 */
    private volatile boolean runtimeUnsupported;
    private int retryAttempts;
    /** 最近一次有效快照的简化级别与边缘容差（仅去敏诊断）。 */
    private volatile int lastLevel;
    private volatile float lastTolerance;

    /*
     * 「同一份区域不重复写 surface」的护栏（本轮新增）。
     *
     * setTouchableRegion 走的是 SurfaceControl 事务：每一次调用都会让 WMS 重新
     * 计算输入窗口信息。开启穿透后网页可能因为滑动/布局抖动在短时间内提交多份
     * **内容完全相同**的载荷（revision 不同但矩形一致），旧实现每次都照写一遍，
     * 属于纯浪费的主线程 + SurfaceFlinger 工作，也正是「开启穿透后更卡」的一个
     * 可确定来源。
     *
     * 这里在真正写 surface 前比一次「区域签名」：代际 + 矩形数 + 每个矩形的
     * 设备像素边界完全一致就直接跳过写入，但仍然照常上报 ACK —— 对网页而言
     * 结果没有区别（区域已经是这一份），只是少了一次系统调用。
     *
     * 只跳过 **非空区域**：恢复整窗（region == null）必须每次都执行，否则切页/
     * 关闭穿透时可能停在旧的孔洞上。代际参与签名，换页后一定不会误判为「没变」。
     */
    private long lastAppliedGeneration = Long.MIN_VALUE;
    private String lastAppliedSignature = "";

    private final View.OnAttachStateChangeListener attachListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    // 重新挂载：用最新快照重建（不需要 JS 几何再变化）
                    retryAttempts = 0;
                    // 新 surface 上的输入区域不一定继承，必须允许重写一次
                    lastAppliedSignature = "";
                    lastAppliedGeneration = Long.MIN_VALUE;
                    if (passThroughEnabled) {
                        applyLatest();
                    }
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                    // surface 暂时不可用：只停重试，**不**丢最新快照
                    main.removeCallbacks(retryRunnable);
                    // 旧的 surface 已失效，重挂后必须重新写入区域
                    lastAppliedSignature = "";
                    lastAppliedGeneration = Long.MIN_VALUE;
                }
            };

    private final Runnable retryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!passThroughEnabled) {
                return;
            }
            applyLatest();
        }
    };

    public OverlayTouchRegionController(View rootView, View inputView, Host host) {
        this.rootView = rootView;
        this.inputView = inputView;
        this.host = host;
        if (rootView != null) {
            rootView.addOnAttachStateChangeListener(attachListener);
        }
    }

    /** 是否具备精确穿透的公开 API 能力。 */
    public boolean isSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !runtimeUnsupported;
    }

    /** 精确穿透当前是否处于启用状态。 */
    public boolean isPassThroughEnabled() {
        return passThroughEnabled;
    }

    /** 最近应用快照的轮廓简化级别（0 = 未聚合；-1/整窗回退时为 0）。 */
    public int touchRegionLevel() {
        return lastLevel;
    }

    /** 最近应用快照如实上报的边缘容差（CSS 像素）。 */
    public float touchRegionTolerance() {
        return lastTolerance;
    }

    // ---- 页面会话登记（RV-03 / INT-04）-----------------------------

    /**
     * 登记当前页会话。由 OverlayService 在网页 registerPageSession 时调用。
     *
     * INT-04 修正两件事：
     *   1. **同编号登记必须无操作**：旧实现先 {@code gate.beginSession(newId)} 再
     *      {@code snapshots.dropIfSession(gate.session())}，等于拿新编号去清快照 ——
     *      旧会话的快照永远留着；而且每次 enable/attach/layout 重新登记都会把
     *      revision 归零，于是同一个旧 revision 又能被接受；
     *   2. 真正换页时按**旧会话**清快照、待应用任务与 ACK，然后才切换会话
     *      （{@link RegionSessionState#beginSession} 的顺序）。换页同时自增代际，
     *      任何在途/迟到的异步应用都会被代际复核拦下。
     */
    public void beginSession(String sessionId) {
        if (!sessions.beginSession(sessionId)) {
            // 空编号或同一页面重复登记（enable / attach / layout 都会走到这里）：
            // 保持幂等，绝不清快照、绝不把 revision 归零
            return;
        }
        main.removeCallbacks(retryRunnable);
        retryAttempts = 0;
        // 新会话还没有载荷：先把窗口恢复成整窗可交互，避免沿用旧孔洞
        final long generation = sessions.generation();
        if (passThroughEnabled) {
            main.post(() -> applyRegionNow(generation, null, -1L, 0, false));
        }
    }

    /**
     * 注销会话（网页 pagehide / 导航开始）。只有匹配当前会话才生效，
     * 避免旧页迟到注销清掉新页；旧 A 注销不得影响新登记的 B（INT-04）。
     */
    public void endSession(String sessionId) {
        if (!sessions.endSession(sessionId)) {
            return;
        }
        main.removeCallbacks(retryRunnable);
        retryAttempts = 0;
        final long generation = sessions.generation();
        if (passThroughEnabled) {
            main.post(() -> applyRegionNow(generation, null, -1L, 0, false));
        }
    }

    // ---- 开关 -------------------------------------------------------

    /**
     * 开启/关闭精确穿透。关闭时把输入区域恢复成整个窗口（null = 整窗）。
     * 关闭不清快照：再次开启时无需 JS 几何变化即可恢复。
     */
    public void setPassThroughEnabled(final boolean enabled) {
        if (!isSupported()) {
            reportUnsupported("api<33");
            return;
        }
        if (passThroughEnabled == enabled) return;
        passThroughEnabled = enabled;
        main.post(() -> {
            // 读当下代际；applyRegionNow 在真正写 surface 之前还会再复核一次
            final long generation = sessions.generation();
            if (!enabled) {
                applyRegionNow(generation, null, -1L, 0, false);
                return;
            }
            // 先整窗，避免上一次会话遗留的孔洞；有「当前会话」的快照再按最新几何重建
            applyRegionNow(generation, null, -1L, 0, false);
            if (sessions.latest() != null) {
                applyLatest();
            }
        });
    }

    /**
     * 应用 JS 提交的输入区域载荷（可在桥线程解析，主线程应用）。
     *
     * @return 稳定结果码：
     *   queued / stale / not-registered / disabled / unsupported / invalid
     *   真正的 applied 通过 {@link Host#onTouchRegionApplied} 异步通知，
     *   网页必须等 ACK 才能认为生效（失败时 {@link Host#onTouchRegionRejected}）。
     */
    public String applyPayload(final String json) {
        if (!isSupported()) {
            reportUnsupported("api<33");
            return "unsupported";
        }
        if (json == null || json.isEmpty()) {
            return "invalid";
        }
        if (!passThroughEnabled) {
            return "disabled";
        }
        final Parsed parsed;
        try {
            parsed = parse(json);
        } catch (Exception error) {
            return "invalid";
        }
        if (parsed == null) {
            return "invalid";
        }
        // 会话 + revision 裁决 + 代际读取 + 快照落库必须是**一次原子提交**（INT-04）：
        // 拆成 acceptRevision / generation / store 三次调用时，beginSession 可以在
        // 中间换页，旧代际的 A 载荷就会覆盖新会话 B 的快照。
        final RegionSnapshotStore.Snapshot snapshot = new RegionSnapshotStore.Snapshot(
                parsed.sessionId, parsed.revision, parsed.cssWidth, parsed.cssHeight,
                parsed.rects, parsed.approximated, parsed.level, parsed.tolerance);
        final RegionSessionState.SubmitResult submitted = sessions.submit(
                parsed.sessionId, parsed.revision, snapshot);
        if (submitted.decision == RegionSessionGate.Decision.NOT_REGISTERED) {
            return "not-registered";
        }
        if (!submitted.accepted()) {
            return "stale";
        }
        final long generation = submitted.generation;
        main.post(() -> {
            if (!passThroughEnabled) {
                return;
            }
            // 只有「仍是最新一版 + 代际与会话都一致」才应用；旧事件不得恢复旧区域
            if (!sessions.isApplicable(snapshot, generation)) {
                return;
            }
            applyLatest();
        });
        return "queued";
    }

    /** 服务销毁/窗口移除时调用：解除监听，避免继续引用失效 surface。 */
    public void destroy() {
        if (rootView != null) {
            rootView.removeOnAttachStateChangeListener(attachListener);
        }
        main.removeCallbacks(retryRunnable);
        sessions.reset();
        passThroughEnabled = false;
    }

    // ---- 内部实现 --------------------------------------------------

    /** 用最新快照重建 Region 并应用；没有快照则保持整窗。 */
    private void applyLatest() {
        // 先取当下代际，再按它取可应用快照：两者只要有一个对不上就会返回 null
        // （保守拒绝，不会误用旧会话）；写 surface 前还会再复核一次。
        final long generation = sessions.generation();
        RegionSnapshotStore.Snapshot snapshot = sessions.applicableSnapshot(
                generation, sessions.session());
        if (snapshot == null) {
            // 没有快照，或快照属于已换掉的旧会话：保持整窗可交互
            applyRegionNow(generation, null, -1L, 0, false);
            return;
        }
        Region region = buildRegion(snapshot);
        boolean fallback = false;
        if (region == null || region.isEmpty()) {
            // 安全底线：绝不应用空区域
            region = fullWindowRegionOrNull();
            if (region == null) {
                // 布局尚未完成：有界重试，而不是用 1×1 或谎报成功
                scheduleRetry();
                return;
            }
            fallback = true;
        }
        lastLevel = fallback ? 0 : snapshot.level;
        lastTolerance = fallback ? 0f : snapshot.tolerance;
        applyRegionNow(generation, region, snapshot.revision, snapshot.rectCount(), fallback);
    }

    /**
     * 真正写 surface。
     *
     * @param generation 计算这份区域时的代际；写入前必须仍然一致，否则直接放弃
     *                   （换页/注销之后绝不能把旧区域写回 surface）
     * @param region     null 表示恢复整窗（{@code setTouchableRegion(null)}）
     */
    private void applyRegionNow(long generation, Region region, long revision,
                                int rectCount, boolean fallback) {
        if (region == null) {
            // 恢复整窗：不再有轮廓近似，级别/容差归零
            lastLevel = 0;
            lastTolerance = 0f;
        }
        if (!sessions.isGenerationCurrent(generation)) {
            return;
        }
        if (rootView == null) {
            return;
        }
        if (!rootView.isAttachedToWindow()) {
            // surface 未 ready：保留快照，稍后重试
            scheduleRetry();
            return;
        }
        // 同一代际下区域逐设备像素完全一致：不必再写一次 surface（仍然照常 ACK）。
        // 只对非空区域生效；恢复整窗（region == null）必须真正执行。
        String signature = null;
        if (region != null) {
            signature = regionSignature(region);
            if (signature != null && generation == lastAppliedGeneration && signature.equals(lastAppliedSignature)) {
                retryAttempts = 0;
                host.onTouchRegionApplied(revision, rectCount, fallback);
                return;
            }
        }
        try {
            android.view.AttachedSurfaceControl control = rootView.getRootSurfaceControl();
            if (control == null) {
                host.onTouchRegionRejected("no-surface-control");
                scheduleRetry();
                return;
            }
            // 写入瞬间的最后一次代际复核：从取快照到真正 setTouchableRegion 之间
            // 可能已经发生换页（begin/endSession 在桥线程自增代际）。
            if (!sessions.isGenerationCurrent(generation)) {
                return;
            }
            control.setTouchableRegion(region);
            retryAttempts = 0;
            if (signature != null) {
                lastAppliedGeneration = generation;
                lastAppliedSignature = signature;
            } else {
                // 恢复整窗：下一次写入必须真正执行
                lastAppliedGeneration = Long.MIN_VALUE;
                lastAppliedSignature = "";
            }
            host.onTouchRegionApplied(revision, rectCount, fallback);
        } catch (Throwable error) {
            // MIUI / 个别 ROM 可能在底层拒绝；一次性降级，不再重试
            runtimeUnsupported = true;
            passThroughEnabled = false;
            reportUnsupported(error.getClass().getSimpleName());
        }
    }

    /**
     * 区域的**内容签名**（去敏）：代际 + 边界 + 每个 span 的起止。
     *
     * 只用于「这份区域和上次写进 surface 的是不是同一份」，不参与任何几何计算，
     * 也不写入日志或遥测。Region 的迭代顺序稳定（按 y 升序、span 左到右），
     * 因此同一份矩形集合必然得到同一签名。
     */
    private static String regionSignature(Region region) {
        if (region == null || region.isEmpty()) {
            return "empty";
        }
        StringBuilder builder = new StringBuilder(64);
        builder.append(region.getBounds().toShortString());
        android.graphics.RegionIterator iterator = new android.graphics.RegionIterator(region);
        android.graphics.Rect rect = new android.graphics.Rect();
        int spans = 0;
        while (iterator.next(rect)) {
            builder.append(';').append(rect.left).append(',').append(rect.top)
                    .append(',').append(rect.right).append(',').append(rect.bottom);
            spans++;
            if (spans > 4096) {
                // 截断前缀不能代表完整区域：超预算时禁止去重。
                return null;
            }
        }
        return builder.toString();
    }

    private void scheduleRetry() {
        if (!passThroughEnabled) {
            return;
        }
        if (retryAttempts >= MAX_RETRIES) {
            host.onTouchRegionRejected("surface-not-ready");
            return;
        }
        retryAttempts++;
        main.removeCallbacks(retryRunnable);
        main.postDelayed(retryRunnable, RETRY_STEP_MS);
    }

    private Region fullWindowRegionOrNull() {
        int width = rootView != null ? rootView.getWidth() : 0;
        int height = rootView != null ? rootView.getHeight() : 0;
        if (width <= 0 || height <= 0) {
            return null;
        }
        return new Region(0, 0, width, height);
    }

    private Region buildRegion(RegionSnapshotStore.Snapshot snapshot) {
        if (rootView == null) {
            return null;
        }
        int rootW = rootView.getWidth();
        int rootH = rootView.getHeight();
        int viewW = inputView != null ? inputView.getWidth() : rootW;
        int viewH = inputView != null ? inputView.getHeight() : rootH;
        if (rootW <= 0 || rootH <= 0 || viewW <= 0 || viewH <= 0) {
            return null;
        }
        if (snapshot.cssWidth <= 0f || snapshot.cssHeight <= 0f) {
            return null;
        }
        // inputView 相对 rootView 的偏移（正常为 0）
        int offsetX = 0;
        int offsetY = 0;
        if (inputView != null && inputView != rootView) {
            int[] rootLoc = new int[2];
            int[] inputLoc = new int[2];
            rootView.getLocationInWindow(rootLoc);
            inputView.getLocationInWindow(inputLoc);
            offsetX = inputLoc[0] - rootLoc[0];
            offsetY = inputLoc[1] - rootLoc[1];
        }
        // 用实际像素 / CSS 视口校准，不直接信 dpr
        float scaleX = viewW / snapshot.cssWidth;
        float scaleY = viewH / snapshot.cssHeight;
        Region region = new Region();
        Rect scratch = new Rect();
        for (float[] rect : snapshot.rects) {
            int left = (int) Math.floor(rect[0] * scaleX) + offsetX;
            int top = (int) Math.floor(rect[1] * scaleY) + offsetY;
            int right = (int) Math.ceil(rect[2] * scaleX) + offsetX;
            int bottom = (int) Math.ceil(rect[3] * scaleY) + offsetY;
            if (right <= left || bottom <= top) {
                continue;
            }
            if (left < 0) left = 0;
            if (top < 0) top = 0;
            if (right > rootW) right = rootW;
            if (bottom > rootH) bottom = rootH;
            if (right <= left || bottom <= top) {
                continue;
            }
            scratch.set(left, top, right, bottom);
            region.op(scratch, Region.Op.UNION);
        }
        return region;
    }

    private void reportUnsupported(String reason) {
        if (unsupportedReported) {
            return;
        }
        unsupportedReported = true;
        if (host != null) {
            host.onTouchRegionUnsupported(reason);
        }
    }

    private static final class Parsed {
        long revision;
        String sessionId = "";
        float cssWidth;
        float cssHeight;
        float dpr;
        String portraitKey = "";
        String mode = "";
        boolean approximated;
        int level;
        float tolerance;
        float[][] rects;
    }

    /** 解析并校验载荷：拒绝 NaN/Infinity、越界尺寸、超量矩形、缺失 revision/session。 */
    private static Parsed parse(String json) throws Exception {
        if (json.length() > MAX_PAYLOAD_CHARS) {
            return null;
        }
        JSONObject object = new JSONObject(json);
        Parsed parsed = new Parsed();
        parsed.revision = object.optLong("revision", -1L);
        if (parsed.revision < 0) {
            return null;
        }
        parsed.sessionId = object.optString("pageSessionId", "");
        if (parsed.sessionId.isEmpty()) {
            return null;
        }
        parsed.cssWidth = (float) object.optDouble("cssW", 0d);
        parsed.cssHeight = (float) object.optDouble("cssH", 0d);
        parsed.dpr = (float) object.optDouble("dpr", 1d);
        parsed.portraitKey = object.optString("portraitKey", "");
        parsed.mode = object.optString("mode", "");
        parsed.approximated = object.optBoolean("approximated", false);
        parsed.level = (int) Math.max(0, Math.min(MAX_LEVEL, object.optLong("level", 0L)));
        parsed.tolerance = (float) object.optDouble("tolerance", 0d);
        if (!isFinite(parsed.tolerance) || parsed.tolerance < 0f) {
            parsed.tolerance = 0f;
        }
        if (!isFinite(parsed.cssWidth) || !isFinite(parsed.cssHeight)) {
            return null;
        }
        if (parsed.cssWidth < MIN_VIEWPORT || parsed.cssWidth > MAX_VIEWPORT
                || parsed.cssHeight < MIN_VIEWPORT || parsed.cssHeight > MAX_VIEWPORT) {
            return null;
        }
        JSONArray array = object.optJSONArray("rects");
        if (array == null) {
            return null;
        }
        if (array.length() > MAX_RECTS) {
            return null;
        }
        java.util.ArrayList<float[]> rects = new java.util.ArrayList<>(array.length());
        for (int index = 0; index < array.length(); index++) {
            JSONArray item = array.optJSONArray(index);
            if (item == null || item.length() != 4) {
                return null;
            }
            float x0 = (float) item.optDouble(0, Double.NaN);
            float y0 = (float) item.optDouble(1, Double.NaN);
            float x1 = (float) item.optDouble(2, Double.NaN);
            float y1 = (float) item.optDouble(3, Double.NaN);
            if (!isFinite(x0) || !isFinite(y0) || !isFinite(x1) || !isFinite(y1)) {
                return null;
            }
            if (x1 <= 0 || y1 <= 0 || x0 >= parsed.cssWidth || y0 >= parsed.cssHeight) {
                continue;
            }
            rects.add(new float[]{x0, y0, x1, y1});
        }
        parsed.rects = rects.toArray(new float[0][]);
        return parsed;
    }

    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
