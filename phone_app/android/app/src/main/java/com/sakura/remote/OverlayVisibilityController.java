package com.sakura.remote;

import android.os.Handler;
import android.os.Looper;

/**
 * 悬浮窗「是否挂挂在屏幕上、以什么形态挂」的单一状态控制器。
 *
 * 为什么必须收口：
 *   旧代码有两个各自独立 removeView/addView 的隐藏理由（授权隐藏、
 *   截图隐藏），加上手写的 attached 判断。两个理由同时存在时，
 *   先结束的那个会把窗口加回来，而另一个理由还成立 —— 于是截图里出现
 *   悬浮窗、或者用户关掉桌宠后又被加回来。
 *
 * RV-08 / RV-09 把每个隐藏理由归属到发起它的 requestId/token；
 * 本轮（FIX-BLANK-01）再把**渲染可用性**并进来：
 *
 *   可挂载(renderAttachable) = 页面已 boot 且确认有可见立绘。
 *   不可挂载时窗口**必须真正移除**，绝不允许用「WebView INVISIBLE + 窗口仍
 *   alpha=1 整矩形收触摸」冒充安全降级 —— 那样用户什么都看不到，系统却仍把
 *   整块矩形当遮挡层，正是本轮要修的实际缺陷。
 *
 * 形态（{@link WindowPlan}）：
 *   - {@link #PLAN_DETACHED}：窗口完全移出 WindowManager（用户/截图/授权隐藏，
 *     或未允许卡片形态时）；
 *   - {@link #PLAN_CARD}：窗口以**有界且可见**的加载/恢复卡片形态挂载，
 *     加载中、超时、自动恢复与明确失败时使用，尺寸固定，不可能形成全屏透明遮挡；
 *   - {@link #PLAN_FULL}：完整悬浮窗，仅在 renderAttachable 成立时使用。
 *
 * shouldAttach = 用户启用 && 服务存活 && !授权隐藏 && !截图隐藏 && 可挂载。
 * 每个理由只负责自己：token 匹配才允许释放。
 */
public final class OverlayVisibilityController {

    /** 窗口形态。 */
    public enum WindowPlan {
        /** 完全移出 WindowManager（用户/截图/授权隐藏，或渲染失败且未提供恢复控件时）。 */
        DETACHED,
        /** 有界、可见的加载/恢复卡片（加载中、超时、恢复与明确失败时）。 */
        CARD,
        /** 完整悬浮窗（有可见立绘）。 */
        FULL
    }

    public interface Host {
        /** 窗口当前是否已经挂在 WindowManager 上。 */
        boolean isWindowAttached();

        /** 当前实际挂载形态；未挂载返回 null。 */
        WindowPlan attachedPlan();

        /** 按指定形态执行 addView/updateViewLayout；失败返回 false。 */
        boolean attachWindow(WindowPlan plan);

        /** 执行 removeView。 */
        void detachWindow();
    }

    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean userEnabled = true;
    private boolean serviceAlive;
    private boolean destroyed;
    /** 渲染可用性（FIX-BLANK-01）：默认 false，不能在无可见内容时挂完整窗口。 */
    private boolean renderAttachable;
    /**
     * 是否允许窗口以**有界可见卡片**形态挂载（FIX-RUNTIME-03）。
     *
     * 由渲染协调器按状态机阶段更新：READY 以外（加载中 / 超时 / 恢复 / 失败）
     * 都允许。为什么加载期也必须允许：初始 DETACHED + INVISIBLE WebView 会形成
     * 「等可见才挂载」和「先挂载才能渲染」的死循环，网页的立绘预加载与解码
     * 无法推进。卡片尺寸固定且有界、背景不透明，不会变成透明全屏遮挡。
     */
    private boolean cardAllowed;

    /** 授权流程隐藏的持有者 token（requestId）；null 表示没有该理由。 */
    private String permissionHiddenOwner;
    /** 截图隐藏的持有者 token（requestId）；null 表示没有该理由。 */
    private String captureHiddenOwner;

    /** 已排队的裁决任务，避免同一次变更重复 post。 */
    private boolean reconcilePending;

    public OverlayVisibilityController(Host host) {
        this.host = host;
    }

    public synchronized void setUserEnabled(boolean enabled) {
        userEnabled = enabled;
        reconcile();
    }

    public synchronized void setServiceAlive(boolean alive) {
        serviceAlive = alive;
        reconcile();
    }

    // ---- 渲染可用性（FIX-BLANK-01）----------------------------------

    /**
     * 更新渲染可用性。
     *
     * 只在「页面已 boot 且有可见立绘」时为 true。置 false 会立刻把窗口移出
     * WindowManager（不放任透明大窗继续拦点击）；置 true 只在没有其它隐藏
     * 理由时按完整窗口挂回，不会覆盖用户关闭/截图隐藏的意图。
     */
    public synchronized void setRenderAttachable(boolean attachable) {
        if (renderAttachable == attachable) {
            return;
        }
        renderAttachable = attachable;
        reconcile();
    }

    /**
     * 是否允许有界可见卡片参与窗口挂载。
     *
     * FIX-RUNTIME-03：名称由 recoveryCardAllowed 改为 cardAllowed —— 它不再只是
     * 「失败恢复控件」，而是**加载期也必须成立**的预加载承载形态。保留旧方法名
     * 作为同义入口，避免调用方遗漏。
     */
    public synchronized void setCardAllowed(boolean allowed) {
        if (cardAllowed == allowed) {
            return;
        }
        cardAllowed = allowed;
        reconcile();
    }

