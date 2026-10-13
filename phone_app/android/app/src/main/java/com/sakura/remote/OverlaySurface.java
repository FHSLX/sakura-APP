package com.sakura.remote;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮窗内容的**形态切换器**（FIX-BLANK-01）。
 *
 * 只有两种形态，且都不可能是「透明空白大窗」：
 *   - {@link Mode#FULL}：完整页面（WebView），只在已确认有可见立绘时显示；
 *   - {@link Mode#CARD}：**有界且可见**的状态/恢复卡片，尺寸固定（不随屏幕放大），
 *     用于加载中、失败恢复与手动重试入口。
 *
 * 为什么必须在窗口内容里做这件事：
 *   旧实现「失败就 setVisibility(INVISIBLE)」，窗口本身仍是 alpha=1、整矩形
 *   接收触摸 —— 用户看不到任何东西却点不动桌面。现在失败的页面内容根本不
 *   参与显示，窗口要么装完整页面，要么装这张有界可见卡片，要么被移除。
 */
public final class OverlaySurface {

    public enum Mode {
        FULL,
        CARD
    }

    /** 卡片交互回调。 */
    public interface Actions {
        void onRetryTapped();

        void onCloseTapped();
    }

    /** 卡片尺寸（dp）：有界，不随屏幕尺寸放大。 */
    static final int CARD_WIDTH_DP = 236;
    static final int CARD_HEIGHT_DP = 148;

    private final Context context;
    private FrameLayout container;

    private final View fullView;
    private final LinearLayout cardView;
    private final TextView cardTitle;
    private final TextView cardMessage;
    private final Button retryButton;
    private final Button closeButton;

    private Mode mode = Mode.CARD;
    private Actions actions;

    /** 只构建视图，不挂载；容器由 {@link #bindContainer} 在宿主就绪后注入。 */
    public OverlaySurface(Context context, View fullView) {
        this.context = context;
        this.fullView = fullView;

        cardView = new LinearLayout(context);
        cardView.setOrientation(LinearLayout.VERTICAL);
        cardView.setGravity(Gravity.CENTER_VERTICAL);
        cardView.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setColor(0xF21B1B1F);        // 不透明深色：卡片必须真实可见
        cardBg.setCornerRadius(dp(14));
        cardBg.setStroke(dp(1), 0x33FFFFFF);
        cardView.setBackground(cardBg);
        // 卡片必须吸收**窗口内**的输入：否则点击会穿透到下面仍在布局的 WebView
        // （页面并未销毁，只是被覆盖）。窗口本身有界，窗口外的触摸不受影响。
        cardView.setClickable(true);

        cardTitle = new TextView(context);
        cardTitle.setTextColor(0xFFF2F2F5);
        cardTitle.setTextSize(14f);
        cardTitle.setSingleLine(true);
        cardView.addView(cardTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        cardMessage = new TextView(context);
        cardMessage.setTextColor(0xFFB9B9C4);
        cardMessage.setTextSize(12f);
        cardMessage.setMaxLines(2);
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(6);
        cardView.addView(cardMessage, messageParams);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonsParams.topMargin = dp(12);

        retryButton = new Button(context);
        retryButton.setText("重试");
        retryButton.setAllCaps(false);
        retryButton.setTextSize(12f);
        retryButton.setOnClickListener(v -> {
            Actions callback = actions;
            if (callback != null) {
                callback.onRetryTapped();
            }
        });
        buttons.addView(retryButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        closeButton = new Button(context);
        closeButton.setText("关闭");
        closeButton.setAllCaps(false);
        closeButton.setTextSize(12f);
        closeButton.setOnClickListener(v -> {
            Actions callback = actions;
            if (callback != null) {
                callback.onCloseTapped();
            }
        });
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        closeParams.leftMargin = dp(8);
        buttons.addView(closeButton, closeParams);

        cardView.addView(buttons, buttonsParams);
    }

    /** 由宿主在容器就绪后调用；此时才允许把内容挂进容器。 */
    public void bindContainer(FrameLayout container) {
        if (container != null) {
            this.container = container;
        }
        setMode(this.mode);
    }

    public void setActions(Actions actions) {
        this.actions = actions;
    }

    /**
     * 切换窗口内容形态。
     *
     * **关键修复（本轮现场缺陷）**：CARD 不再把页面视图摘出视图树。
     *
     * 旧实现是 {@code container.removeAllViews(); container.addView(cardView)} ——
     * fullView（内含 WebView）因此失去 parent，不再参与 measure/layout/draw。
     * 注释里写「WebView 在该卡片内正常工作」，实际根本不成立：页面被摘掉后
     * 立绘的预加载与解码无法推进，网页永远送不出 ready 信号，窗口永远升不到
     * FULL。现场表现就是「有界卡片一直显示 / 立绘永不出现」。
     *
     * 现在的规则：
     *   - fullView **始终**留在容器里（已 attach、可 measure/layout、非 GONE/INVISIBLE）；
     *   - CARD 只是在它上面**覆盖**一层不透明、有界、可点击的卡片（窗口尺寸由
     *     OverlayService 设为卡片尺寸，卡片填满这个有界窗口）；
     *   - FULL 只移除卡片层，**不**销毁/替换页面视图，WebView 生命周期保持单一。
     */
    public void setMode(Mode next) {
        this.mode = next;
        if (container == null) {
            return;
        }
        ensureFullViewAttached();
        if (next == Mode.FULL) {
            removeCardLayer();
        } else {
            ensureCardLayer();
        }
        // 卡片的实际窗口尺寸由 OverlayService 依据 mode 设定（有界）：这里只保证
        // 容器内部布局填满窗口，窗口本身不可能是「透明大矩形」。
    }

    /** 页面视图必须始终挂在容器里（幂等；从旧 parent 摘除后再挂）。 */
    private void ensureFullViewAttached() {
        if (fullView.getParent() == container) {
            return;
        }
        if (fullView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) fullView.getParent()).removeView(fullView);
        }
        container.addView(fullView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** 在页面视图之上覆盖卡片层（已在则不动，保证不重复 parent）。 */
    private void ensureCardLayer() {
        if (cardView.getParent() == container) {
            return;
        }
        if (cardView.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) cardView.getParent()).removeView(cardView);
        }
        // 后 add 的子 View 在 FrameLayout 中位于更上层：卡片覆盖页面。
        container.addView(cardView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** 只移除卡片层；页面视图继续留在视图树里（FULL 时不做销毁/替换）。 */
    private void removeCardLayer() {
        if (cardView.getParent() == container) {
            container.removeView(cardView);
        }
    }

    public Mode mode() {
        return mode;
    }

    /** 状态卡片内容（全部为去敏稳定文案，不含 URL/token/用户数据）。 */
    public void updateCard(String title, String message, boolean retryEnabled) {
        cardTitle.setText(title == null ? "" : title);
        cardMessage.setText(message == null ? "" : message);
        retryButton.setEnabled(retryEnabled);
        retryButton.setVisibility(View.VISIBLE);
        closeButton.setVisibility(View.VISIBLE);
    }

    /** 卡片尺寸（设备像素）。 */
    public int cardWidthPx() {
        return dp(CARD_WIDTH_DP);
    }

    public int cardHeightPx() {
        return dp(CARD_HEIGHT_DP);
    }

    public View cardViewForTest() {
        return cardView;
    }

    /** 页面视图（内含 WebView）；测试用，验证 CARD 时它仍在视图树里。 */
    public View fullViewForTest() {
        return fullView;
    }

    /** 内容容器；测试用（父链/层序断言）。 */
    public FrameLayout containerForTest() {
        return container;
    }

    /** 卡片标题（测试用，只读）。 */
    public String cardTitleForTest() {
        return String.valueOf(cardTitle.getText());
    }

    /** 卡片正文（测试用，只读）。 */
    public String cardMessageForTest() {
        return String.valueOf(cardMessage.getText());
    }

    private int dp(int value) {
        float density = context.getResources().getDisplayMetrics().density;
        if (density <= 0f) {
            density = 1f;
        }
        return Math.round(value * density);
    }
}
