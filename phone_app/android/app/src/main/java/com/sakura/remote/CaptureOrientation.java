package com.sakura.remote;

/**
 * EXIF 方向 → 位图规范变换（纯决策，可离线测试）。
 *
 * <p>为什么需要：{@code BitmapFactory} 解码 JPEG 时<b>不会</b>应用 EXIF
 * {@code Orientation}。相机把「竖拍」记录成「横放的像素 + Orientation=6」，
 * 缩图后若直接重编码，竖拍照片会变成横着甚至倒着。
 *
 * <p>这里的取值与 {@code android.media.ExifInterface} 的常量一致
 * （1..8），但类本身不依赖 Android，方便用纯 JVM 测试钉住映射关系。
 */
final class CaptureOrientation {

    static final int NORMAL = 1;
    static final int FLIP_HORIZONTAL = 2;
    static final int ROTATE_180 = 3;
    static final int FLIP_VERTICAL = 4;
    static final int TRANSPOSE = 5;
    static final int ROTATE_90 = 6;
    static final int TRANSVERSE = 7;
    static final int ROTATE_270 = 8;

    private CaptureOrientation() {
    }

    /** 需要顺时针旋转的角度（0/90/180/270）。未知值按 0 处理。 */
    static int rotationDegrees(int orientation) {
        switch (orientation) {
            case ROTATE_90:
                return 90;
            case ROTATE_180:
                return 180;
            case ROTATE_270:
                return 270;
            default:
                return 0;
        }
    }

    /** 是否需要水平镜像（左右翻转）。 */
    static boolean flipHorizontal(int orientation) {
        return orientation == FLIP_HORIZONTAL
                || orientation == TRANSPOSE
                || orientation == TRANSVERSE;
    }

    /** 是否需要垂直镜像（上下翻转）。 */
    static boolean flipVertical(int orientation) {
        return orientation == FLIP_VERTICAL;
    }

    /** 该方向是否需要对像素做任何变换。 */
    static boolean needsTransform(int orientation) {
        return rotationDegrees(orientation) != 0
                || flipHorizontal(orientation)
                || flipVertical(orientation);
    }

    /** 旋转 + 镜像是否交换宽高。 */
    static boolean swapsDimensions(int orientation) {
        int degrees = rotationDegrees(orientation);
        return degrees == 90 || degrees == 270;
    }
}
