package android.media;

import java.nio.ByteBuffer;

/**
 * 最小 Image 替身：提供一个可读的 plane，使真实 ScreenCapturer.toBitmap 的
 * 成功分支可执行（rowStride == pixelStride * width，无 padding）。
 */
public class Image implements AutoCloseable {

    public static class Plane {

        private final ByteBuffer buffer;

        Plane(int bytes) {
            this.buffer = ByteBuffer.allocate(bytes);
        }

        public ByteBuffer getBuffer() {
            return buffer;
        }

        public int getPixelStride() {
            return 4;
        }

        public int getRowStride() {
            return 400;   // 100 像素 * 4 字节，与默认 Bitmap 尺寸一致
        }
    }

    public Plane[] getPlanes() {
        return new Plane[]{new Plane(400 * 100)};
    }

    @Override
    public void close() {
    }
}
