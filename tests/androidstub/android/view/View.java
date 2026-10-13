package android.view;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 View 替身（INT-07）：可控 attach 状态、尺寸、surface-control 与
 * attach/detach 监听器。真实 OverlayTouchRegionController 直接操作它。
 */
public class View {

    public interface OnAttachStateChangeListener {
        void onViewAttachedToWindow(View view);

        void onViewDetachedFromWindow(View view);
    }

    public boolean attached = true;
    public int width = 1080;
    public int height = 2400;
    public boolean hasSurfaceControl = true;
    public int locationX;
    public int locationY;

    public final AttachedSurfaceControl surfaceControl = new AttachedSurfaceControl();
    private final List<OnAttachStateChangeListener> listeners = new ArrayList<>();

    public void addOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
        listeners.add(listener);
    }

    public void removeOnAttachStateChangeListener(OnAttachStateChangeListener listener) {
        listeners.remove(listener);
    }

    public boolean isAttachedToWindow() {
        return attached;
    }

    public AttachedSurfaceControl getRootSurfaceControl() {
        return hasSurfaceControl ? surfaceControl : null;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public void getLocationInWindow(int[] outLocation) {
        outLocation[0] = locationX;
        outLocation[1] = locationY;
    }

    public List<OnAttachStateChangeListener> listenersForTest() {
        return listeners;
    }

    /** 模拟窗口重新挂载：驱动真实控制器的 attach 分支。 */
    public void dispatchAttachedForTest() {
        attached = true;
        for (OnAttachStateChangeListener listener : new ArrayList<>(listeners)) {
            listener.onViewAttachedToWindow(this);
        }
    }

    /** 模拟窗口移除。 */
    public void dispatchDetachedForTest() {
        attached = false;
        for (OnAttachStateChangeListener listener : new ArrayList<>(listeners)) {
            listener.onViewDetachedFromWindow(this);
        }
    }
}
