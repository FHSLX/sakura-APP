package com.sakura.remote;

import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.FrameLayout;

/**
 * 悬浮窗根容器：把「窗口外的触摸 / 返回键 / 窗口失焦」变成显式回调。
 *
 * <p>为什么需要单独一层容器：{@link OverlayService} 里的窗口是可聚焦的
 * （输入框聚焦时才会清 {@code FLAG_NOT_FOCUSABLE}），系统级的
 * {@code ACTION_OUTSIDE} 和按键只会派发到窗口的根 View。默认 FrameLayout
 * 不做任何判定，用户点到别的应用时我们不会察觉，焦点就一直挂在悬浮窗上。
 *
 * <p>这里只做三件事，判定逻辑全部委托给可离线测试的
 * {@link OverlayInputFocus}：
 * <ul>
 *   <li>{@code ACTION_OUTSIDE}（需 {@code FLAG_WATCH_OUTSIDE_TOUCH}）→ 交还焦点；</li>
 *   <li>返回键 → 收起键盘、退出输入态（由监听方决定是否消费）；</li>
 *   <li>窗口失焦 → 交还焦点并收起 IME。</li>
 * </ul>
 *
 * <p>它不改变任何布局/尺寸行为：仍然是一个 FrameLayout，根几何不变。
 */
public class OverlayFocusRootView extends FrameLayout {

    /** 宿主回调：都在主线程调用。 */
    public interface Listener {
        /** 用户在窗口外按下了手指（ACTION_OUTSIDE）。 */
        void onOutsideTouch();

        /**
         * 收到返回键。
         *
         * @return true 表示已消费（宿主已退出输入态），调用方不再向下传递
         */
        boolean onBackKey();

        /** 窗口失去输入焦点。 */
        void onWindowFocusLost();
    }

    private Listener listener;

    public OverlayFocusRootView(Context context) {
        super(context);
    }

    public void setFocusListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        final Listener target = listener;
        if (target != null && event != null
                && OverlayInputFocus.isOutsideTouch(event.getAction())) {
            target.onOutsideTouch();
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final Listener target = listener;
        if (target != null && event != null
                && event.getAction() == KeyEvent.ACTION_UP
                && OverlayInputFocus.isFocusReleaseKey(event.getKeyCode())) {
            if (target.onBackKey()) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        final Listener target = listener;
        if (!hasWindowFocus && target != null) {
            target.onWindowFocusLost();
        }
    }
}
