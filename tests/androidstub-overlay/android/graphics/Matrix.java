package android.graphics;

import java.awt.geom.AffineTransform;

/**
 * Matrix 替身（测试专用）：只覆盖缩图方向修正用到的旋转/镜像。
 *
 * <p>用 {@link AffineTransform} 承载，{@link Bitmap#createBitmap} 会真正应用它，
 * 所以 EXIF Orientation 是否被应用可以被端到端断言。
 */
public class Matrix {

    private final AffineTransform transform = new AffineTransform();

    public void postRotate(float degrees) {
        transform.concatenate(AffineTransform.getRotateInstance(Math.toRadians(degrees)));
    }

    public void postScale(float scaleX, float scaleY) {
        transform.concatenate(AffineTransform.getScaleInstance(scaleX, scaleY));
    }

    public boolean isIdentity() {
        return transform.isIdentity();
    }

    public AffineTransform transformForTest() {
        return new AffineTransform(transform);
    }
}
