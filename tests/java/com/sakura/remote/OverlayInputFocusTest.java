package com.sakura.remote;

import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.WindowManager;

/**
 * 悬浮窗输入焦点策略的真实行为测试。
 *
 * <p>直接编译并运行生产类 {@link OverlayInputFocus}（编译期用真实
 * {@code android.jar}：static final int 常量会被内联，运行时不需要 Android）。
 *
 * <p>关键回归：旧实现只在聚焦时清 {@code FLAG_NOT_FOCUSABLE}，
 * 没有补回 {@code FLAG_NOT_TOUCH_MODAL} —— 窗口于是变成「可聚焦 + 触摸模态」，
 * 吞掉整屏触摸。这里逐条钉住不变量。
 */
public final class OverlayInputFocusTest {

    private static int failures;

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name
                + (ok ? "" : " -> " + String.valueOf(detail)));
        if (!ok) {
            failures++;
        }
    }

    public static void main(String[] args) {
        realConstantValues();
        baseFlagsKeepWindowNonModal();
        focusTransitionNeverBecomesTouchModal();
        focusTransitionsRoundTrip();
        outsideTouchAndBackKeyRecognition();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production source: OverlayInputFocus)");
    }

    private static void realConstantValues() {
        check("FLAG_NOT_FOCUSABLE 常量与 Android 一致",
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0x00000008,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        check("FLAG_NOT_TOUCH_MODAL 常量与 Android 一致",
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL == 0x00000020,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
        check("FLAG_WATCH_OUTSIDE_TOUCH 常量与 Android 一致",
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH == 0x00040000,
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);
        check("ACTION_OUTSIDE 常量与 Android 一致",
                MotionEvent.ACTION_OUTSIDE == 4, MotionEvent.ACTION_OUTSIDE);
        check("KEYCODE_BACK 常量与 Android 一致",
                KeyEvent.KEYCODE_BACK == 4, KeyEvent.KEYCODE_BACK);
    }

    private static void baseFlagsKeepWindowNonModal() {
        int flags = OverlayInputFocus.baseFlags();
        check("建窗即可聚焦=false",
                (flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0, flags);
        check("建窗就显式非触摸模态",
                OverlayInputFocus.isNonModal(flags), flags);
        check("建窗就观察窗口外触摸",
                (flags & WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH) != 0, flags);
        check("保留原有 LAYOUT_NO_LIMITS / HARDWARE_ACCELERATED（根几何不变）",
                (flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS) != 0
                        && (flags & WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0,
                flags);
        check("任何非触摸模态查询都成立", OverlayInputFocus.isNonModal(flags), flags);
    }

    private static void focusTransitionNeverBecomesTouchModal() {
        int flags = OverlayInputFocus.baseFlags();
        int focused = OverlayInputFocus.withInputFocus(flags, true);
        check("聚焦后清掉 NOT_FOCUSABLE（软键盘能弹出）",
                (focused & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0, focused);
        check("聚焦后仍显式非触摸模态（旧缺陷就在这条）",
                OverlayInputFocus.isNonModal(focused), focused);
        check("聚焦后其他 flags 原样保留",
                focused == (flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE),
                focused);

        int blurred = OverlayInputFocus.withInputFocus(focused, false);
        check("失焦恢复 NOT_FOCUSABLE",
                (blurred & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0, blurred);
        check("失焦后仍然非触摸模态",
                OverlayInputFocus.isNonModal(blurred), blurred);

        // 直接复现旧实现的输入：没有 NOT_TOUCH_MODAL 的初始 flags
        int legacy = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;
        int legacyFocused = OverlayInputFocus.withInputFocus(legacy, true);
        check("旧式初始 flags 聚焦后也会补上非触摸模态",
                OverlayInputFocus.isNonModal(legacyFocused), legacyFocused);
    }

    private static void focusTransitionsRoundTrip() {
        int flags = OverlayInputFocus.baseFlags();
        int once = OverlayInputFocus.withInputFocus(flags, true);
        int twice = OverlayInputFocus.withInputFocus(once, true);
        check("重复聚焦幂等", once == twice, twice);
        int offOnce = OverlayInputFocus.withInputFocus(once, false);
        int offTwice = OverlayInputFocus.withInputFocus(offOnce, false);
        check("重复失焦幂等", offOnce == offTwice, offTwice);
        check("失焦回到建窗状态", offOnce == flags, offOnce);
    }

    private static void outsideTouchAndBackKeyRecognition() {
        check("ACTION_OUTSIDE 被识别为窗口外触摸",
                OverlayInputFocus.isOutsideTouch(MotionEvent.ACTION_OUTSIDE), true);
        check("ACTION_DOWN 不是窗口外触摸",
                !OverlayInputFocus.isOutsideTouch(MotionEvent.ACTION_DOWN), false);
        check("ACTION_MOVE 不是窗口外触摸",
                !OverlayInputFocus.isOutsideTouch(MotionEvent.ACTION_MOVE), false);
        check("ACTION_UP 不是窗口外触摸",
                !OverlayInputFocus.isOutsideTouch(MotionEvent.ACTION_UP), false);
        check("返回键被识别为释放焦点键",
                OverlayInputFocus.isFocusReleaseKey(KeyEvent.KEYCODE_BACK), true);
        check("HOME 键不是释放焦点键",
                !OverlayInputFocus.isFocusReleaseKey(KeyEvent.KEYCODE_HOME), false);
    }
}
