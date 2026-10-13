package com.sakura.remote;

/**
 * 「透明处穿透」的窗口 alpha 安全阈值计算（纯 Java，可离线测）。
 *
 * <p>Android 12 起跨窗口触摸有「不可信触摸」安全规则：输入孔洞不能绕过遮挡
 * 判定，窗口 LayoutParams.alpha 必须不高于系统给出的 maximum obscuring opacity。
 *
 * <p>旧实现的缺陷：
 *   - 每次开启都把**当前**alpha 当成「原始值」保存，重复开启/重试后原始值被
 *     一步步改写成已降低的值，关闭时回不到最初不透明度；
 *   - **不支持精确穿透时也照降 alpha**（API&lt;33 或 ROM 降级），白变淡却换不来
 *     任何点击穿透。
 *
 * <p>本类保证：
 *   - 原始 alpha 只在第一次**受支持且真正要降低**时保存一次；重复开启不污染它；
 *   - 不支持时完全不改 alpha（{@code adjusted/reduced} 保持 false）；
 *   - 只在系统上限**确实更低**时才降低（避免不必要变淡）；
 *   - 关闭时精确恢复原始值，并清除「已调整」状态；
 *   - 幂等：同样的输入可以安全调用任意次；
 *   - 新窗口建立时用 {@link #reset()} 把基准清回默认，旧窗口的保存值不污染新窗口。
 */
final class PassThroughAlpha {

    private float originalAlpha = 1f;
    private boolean adjusted;
    private boolean reduced;

    /** 计算启用穿透时应设置的窗口 alpha（兼容旧调用：视为能力受支持）。 */
    float enable(float currentAlpha, float safeMax) {
        return enable(currentAlpha, safeMax, true);
    }

    /**
     * 计算启用穿透时应设置的窗口 alpha。
     *
     * @param currentAlpha 当前窗口 alpha（首次受支持开启时作为原始值保存）
     * @param safeMax      系统允许的最大遮挡不透明度；&lt;=0 或 &gt;=1 表示不限制
     * @param supported    本机/本 ROM 是否真的支持精确穿透；false 时**不改 alpha**
     */
    float enable(float currentAlpha, float safeMax, boolean supported) {
        float base = (currentAlpha > 0f && currentAlpha <= 1f) ? currentAlpha : 1f;
        if (!supported) {
            // 不支持的机型没有必要变淡：不动 alpha，也不进入「已调整」状态
            return base;
        }
        if (!adjusted) {
            originalAlpha = base;
            adjusted = true;
        }
        float limit = (safeMax > 0f && safeMax < 1f) ? safeMax * 0.98f : 1f;
        reduced = limit < originalAlpha;
        return reduced ? limit : originalAlpha;
    }

    /** 关闭穿透时恢复原始 alpha（没调整过时也返回原始值）。 */
    float disable() {
        float restored = originalAlpha > 0f ? originalAlpha : 1f;
        adjusted = false;
        reduced = false;
        return restored;
    }

    /** 新窗口建立：清除旧窗口的保存值，基准回到 1（不改调用方当前 alpha）。 */
    void reset() {
        originalAlpha = 1f;
        adjusted = false;
        reduced = false;
    }

    /** 当前是否处于「已按安全上限降低 alpha」的状态。 */
    boolean isReduced() {
        return reduced;
    }

    /** 是否保存过原始值（即当前处于受支持的穿透开启状态）。 */
    boolean isAdjusted() {
        return adjusted;
    }

    /** 首次开启时保存的原始 alpha。 */
    float originalAlpha() {
        return originalAlpha;
    }
}
