package android.graphics;

import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * 最小 Bitmap 替身：尺寸可控；{@code compress} 会写入一个字节并成功，
 * 使真实 ScreenCapturer 的「解码 → 压缩 → data URL」成功分支可被执行。
 */
public class Bitmap {

    public enum Config {
        ARGB_8888
    }

    public enum CompressFormat {
        JPEG
    }

    private final int width;
    private final int height;

    public Bitmap() {
        this(100, 100);
    }

    public Bitmap(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(width, height);
    }

    public static Bitmap createBitmap(Bitmap source, int x, int y, int width, int height) {
        return source;
    }

    public static Bitmap createScaledBitmap(Bitmap source, int width, int height, boolean filter) {
        return source;
    }

    public void copyPixelsFromBuffer(ByteBuffer buffer) {
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        try {
            stream.write(1);
            return true;
        } catch (java.io.IOException error) {
            return false;
        }
    }

    public void recycle() {
    }
}
