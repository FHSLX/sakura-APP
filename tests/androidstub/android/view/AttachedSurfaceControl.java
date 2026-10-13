package android.view;

import android.graphics.Region;

/**
 * 最小 AttachedSurfaceControl 替身：记录 {@code setTouchableRegion} 的调用
 * 与最后一次写入的 Region，供真实 OverlayTouchRegionController 的行为断言。
 */
public class AttachedSurfaceControl {

    public int setTouchableRegionCalls;
    public Region lastRegion;
    public boolean throwOnSet;

    public void setTouchableRegion(Region region) {
        setTouchableRegionCalls++;
        if (throwOnSet) {
            throw new IllegalStateException("surface rejected");
        }
        lastRegion = region;
    }
}
