package com.sakura.remote;

/**
 * OverlayService 的**协作者替身**（INT-07 测试专用，不进入产品构建）。
 *
 * 真实 OverlayService 依赖 WebView/PetWindow/WindowManager，无法在 JVM 里实例化；
 * 但 {@link CaptureService}、{@link CapturePermissionActivity}、
 * {@link CaptureCoordinator} 只通过几个 static 方法与它交互：
 *   - 悬浮窗当前是否 attach（首帧让位门槛）；
 *   - 授权隐藏 / 截图隐藏的 token 归属。
 *
 * 这个替身只保存这两样**状态**，不含任何被测控制流（onStartCommand/abort/
 * 区域提交都不在这里）。token 规则与真实 OverlayVisibilityController 一致：
 * 持有者匹配才允许释放。
 */
public final class OverlayService {

    private static boolean overlayAttached = true;
    private static String permissionHiddenOwner;
    private static String captureHiddenOwner;

    private OverlayService() {
    }

    // ---- 悬浮窗状态 --------------------------------------------------

    public static boolean isOverlayWindowAttached() {
        return overlayAttached;
    }

    public static boolean isRunning() {
        return false;
    }

    public static void setOverlayAttachedForTest(boolean attached) {
        overlayAttached = attached;
    }

    // ---- 授权隐藏（token 归属）--------------------------------------

    public static void acquirePermissionHidden(String requestId) {
        permissionHiddenOwner = requestId;
    }

    public static void releasePermissionHidden(String requestId) {
        if (requestId != null && requestId.equals(permissionHiddenOwner)) {
            permissionHiddenOwner = null;
        }
    }

    // ---- 截图隐藏（token 归属）--------------------------------------

    public static void setHiddenForCapture(String requestId, boolean hidden) {
        if (hidden) {
            captureHiddenOwner = requestId;
        } else if (requestId != null && requestId.equals(captureHiddenOwner)) {
            captureHiddenOwner = null;
        }
    }

    public static void releaseCaptureHidden(String requestId) {
        if (requestId != null && requestId.equals(captureHiddenOwner)) {
            captureHiddenOwner = null;
        }
    }

    // ---- 测试观察/复位 ----------------------------------------------

    public static String permissionHiddenOwnerForTest() {
        return permissionHiddenOwner;
    }

    public static String captureHiddenOwnerForTest() {
        return captureHiddenOwner;
    }

    public static void resetForTest() {
        overlayAttached = true;
        permissionHiddenOwner = null;
        captureHiddenOwner = null;
    }
}