    /** 兼容旧调用点：与 {@link #setCardAllowed(boolean)} 同义。 */
    public synchronized void setRecoveryCardAllowed(boolean allowed) {
        setCardAllowed(allowed);
    }

    public synchronized boolean isCardAllowed() {
        return cardAllowed;
    }

    /** 兼容旧调用点。 */
    public synchronized boolean isRecoveryCardAllowed() {
        return cardAllowed;
    }

    public synchronized boolean isRenderAttachable() {
        return renderAttachable;
    }

    // ---- 授权隐藏（按 token 归属）----------------------------------

    /**
     * 建立/转移授权隐藏理由。
     *
     * token 通常是 requestId。新的 token 会成为当前持有者；旧持有者之后
     * 再来释放时因为 token 不匹配而被忽略 —— 这正是「返回前取消者不能清掉
     * 新任务隐藏理由」的落点。
     */
    public synchronized void acquirePermissionHidden(String token) {
        permissionHiddenOwner = normalize(token);
        reconcile();
    }

    /** 只有持有者才能释放授权隐藏。 */
    public synchronized void releasePermissionHidden(String token) {
        if (!matches(permissionHiddenOwner, token)) {
            return;
        }
        permissionHiddenOwner = null;
        reconcile();
    }

    // ---- 截图隐藏（按 token 归属）----------------------------------

    public synchronized void acquireCaptureHidden(String token) {
        captureHiddenOwner = normalize(token);
        reconcile();
    }

    public synchronized void releaseCaptureHidden(String token) {
        if (!matches(captureHiddenOwner, token)) {
            return;
        }
        captureHiddenOwner = null;
        reconcile();
    }

    public synchronized boolean isPermissionHidden() {
        return permissionHiddenOwner != null;
    }

    public synchronized boolean isCaptureHidden() {
        return captureHiddenOwner != null;
    }

    public synchronized String permissionHiddenOwner() {
        return permissionHiddenOwner;
    }

    public synchronized String captureHiddenOwner() {
        return captureHiddenOwner;
    }

    /** 是否存在任何隐藏理由（不含渲染可用性）。 */
    public synchronized boolean hasHiddenReason() {
        return permissionHiddenOwner != null || captureHiddenOwner != null;
    }

    /** 是否应该把窗口挂到 WindowManager 上（任何形态）。 */
    public synchronized boolean shouldAttach() {
        return desiredPlanLocked() != WindowPlan.DETACHED;
    }

    /**
     * 当前应采用的窗口形态。
     *
     * 隐藏理由优先于渲染可用性：有用户关闭/截图/授权隐藏时一律 DETACHED。
     * 没有隐藏理由时：
     *   - 有可见立绘 → FULL；
     *   - 其余（加载中 / 超时 / 恢复 / 失败）→ CARD（有界且可见，能承载预加载
     *     与恢复控件，也不可能是透明全屏遮挡）。
     */
    public synchronized WindowPlan desiredPlan() {
        return desiredPlanLocked();
    }

    private WindowPlan desiredPlanLocked() {
        if (destroyed || !userEnabled || !serviceAlive || hasHiddenReason()) {
            return WindowPlan.DETACHED;
        }
        if (renderAttachable) {
            return WindowPlan.FULL;
        }
        return cardAllowed ? WindowPlan.CARD : WindowPlan.DETACHED;
    }

    public synchronized boolean isWindowAttached() {
        return host.isWindowAttached();
    }

    /** 把当前状态应用到窗口；幂等，重复调用安全。 */
    public void reconcile() {
        synchronized (this) {
            if (reconcilePending) {
                return;
            }
            reconcilePending = true;
        }
        main.post(this::applyNow);
    }

    private void applyNow() {
        WindowPlan desired;
        synchronized (this) {
            reconcilePending = false;
            desired = desiredPlanLocked();
        }
        if (desired == WindowPlan.DETACHED) {
            if (host.isWindowAttached()) {
                host.detachWindow();
            }
            return;
        }
        if (!host.isWindowAttached()) {
            host.attachWindow(desired);
            return;
        }
        WindowPlan attached = host.attachedPlan();
        if (attached == desired) {
            return;
        }
        // 形态变化：优先原地换内容/尺寸；宿主不支持时移除后按新形态挂回。
        // 这里必须真正切换形态，不能留下「旧形态但以为已是新形态」的窗口。
        if (attached == null) {
            host.detachWindow();
        }
        if (!host.attachWindow(desired) && host.isWindowAttached()) {
            host.detachWindow();
        }
    }

    /** 仅用于测试：同步执行已排队的裁决。 */
    void applyPendingNowForTest() {
        main.post(this::applyNow);
    }

    /** 服务销毁：彻底释放，之后的回调不得再重加窗口。 */
    public void destroy() {
        synchronized (this) {
            destroyed = true;
            serviceAlive = false;
            permissionHiddenOwner = null;
            captureHiddenOwner = null;
            renderAttachable = false;
            cardAllowed = false;
            reconcilePending = false;
        }
        main.removeCallbacksAndMessages(null);
    }

    private static String normalize(String token) {
        return token == null ? "" : token;
    }

    /**
     * token 匹配规则：持有者为 null（无理由）时任何释放都不生效；
     * 释放 token 为 null/空表示「无条件释放当前理由」（仅内部/销毁用）。
     */
    private static boolean matches(String owner, String token) {
        if (owner == null) {
            return false;
        }
        if (token == null || token.isEmpty()) {
            return true;
        }
        return token.equals(owner);
    }
}
