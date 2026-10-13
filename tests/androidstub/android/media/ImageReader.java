package android.media;

import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.view.Surface;

/**
 * 最小 ImageReader 替身（INT-07）：保存真实控制器设置的帧监听器，
 * 并提供 {@code acquireLatestImage()} 的可控返回值（默认 null = 无帧）。
 */
public class ImageReader implements AutoCloseable {

    public interface OnImageAvailableListener {
        void onImageAvailable(ImageReader reader);
    }

    private OnImageAvailableListener listener;
    private Handler handler;
    private Image nextImage;
    private static ImageReader lastInstance;

    public static ImageReader newInstance(int width, int height, int format, int maxImages) {
        ImageReader instance = new ImageReader();
        lastInstance = instance;
        return instance;
    }

    /** 最近一次由真实产品创建的 reader（测试观察帧回调用）。 */
    public static ImageReader lastInstanceForTest() {
        return lastInstance;
    }

    public static void resetLastInstanceForTest() {
        lastInstance = null;
    }

    public void setOnImageAvailableListener(OnImageAvailableListener listener, Handler handler) {
        this.listener = listener;
        this.handler = handler;
    }

    public OnImageAvailableListener listenerForTest() {
        return listener;
    }

    public Handler handlerForTest() {
        return handler;
    }

    public Surface getSurface() {
        return new Surface();
    }

    public Image acquireLatestImage() {
        Image image = nextImage;
        nextImage = null;
        return image;
    }

    public void setNextImageForTest(Image image) {
        this.nextImage = image;
    }

    @Override
    public void close() {
    }
}
