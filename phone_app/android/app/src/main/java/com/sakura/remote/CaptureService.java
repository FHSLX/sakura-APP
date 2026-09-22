package com.sakura.remote;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
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
import android.os.IBinder;
import android.os.Looper;
import android.util.Base64;
import android.util.DisplayMetrics;

import java.io.ByteArrayOutputStream;

/**
 * 截屏用的前台服务。
 *
 * 为什么必须是独立的前台服务：Android 14（API 34）起，MediaProjection 只能
 * 在 foregroundServiceType="mediaProjection" 的前台服务里使用，否则
 * createVirtualDisplay 直接失败。之前只在 Activity 里做，所以截屏一直没生效。
 *
 * 服务还负责「先隐藏悬浮窗再截图」：否则截出来的图里会带着学姐自己和对话框。
 */
public class CaptureService extends Service {

    public static final String EXTRA_RESULT_CODE = "resultCode";
    public static final String EXTRA_RESULT_DATA = "resultData";

    private static final String CHANNEL_ID = "sakura_capture";
    private static final int NOTIFICATION_ID = 0x5342;

    /**
     * 截图结果回传目标。由调用方（MainActivity 或 OverlayService）设置。
     * 用静态引用是因为结果必须送回**发起截屏的那个 WebView**，
     * 而 Service 里拿不到是哪一个。
     */
    public interface Receiver {
        void onCaptureResult(String dataUrl, String error);
    }

    private static volatile Receiver receiver;

    public static void setReceiver(Receiver value) {
        receiver = value;
    }

    /**
     * 启动一次截屏。
     *
     * @param grantData 用户刚授权的 MediaProjection Intent（可能为 null，
     *                  为 null 时说明还没授权，调用方应先去申请）
     */
    public static void start(Context context, int resultCode, Intent grantData) {
        Intent intent = new Intent(context, CaptureService.class);
        intent.putExtra(EXTRA_RESULT_CODE, resultCode);
        intent.putExtra(EXTRA_RESULT_DATA, grantData);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        if (intent == null) {
            finish("", "截屏服务启动参数缺失");
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultData == null) {
            finish("", "没有拿到截屏授权");
            return START_NOT_STICKY;
        }

        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            finish("", "系统不支持屏幕截图");
            return START_NOT_STICKY;
        }

        MediaProjection projection;
        try {
            projection = manager.getMediaProjection(resultCode, resultData);
        } catch (Exception error) {
            finish("", "获取截屏通道失败：" + error.getClass().getSimpleName());
            return START_NOT_STICKY;
        }
        if (projection == null) {
            finish("", "获取截屏通道失败");
            return START_NOT_STICKY;
        }

        // 先隐藏悬浮窗，等窗口真正消失再截图，否则会把学姐和对话框一起拍进去。
        OverlayService.setHiddenForCapture(true);

        new Handler(Looper.getMainLooper()).postDelayed(
                () -> capture(projection), HIDE_DELAY_MS);
        return START_NOT_STICKY;
    }

    /** 隐藏悬浮窗后等待的时间：太短窗口还在合成，截出来仍有残影。 */
    private static final long HIDE_DELAY_MS = 450L;

    private void capture(MediaProjection projection) {
        DisplayMetrics metrics = new DisplayMetrics();
        android.view.WindowManager wm =
                (android.view.WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (wm == null) {
            projection.stop();
            finish("", "取不到屏幕尺寸");
            return;
        }
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        int density = metrics.densityDpi;

        ImageReader reader = null;
        VirtualDisplay display = null;
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2);
            display = projection.createVirtualDisplay(
                    "sakura-capture",
                    width, height, density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    reader.getSurface(), null, null);
        } catch (Exception error) {
            if (reader != null) {
                reader.close();
            }
            projection.stop();
            finish("", "创建截图画面失败：" + error.getClass().getSimpleName());
            return;
        }

        final ImageReader activeReader = reader;
        final VirtualDisplay activeDisplay = display;
        final MediaProjection activeProjection = projection;
        // 再等一会儿让系统把第一帧填进 surface，否则 acquireLatestImage 拿到空。
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            String dataUrl = "";
            String errorText = "";
            Image image = null;
            try {
                image = activeReader.acquireLatestImage();
                if (image == null) {
                    errorText = "截图画面为空，请重试";
                } else {
                    Bitmap bitmap = ScreenCapturer.toBitmap(image, width, height);
                    if (bitmap == null) {
                        errorText = "截图解码失败";
                    } else {
                        dataUrl = ScreenCapturer.compressToDataUrl(bitmap);
                        bitmap.recycle();
                        if (dataUrl.isEmpty()) {
                            errorText = "截图压缩失败";
                        }
                    }
                }
            } catch (Exception error) {
                errorText = error.getClass().getSimpleName() + ": " + error.getMessage();
            } finally {
                if (image != null) {
                    image.close();
                }
                activeReader.close();
                try {
                    if (activeDisplay != null) {
                        activeDisplay.release();
                    }
                } catch (Exception ignored) {
                    // 已释放
                }
                activeProjection.stop();
                finish(dataUrl, errorText);
            }
        }, FRAME_DELAY_MS);
    }

    /** 等第一帧填充的时间。 */
    private static final long FRAME_DELAY_MS = 700L;

    private void finish(String dataUrl, String error) {
        // 无论成功失败都要把悬浮窗放回来，否则用户会觉得桌宠「消失了」。
        OverlayService.setHiddenForCapture(false);
        Receiver target = receiver;
        if (target != null) {
            target.onCaptureResult(dataUrl, error);
        }
        stopForegroundCompat();
        stopSelf();
    }

    private void startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        getString(R.string.capture_channel_name),
                        NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(getString(R.string.capture_notification_title))
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setOngoing(true);
        Notification notification = builder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
    }
}
