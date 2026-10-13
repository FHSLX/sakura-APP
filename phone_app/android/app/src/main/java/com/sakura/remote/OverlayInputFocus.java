package com.sakura.remote;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * 悬浮窗「输入焦点」策略（纯决策，无 View/系统调用，可离线测试）。
 *
 * <p>背景（真机反馈）：点开对话框后焦点被一直占用，除立绘外其他界面不能操作。
 *
 * <p>根因：窗口初始带 {@code FLAG_NOT_FOCUSABLE}，而 Android 规定该 flag
 * <b>隐含</b> {@code FLAG_NOT_TOUCH_MODAL}。网页里输入框聚焦时，
 * {@code onInputFocusChanged(true)} 只清掉了 {@code FLAG_NOT_FOCUSABLE}，
 * 并没有显式补回 {@code FLAG_NOT_TOUCH_MODAL}，于是窗口同时变成
 * 「可聚焦 + 触摸模态」：它会吞掉整个屏幕上的触摸，底下的应用再也点不到。
 *
 * <p>所以这里的规则是：<b>任何</b>焦点切换都必须显式保留
 * {@code FLAG_NOT_TOUCH_MODAL}；初始还带 {@code FLAG_WATCH_OUTSIDE_TOUCH}，
 * 窗口才能在用户点到外面时收到 {@code ACTION_OUTSIDE} 并主动交还焦点。
 *
 * <p>不用「禁用输入」绕过：输入框仍然可以聚焦、软键盘仍然弹得出来，
 * 只是窗口永远不再独占触摸。
 */
final class OverlayInputFocus {

    private OverlayInputFocus() {
    }

    /**
     * 建窗时的基础 flags。
     *
     * <ul>
     *   <li>{@code FLAG_NOT_FOCUSABLE}：默认不抢其他应用的输入；</li>
     *   <li>{@code FLAG_NOT_TOUCH_MODAL}：窗口外触摸交给下层，不吞整屏；</li>
     *   <li>{@code FLAG_WATCH_OUTSIDE_TOUCH}：窗口外的 DOWN 会以
     *       {@code ACTION_OUTSIDE} 回传，用它主动交还输入焦点；</li>
     *   <li>原有的 {@code FLAG_LAYOUT_NO_LIMITS}、{@code FLAG_HARDWARE_ACCELERATED}
     *       保持不变，根窗口几何与渲染不受影响。</li>
     * </ul>
     */
    static int baseFlags() {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
    }

    /**
     * 输入框聚焦/失焦后的 flags。
     *
     * <p>先把 {@code FLAG_NOT_TOUCH_MODAL} 补上（幂等），再按需增删
     * {@code FLAG_NOT_FOCUSABLE}。这样无论从哪个中间状态进来，结果都不会
     * 是触摸模态。
     */
    static int withInputFocus(int flags, boolean focused) {
        int next = keepNonModal(flags);
        if (focused) {
            next &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        } else {
            next |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        }
        return next;
    }

    /** 只保证「非触摸模态」这一条不变量，其余 flags 原样保留。 */
    static int keepNonModal(int flags) {
        return flags | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    }

    /** 该 flags 是否已经带「非触摸模态」这个不变量。 */
    static boolean isNonModal(int flags) {
        return (flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0;
    }

    /** 该 flags 是否处于「可聚焦」状态（输入框已聚焦）。 */
    static boolean isInputFocusable(int flags) {
        return (flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0;
    }

    /**
     * 这次触摸是不是「窗口外」事件。
     *
     * <p>只有显式带 {@code FLAG_WATCH_OUTSIDE_TOUCH} 才会收到；收到即说明用户
     * 点了别的地方，此时应立刻交还输入焦点并收起软键盘。
     */
    static boolean isOutsideTouch(int action) {
        return (action & MotionEvent.ACTION_MASK) == MotionEvent.ACTION_OUTSIDE;
    }

    /**
     * 是否是需要用来「关闭输入」的按键。
     *
     * <p>返回键在悬浮窗里没有 Activity 可回退，语义就是「收起键盘 / 退出输入态」。
     */
    static boolean isFocusReleaseKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK;
    }
}
