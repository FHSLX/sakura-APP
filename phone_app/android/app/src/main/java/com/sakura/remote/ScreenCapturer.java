package com.sakura.remote;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 手机屏幕截图 + 压缩。
 *
 * 为什么不用宿主（电脑端）的截图：
 * 插件的 `sakura.host.screen.capture()` 只返回一个 resourceId，
 * 真正的 JPEG 取回函数是 Core 专用的，插件拿不到图。
 * 而且「学姐看到我正在看什么」在手机场景下本来就是指手机屏幕。
 *
 * 带宽控制：截图先按最长边缩到 {@link #MAX_DIMENSION}，再压成 JPEG。
 * 实测 1080×2460 的原始截图 2–4 MB，压完约 150–300 KB，约省 10 倍。
 */
public final class ScreenCapturer {

    /** 压缩后最长边的上限（像素）。 */
    private static final int MAX_DIMENSION = 1280;
    /** JPEG 质量。70 在文字可读性和体积之间比较平衡。 */
    private static final int JPEG_QUALITY = 70;

    public interface Callback {
        /** dataUrl 形如 data:image/jpeg;base64,....；失败时为空串。 */
        void onResult(String dataUrl, String error);
    }

    private ScreenCapturer() {
    }

    /** 系统截图权限是否还在（每次进程重启后都要重新申请）。 */
    public static boolean hasPermission(Context context) {
        return ScreenPermissionHolder.projection != null;
    }

    /**
     * 保留系统返回的授权结果，供后续复用。
     * MediaProjection 的授权不能序列化保存，进程结束后必须重新申请。
     */
    public static void rememberPermission(Intent data) {
        ScreenPermissionHolder.pendingData = data;
    }

    /** 取回已保存的授权 Intent，交给 CaptureService 使用。 */
    public static Intent storedGrant() {
        return ScreenPermissionHolder.pendingData;
    }

    /**
     * 再截一次（已有授权时用）。
     *
     * 注意不要把 pendingData 缓存起来反复用：Android 14 起同一个授权 Intent
     * 只能换取一次 MediaProjection，第二次会失败。所以这里也是交给
     * CaptureService，由它每次重新走完整流程；授权失效时回调里会带错误，
     * 调用方据此重新申请。
     */
    public static void capture(Context context, Callback callback) {
        Intent data = ScreenPermissionHolder.pendingData;
        if (data == null) {
            callback.onResult("", "还没有截图权限");
            return;
        }
        CaptureService.setReceiver(callback::onResult);
        CaptureService.start(context, Activity.RESULT_OK, data);
    }


    /** ImageReader 的一行像素含 padding，必须按 rowStride 逐行拷贝。 */
    static Bitmap toBitmap(Image image, int width, int height) {
        Image.Plane[] planes = image.getPlanes();
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

    /**
     * 保存系统返回的授权 Intent。
     *
     * 做成静态的：MediaProjection 的授权结果没法序列化进 Bundle，
     * 进程内暂存是官方文档给的常规做法（每次进程重启都要重新申请）。
     */
    private static final class ScreenPermissionHolder {
        static volatile Intent pendingData;
        static volatile MediaProjection projection;

        private ScreenPermissionHolder() {
        }
    }

    /**
     * 授权成功后由 Activity 调用：把授权交给 CaptureService 去真正截屏。
     *
     * 为什么不在 Activity 里直接截：Android 14（API 34）起 MediaProjection
     * 只能在 foregroundServiceType="mediaProjection" 的前台服务里使用，
     * 在 Activity 回调里 createVirtualDisplay 会直接失败 —— 这正是之前
     * 「点了截屏没反应」的根因。
     */
    public static void captureWithGrant(Context context, Intent data, Callback callback) {
        rememberPermission(data);
        CaptureService.setReceiver(callback::onResult);
        CaptureService.start(context, Activity.RESULT_OK, data);
    }
}
