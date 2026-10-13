package com.sakura.remote;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * R2 精确穿透的**最小探针**（仅 debug 源集，不进正式用户流程）。
 *
 * 目的：在没有网络、没有角色、没有模型的前提下，用一张合成图和已知 Region
 * 验证：
 *   1) 透明洞处的轻点是否真的落到下层（同 UID 的探针 Activity 计数器）；
 *   2) 人物实体/不透明边框处是否仍由悬浮窗接管；
 *   3) API33+ 才调用 setTouchableRegion，旧系统不调用；
 *   4) 关闭后是否恢复整窗可交互。
 *
 * 用法：debug 包安装后执行
 *   adb shell am start -n com.sakura.remote/.TouchRegionProbeActivity
 * 然后点「应用透明洞」，再点悬浮窗中央的透明洞观察计数。
 *
 * 它刻意不复用网页几何：探针用**已知常量矩形**构造载荷，从而把「公开 API 调用
 * 链路」与「前端 alpha 轮廓算法」分开验证。
 */
public class TouchRegionProbeActivity extends Activity {

    /** 合成图尺寸（像素）。 */
    private static final int IMG_W = 240;
    private static final int IMG_H = 360;
    /** 不透明边框宽度。 */
    private static final int BORDER = 40;
    /** 中央透明洞（图像坐标）。 */
    private static final int HOLE_L = 80;
    private static final int HOLE_T = 120;
    private static final int HOLE_R = 160;
    private static final int HOLE_B = 240;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout overlayRoot;
    private ImageView probeView;
    private OverlayTouchRegionController controller;
    private TextView statusView;
    private TextView counterView;

    private int downCount;
    private int upCount;
    /** 探针自己的单调 revision（控制器要求严格递增，否则按陈旧载荷丢弃）。 */
    private long probeRevision;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(24, 24, 24, 24);

        TextView title = new TextView(this);
        title.setText("精确穿透探针（debug）\n"
                + "1. 点「显示探针窗口」\n"
                + "2. 点「应用透明洞」\n"
                + "3. 点悬浮窗中央的洞 → 该 Activity 的 DOWN/UP 计数应各 +1\n"
                + "4. 点不透明边框 → 计数不应变化（被探针窗口吃掉）");
        content.addView(title);

        statusView = new TextView(this);
        content.addView(statusView);

        counterView = new TextView(this);
        counterView.setTextSize(20f);
        content.addView(counterView);

        content.addView(button("显示探针窗口", v -> showProbeWindow()));
        content.addView(button("应用透明洞", v -> applyHoleRegion()));
        content.addView(button("整窗可交互（关闭穿透）", v -> disablePassThrough()));
        content.addView(button("移除探针窗口", v -> removeProbeWindow()));
        content.addView(button("刷新状态", v -> refreshStatus()));

