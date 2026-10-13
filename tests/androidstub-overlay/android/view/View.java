package android.view;

import android.content.Context;
import android.graphics.drawable.Drawable;

/**
 * 最小 View 替身（悬浮窗可见性测试专用）。
 *
 * 记录 attach 状态、尺寸与可见性；真实 PetWindow/OverlaySurface 直接操作它。
 * attach 默认 false，模拟「还没挂到 WindowManager」。
 */
public class View {

    public static final int VISIBLE = 0;
    public static final int INVISIBLE = 4;
    public static final int GONE = 8;

    public boolean attached;
    /**
     * 父容器。
     *
     * 为什么必须有：真实 {@code isAttachedToWindow()} 是**沿父链**判断的 ——
     * 一个子 View 只要挂在已 attach 的窗口根下面就是 attached，哪怕它自己的
     * attached 标记从没被设置过。旧替身只看自己的 {@code attached} 字段，
     * 于是「被 removeAllViews() 摘掉的孤立 WebView」也会被当成已 attach，
     * 正好掩盖了本轮的真实缺陷（CARD 时页面脱离视图树）。现在改为继承父链。
     */
    public ViewGroup parent;
    public int width;
    public int height;
    public int visibility = VISIBLE;
    public Drawable background;
    public boolean clipToOutline;
    public ViewOutlineProvider outlineProvider;
    public boolean clickable;
    public boolean enabled = true;
    public android.webkit.WebSettings settings = new android.webkit.WebSettings();

    public View() {
    }

    public View(Context context) {
        this.context = context;
    }

    /** 视图上下文（App 文件选择客户端需要从 WebView 取 Context 启动辅助页）。 */
    protected Context context;

    public Context getContext() {
        return context;
    }

    /** 最小替身：真实 View 的 getSettings 在 WebView 上，这里为测试转发。 */
    public android.webkit.WebSettings getSettings() {
        return settings;
    }

    /** 父容器（没有父容器时为 null）。 */
    public ViewGroup getParent() {
        return parent;
    }

    /**
     * 是否已附着到窗口：**沿父链继承**。
     *
     * 根 View（parent == null）看自己的 attached 标记；子 View 一律继承父容器的
     * 判断结果。这样「不在视图树里的孤立子 View」不会被误判成已 attach。
     */
    public boolean isAttachedToWindow() {
        if (parent != null) {
            return parent.isAttachedToWindow();
        }
        return attached;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getVisibility() {
        return visibility;
    }

    public void setVisibility(int visibility) {
        this.visibility = visibility;
    }

    public void setBackground(Drawable background) {
        this.background = background;
    }

    public Drawable getBackground() {
        return background;
    }

    public void setBackgroundColor(int color) {
    }

    public void setClipToOutline(boolean clip) {
        this.clipToOutline = clip;
    }

    public void setOutlineProvider(ViewOutlineProvider provider) {
        this.outlineProvider = provider;
    }

    public ViewOutlineProvider getOutlineProvider() {
        return outlineProvider;
    }

    public void setClickable(boolean clickable) {
        this.clickable = clickable;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setPadding(int left, int top, int right, int bottom) {
    }

    public void setOnClickListener(OnClickListener listener) {
    }

    public void getLocationInWindow(int[] outLocation) {
        outLocation[0] = 0;
        outLocation[1] = 0;
    }

    public AttachedSurfaceControl getRootSurfaceControl() {
        return null;
    }

    /** 简单点击监听接口（替身只存不用）。 */
    public interface OnClickListener {
        void onClick(View view);
    }
}
