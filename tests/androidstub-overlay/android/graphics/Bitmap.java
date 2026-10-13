package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

/**
 * Bitmap 替身（测试专用）。
 *
 * <p>这里不是空壳：它用 {@link BufferedImage} 真正承载像素，
 * 让生产代码 {@code FileChooserActivity.shrinkToBudget} 的
 * 「解码 → 按 EXIF 旋转 → 重编码 → 再核对预算」整条链路能在纯 JVM 上跑通，
 * 而不是只测一个被测试覆盖掉的假 shrink。
 *
 * <p>同时保留 ScreenCapturer 等类需要的表面方法，避免影响其它测试。
 */
public class Bitmap {

    /** 与 Android 同名的像素配置（替身只支持 ARGB_8888）。 */
    public enum Config {
        ARGB_8888
    }

    public enum CompressFormat {
        JPEG,
        PNG,
        WEBP
    }

    private BufferedImage image;
    private final int fallbackWidth;
    private final int fallbackHeight;

    public Bitmap() {
        this(100, 100);
    }

    public Bitmap(int width, int height) {
        this.fallbackWidth = width;
        this.fallbackHeight = height;
        this.image = null;
    }

    public Bitmap(BufferedImage image) {
        this.image = image;
        this.fallbackWidth = image == null ? 0 : image.getWidth();
        this.fallbackHeight = image == null ? 0 : image.getHeight();
    }

    public BufferedImage imageForTest() {
        return image;
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        if (source == null || source.image == null) {
            return source;
        }
        BufferedImage cropped = source.image.getSubimage(x, y, width, height);
        BufferedImage copy = new BufferedImage(cropped.getWidth(), cropped.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = copy.createGraphics();
        graphics.drawImage(cropped, 0, 0, null);
        graphics.dispose();
        return new Bitmap(copy);
    }

    /**
     * 生产缩图路径真正用的重载：按 {@link Matrix} 旋转/镜像。
     *
     * <p>用 {@link AffineTransform} 复现 Android 的「变换后重新贴合边界」语义，
     * 因此 EXIF 方向导致的宽高交换能在测试里断言到。
     */
    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height,
                                      Matrix matrix, boolean filter) {
        if (source == null || source.image == null) {
            return null;
        }
        BufferedImage cropped = source.image.getSubimage(x, y, width, height);
        if (matrix == null || matrix.isIdentity()) {
            return new Bitmap(cropped);
        }
        AffineTransform transform = new AffineTransform(matrix.transformForTest());
        Rectangle2D bounds = transform.createTransformedShape(
                new java.awt.Rectangle(0, 0, width, height)).getBounds2D();
        int outWidth = Math.max(1, (int) Math.round(bounds.getWidth()));
        int outHeight = Math.max(1, (int) Math.round(bounds.getHeight()));
        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.translate(-bounds.getX(), -bounds.getY());
        graphics.drawImage(cropped, transform, null);
        graphics.dispose();
        return new Bitmap(out);
    }

    public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
        if (source == null || source.image == null) {
            return source;
        }
        BufferedImage out = new BufferedImage(Math.max(1, width), Math.max(1, height),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = out.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source.image, 0, 0, out.getWidth(), out.getHeight(), null);
        graphics.dispose();
        return new Bitmap(out);
    }

    public void copyPixelsFromBuffer(ByteBuffer buffer) {
        // 截图替身路径不参与像素断言
    }

    public int getWidth() {
        return image == null ? fallbackWidth : image.getWidth();
    }

    public int getHeight() {
        return image == null ? fallbackHeight : image.getHeight();
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        if (image == null) {
            try {
                stream.write(1);
                return true;
            } catch (java.io.IOException error) {
                return false;
            }
        }
        try {
            String formatName = format == CompressFormat.PNG ? "png" : "jpg";
            return ImageIO.write(image, formatName, stream);
        } catch (Exception error) {
            return false;
        }
    }

    public void recycle() {
        image = null;
    }
}
