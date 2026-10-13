package com.sakura.remote;

/**
 * 截屏授权 Activity 的生命周期裁决（RV-01 / RV-08 / INT-03）。
 *
 * 旧实现的三个问题都在这里收口：
 *   1. manifest 里 {@code noHistory=true} 会让「再去申请系统授权」的 Activity
 *      收不到 {@code onActivityResult}（Android 官方文档明确说明）——
 *      修正见 AndroidManifest，本类负责其余生命周期规则；
 *   2. 等悬浮窗移除超时后仍然去申请授权；没有 RESUMED/focus 门槛；
 *   3. 旋转导致 onDestroy 时把在途请求一并取消（明明是配置变化）。
 *
 * 第三轮（INT-03）再收口一处：旧实现把「!isFinishing && !isDestroyed」当 resumed，
 * 又用「等待次数超过阈值」当有焦点，等于等几次就强行后台拉起系统授权。
 * 现在 resumed 只认 onResume/onPause，焦点只认真实 hasWindowFocus()，
 * 唯一兜底是总超时期限，见 {@link #nextWaitAction}。
 *
 * 纯 Java 无 Android 依赖，可用普通 JVM 跑「旋转不取消 / 返回取消 / 只弹一次 /
 * 无焦点超时不强启 / 前提满足恰好一次」等行为断言。
 */
final class CaptureConsentPolicy {

    /** onCreate 的处置。 */
    enum CreateDecision {
        /** 请求仍有效：继续隐藏悬浮窗并等待让位。 */
        PROCEED,
        /** 请求已失效（陈旧/超时）：直接结束，不去申请授权。 */
        FINISH_STALE
    }

    /** onDestroy 的处置。 */
    enum DestroyDecision {
        /** 已经拿到结果：什么都不用做。 */
        NONE,
        /** 配置变化（旋转）：保留请求，重建后继续等待系统授权结果。 */
        KEEP_REQUEST,
        /** 用户返回 / 被系统结束：终结在途请求。 */
        CANCEL_REQUEST
    }

    /** 授权 Activity 等待「让位 + 焦点」的下一步动作（INT-03）。 */
    enum WaitAction {
        /** 继续等待：悬浮窗未让位。 */
        WAIT_OVERLAY,
        /** 继续等待：已让位且已 resumed，但还没有**真实**窗口焦点。 */
        WAIT_FOCUS,
        /** 前提全部满足：恰好申请一次系统授权。 */
        REQUEST_CONSENT,
        /** 总超时期限内没等到前提：明确失败，绝不强行当有焦点。 */
        FAIL_NO_YIELD
    }

    /** 授权 Activity 等待循环的每步处置。 */
    enum WaitDecision {
        WAIT_OVERLAY,
        WAIT_FOCUS,
        REQUEST_CONSENT,
        FAIL_NO_YIELD
    }

    private CaptureConsentPolicy() {
    }

    static CreateDecision onCreate(boolean requestStillActive) {
        return requestStillActive ? CreateDecision.PROCEED : CreateDecision.FINISH_STALE;
    }

    /** 授权弹窗只开一次：已经请求过就不再请求（旋转重建后等待原结果）。 */
    static boolean shouldRequestConsent(boolean consentAlreadyRequested) {
        return !consentAlreadyRequested;
    }

    static DestroyDecision onDestroy(boolean resultHandled,
                                     boolean changingConfigurations) {
        if (resultHandled) {
            return DestroyDecision.NONE;
        }
        if (changingConfigurations) {
            return DestroyDecision.KEEP_REQUEST;
        }
        return DestroyDecision.CANCEL_REQUEST;
    }

    /**
     * 真实生命周期版本的等待裁决（INT-03）。
     *
     * 与 {@link #onWaitTick} 的区别：这里**不接受**「等够次数就当有焦点」。
     * 焦点只认 {@code Activity.hasWindowFocus()} 的真实结果，resumed 只认
     * onResume/onPause 维护的状态；两者与「悬浮窗已移除」必须在同一时刻同时成立。
     * 唯一的时间兜底是总超时期限，超过就明确失败（期限先于前提判定），
     * 因此「暂停很久再恢复」也不能后台强行拉起系统授权。
     *
     * @param overlayAttached 悬浮窗是否仍然挂在屏幕上
     * @param resumed         Activity 是否处于 onResume 之后、onPause 之前
     * @param focused         真实 hasWindowFocus() 结果
     * @param elapsedMs       本实例等待已经过的毫秒（跨暂停累计）
     * @param timeoutMs       总超时期限
     */
    static WaitAction nextWaitAction(boolean overlayAttached, boolean resumed,
                                     boolean focused, long elapsedMs, long timeoutMs) {
        // 先判期限：暂停/旋转都会累计 elapsed，超过总期限一律失败，
        // 不能让「暂停很久再恢复」把等待窗口无限续命。
        if (timeoutMs > 0L && elapsedMs >= timeoutMs) {
            return WaitAction.FAIL_NO_YIELD;
        }
        if (!overlayAttached && resumed && focused) {
            return WaitAction.REQUEST_CONSENT;
        }
        if (overlayAttached) {
            return WaitAction.WAIT_OVERLAY;
        }
        return WaitAction.WAIT_FOCUS;
    }

    /**
     * 等待「悬浮窗已移除 + 本 Activity 已 RESUMED 且有焦点」。
     *
     * 超时不再去申请授权，而是明确失败终结 —— 否则悬浮窗还没让位就弹授权框，
     * MIUI 会把确认框压掉，用户看到的是「点了没反应」，请求却一直挂在 registry。
     *
     * 保留这个「按次数」的版本是为了兼容既有单测与静态调用点；
     * 真实 Activity 的等待循环用 {@link #nextWaitAction}，它不会把次数当焦点。
     *
     * @param attempts 已经重试的次数（从 0 开始）
     */
    static WaitDecision onWaitTick(boolean overlayAttached, boolean resumed,
                                   boolean focused, int attempts, int maxAttempts) {
        int next = attempts + 1;
        if (!overlayAttached && resumed && focused) {
            return WaitDecision.REQUEST_CONSENT;
        }
        if (next >= maxAttempts) {
            return WaitDecision.FAIL_NO_YIELD;
        }
        return overlayAttached ? WaitDecision.WAIT_OVERLAY : WaitDecision.WAIT_FOCUS;
    }
}