        setContentView(content);
        refreshStatus();
    }

    private Button button(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setOnClickListener(listener);
        return button;
    }

    // ---- 探针悬浮窗 --------------------------------------------------

    private void showProbeWindow() {
        if (overlayRoot != null) {
            return;
        }
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        params = new WindowManager.LayoutParams(
                IMG_W, IMG_H, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 40;
        params.y = 120;

        overlayRoot = new FrameLayout(this);
        probeView = new ImageView(this);
        probeView.setImageBitmap(buildSyntheticBitmap());
        probeView.setScaleType(ImageView.ScaleType.FIT_XY);
        overlayRoot.addView(probeView, new FrameLayout.LayoutParams(IMG_W, IMG_H));

        controller = new OverlayTouchRegionController(overlayRoot, probeView,
                new OverlayTouchRegionController.Host() {
                    @Override
                    public void onTouchRegionApplied(long revision, int rectCount,
                                                     boolean fallbackFull) {
                        runOnUiThread(() -> statusView.setText("区域已应用 revision=" + revision
                                + " rects=" + rectCount
                                + (fallbackFull ? "（回退整窗！）" : "")));
                    }

                    @Override
                    public void onTouchRegionRejected(String reason) {
                        runOnUiThread(() -> statusView.setText("区域被拒绝：" + reason));
                    }

                    @Override
                    public void onTouchRegionUnsupported(String reason) {
                        runOnUiThread(() -> statusView.setText("本机不支持精确穿透：" + reason));
                    }
                });

        try {
            windowManager.addView(overlayRoot, params);
        } catch (Exception error) {
            overlayRoot = null;
            statusView.setText("添加探针窗口失败：" + error.getClass().getSimpleName());
        }
    }

    private void removeProbeWindow() {
        disablePassThrough();
        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
                // 已移除
            }
        }
        if (controller != null) {
            controller.destroy();
            controller = null;
        }
        overlayRoot = null;
        probeView = null;
    }

    /** 合成图：不透明边框 + 内部透明洞。 */
    private Bitmap buildSyntheticBitmap() {
        Bitmap bitmap = Bitmap.createBitmap(IMG_W, IMG_H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.TRANSPARENT);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.rgb(240, 120, 170));
        // 四条边框（中间留出 HOLE 区域完全透明）
        canvas.drawRect(0, 0, IMG_W, BORDER, paint);
        canvas.drawRect(0, IMG_H - BORDER, IMG_W, IMG_H, paint);
        canvas.drawRect(0, BORDER, BORDER, IMG_H - BORDER, paint);
        canvas.drawRect(IMG_W - BORDER, BORDER, IMG_W, IMG_H - BORDER, paint);
        // 洞的上方/下方补两块实体，确保洞四周都是不透明（形成真正的内部洞）
        canvas.drawRect(BORDER, BORDER, HOLE_L, HOLE_T, paint);
        canvas.drawRect(HOLE_R, BORDER, IMG_W - BORDER, HOLE_T, paint);
        canvas.drawRect(BORDER, HOLE_B, HOLE_L, IMG_H - BORDER, paint);
        canvas.drawRect(HOLE_R, HOLE_B, IMG_W - BORDER, IMG_H - BORDER, paint);
        return bitmap;
    }

    /**
     * 用已知常量矩形构造载荷并应用。
     *
     * 载荷坐标与探针视图同尺寸（cssW/H = 视图像素），原生校准比例即为 1:1，
     * 因此 Region 与合成图坐标一一对应。
     */
    private void applyHoleRegion() {
        if (controller == null || probeView == null) {
            statusView.setText("先点「显示探针窗口」");
            return;
        }
        if (!controller.isSupported()) {
            statusView.setText("API < 33：本机不支持精确穿透，探针不调用 setTouchableRegion");
            return;
        }
        // 四条不透明边框 + 洞四周实体块（与 buildSyntheticBitmap 一致）
        float[][] rects = new float[][]{
                {0, 0, IMG_W, BORDER},
                {0, IMG_H - BORDER, IMG_W, IMG_H},
                {0, BORDER, BORDER, IMG_H - BORDER},
                {IMG_W - BORDER, BORDER, IMG_W, IMG_H - BORDER},
                {BORDER, BORDER, HOLE_L, HOLE_T},
                {HOLE_R, BORDER, IMG_W - BORDER, HOLE_T},
                {BORDER, HOLE_B, HOLE_L, IMG_H - BORDER},
                {HOLE_R, HOLE_B, IMG_W - BORDER, IMG_H - BORDER},
        };
        controller.setPassThroughEnabled(true);
        // RV-03：控制器要求先登记当前页会话，未登记会话的载荷会被拒绝
        controller.beginSession("probe-session");
        String result = controller.applyPayload(buildPayload(rects));
        statusView.setText("applyPayload -> " + result
                + (controller.isPassThroughEnabled() ? "（已开启）" : "（未开启）"));
    }

    private void disablePassThrough() {
        if (controller != null) {
            controller.setPassThroughEnabled(false);
        }
        statusView.setText("已关闭穿透：整窗可交互");
    }

    private String buildPayload(float[][] rects) {
        // 手工拼 JSON：探针只需要固定结构，避免 org.json 的受检异常
        StringBuilder rectJson = new StringBuilder("[");
        for (int index = 0; index < rects.length; index++) {
            float[] rect = rects[index];
            if (index > 0) {
                rectJson.append(',');
            }
            rectJson.append('[').append(rect[0]).append(',').append(rect[1])
                    .append(',').append(rect[2]).append(',').append(rect[3]).append(']');
        }
        rectJson.append(']');
        int width = Math.max(1, probeView.getWidth() > 0 ? probeView.getWidth() : IMG_W);
        int height = Math.max(1, probeView.getHeight() > 0 ? probeView.getHeight() : IMG_H);
        return "{\"v\":1,\"revision\":" + (++probeRevision)
                + ",\"pageSessionId\":\"probe-session\",\"cssW\":" + width
                + ",\"cssH\":" + height
                + ",\"dpr\":1,\"portraitKey\":\"__probe__\",\"mode\":\"probe\","
                + "\"approximated\":false,\"rects\":" + rectJson + "}";
    }

    private void refreshStatus() {
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
        statusView.setText("SDK=" + Build.VERSION.SDK_INT
                + " 支持精确穿透=" + supported
                + (overlayRoot == null ? " 探针窗口未显示" : " 探针窗口已显示"));
        counterView.setText("本 Activity 收到：DOWN=" + downCount + " UP=" + upCount);
    }

    // ---- 计数器：透明洞的点击应当落到这里 ------------------------------

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            downCount++;
        } else if (event.getActionMasked() == android.view.MotionEvent.ACTION_UP) {
            upCount++;
        }
        runOnUiThread(() -> counterView.setText(
                "本 Activity 收到：DOWN=" + downCount + " UP=" + upCount));
        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDestroy() {
        removeProbeWindow();
        super.onDestroy();
    }
}
