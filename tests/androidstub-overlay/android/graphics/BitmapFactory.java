package android.graphics;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

import javax.imageio.ImageIO;

/**
 * BitmapFactory 替身（测试专用）。
 *
 * <p>只实现生产缩图路径用到的一个重载 {@link #decodeFile(String, Options)}，
 * 但它用 {@link ImageIO} 真实解码文件：
 *   - {@code inJustDecodeBounds} 只回填尺寸并返回 null（与 Android 一致）；
 *   - 否则按 {@code inSampleSize} 做有界降采样。
 *
 * <p>因此「超预算 → 逐级缩小 → EXIF 旋转 → 再编码」这条真实生产控制流
 * 可以在纯 JVM 上被完整执行和断言。
 */
public final class BitmapFactory {

    private BitmapFactory() {
    }

    public static class Options {
        public boolean inJustDecodeBounds;
        public int inSampleSize = 1;
        public int outWidth = -1;
        public int outHeight = -1;
    }

    public static Bitmap decodeFile(String path, Options options) {
        try {
            if (path == null) {
                return null;
            }
            BufferedImage source = ImageIO.read(new File(path));
            if (source == null) {
                return null;
            }
            if (options != null && options.inJustDecodeBounds) {
                options.outWidth = source.getWidth();
                options.outHeight = source.getHeight();
                return null;
            }
            int sample = options == null ? 1 : Math.max(1, options.inSampleSize);
            if (sample > 1) {
                int width = Math.max(1, source.getWidth() / sample);
                int height = Math.max(1, source.getHeight() / sample);
                BufferedImage small = new BufferedImage(width, height,
                        BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = small.createGraphics();
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(source, 0, 0, width, height, null);
                graphics.dispose();
                source = small;
            }
            return new Bitmap(source);
        } catch (Throwable error) {
            return null;
        }
    }
}
