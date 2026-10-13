package com.sakura.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 手机屏幕截图的像素处理。
 *
 * 为什么不用宿主（电脑端）的截图：
 * 插件的 `sakura.host.screen.capture()` 只返回一个 resourceId，
 * 真正的 JPEG 取回函数是 Core 专用的，插件拿不到图。
 * 而且「学姐看到我正在看什么」在手机场景下本来就是指手机屏幕。
 *
 * R2 修正：这里不再保存/复用 MediaProjection 授权。
 *   - 旧实现的 {@code hasPermission()} 检查一个**从未赋值**的 projection 字段，
 *     所以永远返回 false；而 {@code rememberPermission()/storedGrant()} 缓存
 *     Intent 想「复用授权」，但 Android 14 起同一个授权 Intent 只能换一次
 *     MediaProjection，第二次必然失败。
 *   - 现在每次截图都必须重新走系统授权；状态查询只反映「当前是否有在途请求」，
 *     不再假装拥有可复用的权限。
 *
 * 带宽控制：截图先按最长边缩到 {@link #MAX_DIMENSION}，再压成 JPEG。
 * 实测 1080×2460 的原始截图 2–4 MB，压完约 150–300 KB，约省 10 倍。
 */
public final class ScreenCapturer {

    /** 压缩后最长边的上限（像素）。 */
    private static final int MAX_DIMENSION = 1280;
    /** JPEG 质量。70 在文字可读性和体积之间比较平衡。 */
    private static final int JPEG_QUALITY = 70;

    private ScreenCapturer() {
    }

    /**
     * 当前是否可以「直接」截图。
     *
     * MediaProjection 的授权不能序列化保存，进程结束后必须重新申请，
     * 且一次授权只够一次截图。因此这里只表示「已有在途截图请求」，
     * 界面上应说明「每次截图都需要系统确认」。
     */
    public static boolean hasPermission(android.content.Context context) {
        return CaptureCoordinator.get().isBusy();
    }

    /** ImageReader 的一行像素含 padding，必须按 rowStride 逐行拷贝。 */
    static Bitmap toBitmap(android.media.Image image, int width, int height) {
        android.media.Image.Plane[] planes = image.getPlanes();
        if (planes.length == 0) {
            return null;
        }
        ByteBuffer buffer = planes[0].getBuffer();
        int pixelStride = planes[0].getPixelStride();
        int rowStride = planes[0].getRowStride();
        int rowPadding = rowStride - pixelStride * width;
        Bitmap bitmap = Bitmap.createBitmap(
                width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(buffer);
        if (rowPadding == 0) {
            return bitmap;
        }
        // 裁掉右侧 padding
        Bitmap cropped = Bitmap.createBitmap(bitmap, 0, 0, width, height);
        if (cropped != bitmap) {
            bitmap.recycle();
        }
        return cropped;
    }

    /** 缩放 + JPEG 压缩 + base64，得到可直接上传的 data URL。 */
    static String compressToDataUrl(Bitmap source) {
        int width = source.getWidth();
        int height = source.getHeight();
        int longest = Math.max(width, height);
        Bitmap scaled = source;
        if (longest > MAX_DIMENSION) {
            float ratio = (float) MAX_DIMENSION / (float) longest;
            int targetWidth = Math.max(1, Math.round(width * ratio));
            int targetHeight = Math.max(1, Math.round(height * ratio));
            scaled = Bitmap.createScaledBitmap(source, targetWidth, targetHeight, true);
        }
        try {
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
            byte[] payload = stream.toByteArray();
            if (payload.length == 0) {
                return "";
            }
            return "data:image/jpeg;base64,"
                    + Base64.encodeToString(payload, Base64.NO_WRAP);
        } catch (Exception error) {
            return "";
        } finally {
            if (scaled != source) {
                scaled.recycle();
            }
        }
    }

    /** 供离线单元测试验证压缩结果（不涉及 MediaProjection）。 */
    static Bitmap decodeForTest(byte[] payload) {
        return BitmapFactory.decodeByteArray(payload, 0, payload.length);
    }
}
