package android.view;

import android.content.Context;

/** 最小 ViewGroup 替身：维护子视图列表。 */
public class ViewGroup extends View {

    public static class LayoutParams {
        public static final int MATCH_PARENT = -1;
        public static final int WRAP_CONTENT = -2;

        public int width;
        public int height;

        public LayoutParams() {
        }

        public LayoutParams(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    public static class MarginLayoutParams extends LayoutParams {
        public int leftMargin;
        public int topMargin;
        public int rightMargin;
        public int bottomMargin;

        public MarginLayoutParams(int width, int height) {
            super(width, height);
        }

        public void setMargins(int left, int top, int right, int bottom) {
            this.leftMargin = left;
            this.topMargin = top;
            this.rightMargin = right;
            this.bottomMargin = bottom;
        }
    }

    private final java.util.List<View> children = new java.util.ArrayList<>();

    public ViewGroup() {
    }

    public ViewGroup(Context context) {
        super(context);
    }

    public void addView(View child) {
        if (child == null || children.contains(child)) {
            return;
        }
        // 真实 ViewGroup.addView 会把 child 的 parent 指过来，并且拒绝「已有
        // 别的 parent」的 child（IllegalStateException）。替身同样维护父链，
        // 否则 isAttachedToWindow 无法继承、孤立 View 会被当成已 attach。
        if (child.parent != null && child.parent != this) {
            child.parent.removeView(child);
        }
        children.add(child);
        child.parent = this;
    }

    public void addView(View child, LayoutParams params) {
        addView(child);
    }

    public void removeAllViews() {
        for (View child : children) {
            child.parent = null;
        }
        children.clear();
    }

    public void removeView(View child) {
        if (child != null && children.remove(child)) {
            child.parent = null;
        }
    }

    public int indexOfChild(View child) {
        return children.indexOf(child);
    }

    public int getChildCount() {
        return children.size();
    }

    public View getChildAt(int index) {
        return children.get(index);
    }

    public java.util.List<View> childrenForTest() {
        return children;
    }
}
