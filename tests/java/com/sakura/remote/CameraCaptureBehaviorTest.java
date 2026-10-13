package com.sakura.remote;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * 「直接拍照」入口的真实生产行为测试。
 *
 * <p>只驱动真实生产类 {@link OverlayFileChooser} / {@link FileChooserActivity}
 * （以及 {@link FileChooserBridge}），Android 系统接口用 tests/androidstub-overlay 替身。
 * 其中 Bitmap/BitmapFactory/Matrix/ExifInterface 替身是**功能性**的
 * （基于 ImageIO / BufferedImage），因此超预算缩图 + EXIF 方向用例跑的是
 * 真实生产控制流，而不是覆盖掉 shrinkToBudget 的假实现。
 *
 * <p>覆盖：
 *   1) capture 标志从 WebChromeClient 参数转发到辅助 Activity；
 *   2) 拍照入口真的走 ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT（content URI）；
 *   3) 结果 RESULT_OK 但 data==null 时用预先持有的输出 URI；
 *   4) 取消 / 无相机 / 写失败 / FileProvider 失败都有明确反馈且恰好结束一次；
 *   5) 超过 6MiB 预算：真实缩图 + EXIF 方向修正后再核对预算；
 *   6) 每次 launch 独立 requestCode：旧相机/旧图库的迟到结果绝不结束新请求。
 */
public final class CameraCaptureBehaviorTest {

    private static final long MAX_FILE_BYTES = 6L * 1024 * 1024;

    private static int failures;

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name
                + (ok ? "" : " -> " + String.valueOf(detail)));
        if (!ok) {
            failures++;
        }
    }

    private static final class RecordingCallback implements ValueCallback<Uri[]> {
        final AtomicInteger calls = new AtomicInteger();
        Uri[] last;
        final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onReceiveValue(Uri[] value) {
            calls.incrementAndGet();
            last = value;
            latch.countDown();
        }
    }

    /** 对所有 content URI 都返回 image/jpeg + 小负载的 resolver。 */
    private static final class ImageResolver extends ContentResolver {
        byte[] payload = new byte[16];

        @Override
        public String getType(Uri uri) {
            return "image/jpeg";
        }

        @Override
        public InputStream openInputStream(Uri uri) {
            return new ByteArrayInputStream(payload);
        }
    }

    /** 可覆盖创建 / URI 的拍照 Activity（不再覆盖 shrinkToBudget）。 */
    private static class CameraActivity extends FileChooserActivity {
        boolean failCreate;
        boolean failUri;

        @Override
        File createCaptureFile(String prefix) {
            if (failCreate) {
                return null;
            }
            return super.createCaptureFile(prefix);
        }

        @Override
        Uri uriFor(File file) {
            if (failUri) {
                return null;
            }
            return super.uriFor(file);
        }
    }

    private static final class CaptureParams extends WebChromeClient.FileChooserParams {
        private final boolean capture;

        CaptureParams(boolean capture) {
            this.capture = capture;
        }

        @Override
        public String[] getAcceptTypes() {
            return new String[]{"image/*"};
        }

        @Override
        public boolean isCaptureEnabled() {
            return capture;
        }
    }

    public static void main(String[] args) throws Exception {
        captureFlagIsForwarded();
        captureLaunchesRealImageCaptureIntent();
        noCameraAppReportsAndCancels();
        cancelDeletesTempFileAndEndsOnce();
        okWithNullDataUsesPreHeldOutputUri();
        oversizeJpegIsReallyShrunkWithExifRotation();
        normalOrientationKeepsLandscape();
        hugeUndecodableFileIsRejected();
        createFailureReports();
        providerFailureReports();
        staleCameraCancelDoesNotEndReplacement();
        staleCameraSuccessDoesNotEndReplacement();
        stalePickerResultDoesNotCrossIntoCamera();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production sources: OverlayFileChooser/FileChooserActivity)");
    }

    private static void captureFlagIsForwarded() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        Context context = new Context();
        OverlayFileChooser.request(context, "w1", callback, new CaptureParams(true));
        Intent photo = context.lastStartedIntent;
        check("拍照请求把 capture=true 转发给辅助页",
                photo != null && photo.getBooleanExtra(FileChooserActivity.EXTRA_CAPTURE, false),
                photo == null ? null : "no intent");
        FileChooserBridge.cancelAll("w1");

        RecordingCallback gallery = new RecordingCallback();
        Context context2 = new Context();
        OverlayFileChooser.request(context2, "w2", gallery, new CaptureParams(false));
        Intent pick = context2.lastStartedIntent;
        check("图库请求 capture=false（不误走相机）",
                pick != null && !pick.getBooleanExtra(FileChooserActivity.EXTRA_CAPTURE, false),
                pick == null ? null : "no intent");
        FileChooserBridge.cancelAll("w2");
    }

    private static void captureLaunchesRealImageCaptureIntent() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        Intent started = activity.lastActivityResultIntent;
        check("拍照走 ACTION_IMAGE_CAPTURE",
                started != null && MediaStore.ACTION_IMAGE_CAPTURE.equals(started.getAction()),
                started == null ? null : started.getAction());
        check("请求码就是本次 launch 的独立请求码",
                activity.lastActivityRequestCode == activity.lastRequestCodeForTest(),
                activity.lastActivityRequestCode + "/" + activity.lastRequestCodeForTest());
        Uri output = started == null ? null : started.getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        check("EXTRA_OUTPUT 是预先持有的 content URI（不是 thumbnail）",
                output != null && "content".equals(output.getScheme())
                        && output.toString().contains(".fileprovider"),
                output == null ? null : output.toString());
        check("输出 URI 与预先持有的输出一致",
                output != null && output.equals(activity.captureUriForTest()),
                activity.captureUriForTest());
        File temp = activity.captureFileForTest();
        check("临时照片位于应用 cache 目录",
                temp != null && temp.getAbsolutePath().contains("sakura-captures"),
                temp == null ? null : temp.getAbsolutePath());
        check("拿到 URI 后授权了读写",
                started != null
                        && (started.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
                        && (started.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0,
                started == null ? null : started.getFlags());
        FileChooserBridge.cancelAll("w1");
    }

    private static void noCameraAppReportsAndCancels() {
        Intent.resolveActivityForTest = null;
        try {
            FileChooserBridge.resetForTest();
            RecordingCallback callback = new RecordingCallback();
            CameraActivity activity = new CameraActivity();
            activity.setCacheDirForTest(tempCache("nocamera"));
            activity.setContentResolverForTest(new ImageResolver());
            activity.setIntent(captureIntent(FileChooserBridge.begin("w1", callback)));
            activity.onCreate((Bundle) null);
            check("无相机应用时回调恰好取消一次",
                    callback.calls.get() == 1 && callback.last == null, callback.calls.get());
            check("无相机应用时有明确反馈",
                    activity.lastProblemForTest().contains("相机"), activity.lastProblemForTest());
        } finally {
            Intent.resolveActivityForTest = new Object();
        }
    }

    private static void cancelDeletesTempFileAndEndsOnce() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        File temp = activity.captureFileForTest();
        writeBytes(temp, 16);
        int requestCode = activity.lastActivityRequestCode;
        activity.onActivityResult(requestCode, Activity.RESULT_CANCELED, null);
        check("拍照取消回调恰好取消一次",
                callback.calls.get() == 1 && callback.last == null, callback.calls.get());
        check("拍照取消清掉本次临时文件", temp == null || !temp.exists(),
                temp == null ? null : temp.exists());
    }

    private static void okWithNullDataUsesPreHeldOutputUri() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        File temp = activity.captureFileForTest();
        writeBytes(temp, 64);
        Uri expected = activity.captureUriForTest();
        // 系统相机常态：RESULT_OK 但 data == null
        activity.onActivityResult(activity.lastActivityRequestCode, Activity.RESULT_OK, null);
        check("相机成功回调在时限内返回", callback.latch.await(5, TimeUnit.SECONDS), true);
        check("data=null 时交付预先持有的 content URI",
                callback.last != null && callback.last.length == 1
                        && expected.equals(callback.last[0]),
                callback.last == null ? null : callback.last[0]);
    }

    /**
     * 真实超预算缩图：源文件 > 6MiB（真实 JPEG + EXIF=6 竖拍），
     * 走真实 {@code validateCapture → shrinkToBudget → BitmapFactory →
     * applyOrientation → compress}，断言预算与方向都被修正。
     */
    private static void oversizeJpegIsReallyShrunkWithExifRotation() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        File source = activity.captureFileForTest();
        writeOversizeJpeg(source, 600, 400, ExifOrientation.ROTATE_90, 7L * 1024 * 1024);
        check("源照片确实超过 6MiB 预算", source.length() > MAX_FILE_BYTES, source.length());
        check("生产 readOrientation 读出 6", activity.readOrientation(source) == 6,
                activity.readOrientation(source));

        activity.onActivityResult(activity.lastActivityRequestCode, Activity.RESULT_OK, null);
        check("超预算缩图在时限内交付", callback.latch.await(30, TimeUnit.SECONDS), true);
        check("交付的是缩小后的文件",
                callback.last != null && callback.last.length == 1
                        && callback.last[0].toString().contains("shrunk-"),
                callback.last == null ? null : callback.last[0]);

        File shrunk = findShrunkFile(source.getParentFile());
        check("缩图产物存在", shrunk != null && shrunk.isFile(), shrunk);
        check("缩图后仍在 6MiB 预算内",
                shrunk != null && shrunk.length() > 0 && shrunk.length() <= MAX_FILE_BYTES,
                shrunk == null ? null : shrunk.length());
        BufferedImage image = shrunk == null ? null : ImageIO.read(shrunk);
        check("EXIF 旋转 90 后竖拍方向正确（高 > 宽）",
                image != null && image.getHeight() > image.getWidth(),
                image == null ? null : image.getWidth() + "x" + image.getHeight());
        check("旋转后红色角块从左上移到右上（像素级验证）",
                image != null && isRedTopRight(image), image == null ? null : "no image");
    }

    /** 同一超预算流程，EXIF=1：方向不变（宽 > 高）。 */
    private static void normalOrientationKeepsLandscape() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        File source = activity.captureFileForTest();
        writeOversizeJpeg(source, 600, 400, ExifOrientation.NORMAL, 7L * 1024 * 1024);
        activity.onActivityResult(activity.lastActivityRequestCode, Activity.RESULT_OK, null);
        check("正常方向缩图在时限内交付", callback.latch.await(30, TimeUnit.SECONDS), true);
        File shrunk = findShrunkFile(source.getParentFile());
        BufferedImage image = shrunk == null ? null : ImageIO.read(shrunk);
        check("EXIF=1 不旋转（宽 > 高，未被误转）",
                image != null && image.getWidth() > image.getHeight(),
                image == null ? null : image.getWidth() + "x" + image.getHeight());
    }

    /** 超预算但不是有效图片：真实 BitmapFactory 解码失败 → 明确取消。 */
    private static void hugeUndecodableFileIsRejected() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = launchCapture("w1", callback);
        File temp = activity.captureFileForTest();
        writeBytes(temp, 7 * 1024 * 1024);
        activity.onActivityResult(activity.lastActivityRequestCode, Activity.RESULT_OK, null);
        check("无法解码的超大文件回调在时限内结束",
                callback.latch.await(5, TimeUnit.SECONDS), true);
        check("无法解码的超大文件回调取消",
                callback.calls.get() == 1 && callback.last == null, callback.calls.get());
        check("无法解码时给出预算反馈",
                activity.lastProblemForTest().contains("太大"), activity.lastProblemForTest());
    }

    private static void createFailureReports() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = new CameraActivity();
        activity.failCreate = true;
        activity.setCacheDirForTest(tempCache("createfail"));
        activity.setContentResolverForTest(new ImageResolver());
        activity.setIntent(captureIntent(FileChooserBridge.begin("w1", callback)));
        activity.onCreate((Bundle) null);
        check("创建临时文件失败时取消一次",
                callback.calls.get() == 1 && callback.last == null, callback.calls.get());
        check("创建临时文件失败有明确反馈",
                activity.lastProblemForTest().contains("临时文件"), activity.lastProblemForTest());
    }

    private static void providerFailureReports() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        CameraActivity activity = new CameraActivity();
        activity.failUri = true;
        activity.setCacheDirForTest(tempCache("urifail"));
        activity.setContentResolverForTest(new ImageResolver());
        activity.setIntent(captureIntent(FileChooserBridge.begin("w1", callback)));
        activity.onCreate((Bundle) null);
        check("FileProvider 失败时取消一次",
                callback.calls.get() == 1 && callback.last == null, callback.calls.get());
        check("FileProvider 失败有明确反馈",
                activity.lastProblemForTest().contains("输出位置"), activity.lastProblemForTest());
    }

    private static void staleCameraCancelDoesNotEndReplacement() throws Exception {
        staleCameraResultDoesNotEndReplacement(Activity.RESULT_CANCELED);
    }

    private static void staleCameraSuccessDoesNotEndReplacement() throws Exception {
        staleCameraResultDoesNotEndReplacement(Activity.RESULT_OK);
    }

    /**
     * 旧相机结果（取消或成功）回来时，新相机请求必须仍在途；
     * 旧 launch 只按自己的 requestCode 结束，绝不读新文件、绝不结束新请求。
     */
    private static void staleCameraResultDoesNotEndReplacement(int staleResultCode)
            throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback first = new RecordingCallback();
        CameraActivity activity = launchCapture("same-window", first);
        int oldRequestCode = activity.lastActivityRequestCode;
        writeBytes(activity.captureFileForTest(), 32);

        RecordingCallback second = new RecordingCallback();
        String newToken = FileChooserBridge.begin("same-window", second);
        check("替换时旧回调按取消结束[stale=" + staleResultCode + "]",
                first.calls.get() == 1 && first.last == null, first.calls.get());
        activity.onNewIntent(captureIntent(newToken));
        int newRequestCode = activity.lastActivityRequestCode;
        File newFile = activity.captureFileForTest();
        check("新旧请求码不同", oldRequestCode != newRequestCode,
                oldRequestCode + "/" + newRequestCode);

        // 旧相机的迟到结果：必须被忽略
        activity.onActivityResult(oldRequestCode, staleResultCode, null);
        check("旧相机结果不结束新请求[stale=" + staleResultCode + "]",
                second.calls.get() == 0 && FileChooserBridge.hasPending(), second.calls.get());
        check("旧相机迟到结果不清空新请求的输出文件[stale=" + staleResultCode + "]",
                newFile != null && newFile.equals(activity.captureFileForTest()),
                activity.captureFileForTest());

        // 新相机真正返回
        writeBytes(activity.captureFileForTest(), 32);
        activity.onActivityResult(newRequestCode, Activity.RESULT_OK, null);
        check("新相机结果交付新请求[stale=" + staleResultCode + "]",
                second.latch.await(5, TimeUnit.SECONDS), true);
        check("新请求只收到一次结果[stale=" + staleResultCode + "]",
                second.calls.get() == 1, second.calls.get());
    }

    /** 新请求是图库时，旧相机结果不能串进图库请求。 */
    private static void stalePickerResultDoesNotCrossIntoCamera() throws Exception {
        FileChooserBridge.resetForTest();
        RecordingCallback cameraCb = new RecordingCallback();
        CameraActivity activity = launchCapture("same-window", cameraCb);
        int cameraRequestCode = activity.lastActivityRequestCode;

        // 新请求是图库
        RecordingCallback pickerCb = new RecordingCallback();
        String pickerToken = FileChooserBridge.begin("same-window", pickerCb);
        Intent pickerIntent = new Intent()
                .putExtra(FileChooserActivity.EXTRA_TOKEN, pickerToken)
                .putExtra(FileChooserActivity.EXTRA_CAPTURE, false)
                .putExtra(FileChooserActivity.EXTRA_ACCEPT, new String[]{"image/*"});
        activity.onNewIntent(pickerIntent);
        int pickerRequestCode = activity.lastActivityRequestCode;
        check("图库请求码与旧相机不同", pickerRequestCode != cameraRequestCode,
                pickerRequestCode + "/" + cameraRequestCode);

        // 旧相机结果迟到：不能结束图库请求
        activity.onActivityResult(cameraRequestCode, Activity.RESULT_OK, null);
        check("旧相机结果不结束新图库请求",
                pickerCb.calls.get() == 0 && FileChooserBridge.hasPending(), pickerCb.calls.get());

        // 图库正常返回一张图
        Intent data = new Intent().setData(Uri.parse("content://media/synthetic-1"));
        activity.onActivityResult(pickerRequestCode, Activity.RESULT_OK, data);
        check("图库结果交付新图库请求", pickerCb.latch.await(5, TimeUnit.SECONDS), true);
        check("图库请求收到的是图库返回的图片",
                pickerCb.last != null && pickerCb.last.length == 1
                        && "content://media/synthetic-1".equals(pickerCb.last[0].toString()),
                pickerCb.last == null ? null : pickerCb.last[0]);
    }

    // ---- helpers -------------------------------------------------------

    private static CameraActivity launchCapture(String owner, RecordingCallback callback) {
        CameraActivity activity = new CameraActivity();
        activity.setCacheDirForTest(tempCache("capture"));
        activity.setContentResolverForTest(new ImageResolver());
        activity.setIntent(captureIntent(FileChooserBridge.begin(owner, callback)));
        activity.onCreate((Bundle) null);
        return activity;
    }

    private static Intent captureIntent(String token) {
        return new Intent()
                .putExtra(FileChooserActivity.EXTRA_TOKEN, token)
                .putExtra(FileChooserActivity.EXTRA_CAPTURE, true)
                .putExtra(FileChooserActivity.EXTRA_ACCEPT, new String[]{"image/*"});
    }

    private static File tempCache(String tag) {
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "sakura-camera-test-" + tag + "-" + System.nanoTime());
        dir.mkdirs();
        return dir;
    }

    private static void writeBytes(File file, int count) throws Exception {
        if (file == null) {
            throw new IllegalStateException("no capture file");
        }
        if (file.getParentFile() != null) {
            file.getParentFile().mkdirs();
        }
        FileOutputStream stream = new FileOutputStream(file);
        try {
            stream.write(new byte[count]);
        } finally {
            stream.close();
        }
    }

    private static File findShrunkFile(File captureDir) {
        if (captureDir == null) {
            return null;
        }
        File[] files = captureDir.listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (file != null && file.getName().startsWith("shrunk-")) {
                return file;
            }
        }
        return null;
    }

    /** 恒定 EXIF 方向值（避免测试依赖 Android 常量）。 */
    private static final class ExifOrientation {
        static final int NORMAL = 1;
        static final int ROTATE_90 = 6;
    }

    private static void writeOversizeJpeg(File file, int width, int height, int orientation,
                                          long paddingBytes) throws Exception {
        // 画面本身很小、很简单（纯色 + 左上红块），真正的「超预算」来自 COM 段填充：
        // 这样缩图/EXIF 方向走的是真实生产路径，又不必在测试里反复编解码巨图。
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D graphics = image.createGraphics();
        graphics.setColor(new java.awt.Color(0x30, 0x30, 0x30));
        graphics.fillRect(0, 0, width, height);
        int blockWidth = Math.max(8, width / 10);
        int blockHeight = Math.max(8, height / 10);
        graphics.setColor(java.awt.Color.RED);
        graphics.fillRect(0, 0, blockWidth, blockHeight);
        graphics.dispose();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", buffer);
        byte[] jpeg = buffer.toByteArray();
        if (jpeg.length < 4 || (jpeg[0] & 0xFF) != 0xFF || (jpeg[1] & 0xFF) != 0xD8) {
            throw new IllegalStateException("synthetic jpeg generation failed");
        }
        int eoi = jpeg.length - 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // SOI
        out.write(buildExifApp1(orientation));       // EXIF Orientation
        out.write(jpeg, 2, eoi - 2);                 // 到 EOI 之前的原始数据
        long written = 0;
        while (written < paddingBytes) {
            int chunk = (int) Math.min(65533, paddingBytes - written);
            out.write(0xFF);
            out.write(0xFE);                          // COM 段
            int length = chunk + 2;
            out.write((length >> 8) & 0xFF);
            out.write(length & 0xFF);
            out.write(new byte[chunk]);
            written += chunk;
        }
        out.write(jpeg, eoi, 2);                     // EOI

        FileOutputStream stream = new FileOutputStream(file);
        try {
            stream.write(out.toByteArray());
        } finally {
            stream.close();
        }
    }

    /** 构造 "Exif\0\0" + 最小 TIFF（IFD0 仅一个 Orientation 条目）。 */
    private static byte[] buildExifApp1(int orientation) {
        byte[] tiff = new byte[26];
        tiff[0] = 'I';
        tiff[1] = 'I';
        tiff[2] = 0x2A;
        tiff[3] = 0x00;
        writeIntLittle(tiff, 4, 8);                   // IFD0 offset
        writeShortLittle(tiff, 8, 1);                 // entry count
        writeShortLittle(tiff, 10, 0x0112);           // tag Orientation
        writeShortLittle(tiff, 12, 3);                // type SHORT
        writeIntLittle(tiff, 14, 1);                  // count
        writeShortLittle(tiff, 18, orientation);      // value
        writeIntLittle(tiff, 22, 0);                  // next IFD

        byte[] payload = new byte[6 + tiff.length];
        payload[0] = 'E';
        payload[1] = 'x';
        payload[2] = 'i';
        payload[3] = 'f';
        payload[4] = 0;
        payload[5] = 0;
        System.arraycopy(tiff, 0, payload, 6, tiff.length);

        byte[] segment = new byte[4 + payload.length];
        segment[0] = (byte) 0xFF;
        segment[1] = (byte) 0xE1;
        int length = payload.length + 2;
        segment[2] = (byte) ((length >> 8) & 0xFF);
        segment[3] = (byte) (length & 0xFF);
        System.arraycopy(payload, 0, segment, 4, payload.length);
        return segment;
    }

    private static void writeShortLittle(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeIntLittle(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xFF);
        data[offset + 1] = (byte) ((value >> 8) & 0xFF);
        data[offset + 2] = (byte) ((value >> 16) & 0xFF);
        data[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /** 采样右上/左上区域，判断红色方块确实随着 90° 旋转换了角。 */
    private static boolean isRedTopRight(BufferedImage image) {
        int regionW = Math.max(8, image.getWidth() / 6);
        int regionH = Math.max(8, image.getHeight() / 6);
        double topLeft = redFraction(image, 0, 0, regionW, regionH);
        double topRight = redFraction(image,
                Math.max(0, image.getWidth() - regionW), 0, regionW, regionH);
        return topRight > 0.2 && topLeft < 0.02;
    }

    private static double redFraction(BufferedImage image, int x0, int y0, int width, int height) {
        int red = 0;
        int total = 0;
        for (int y = y0; y < Math.min(image.getHeight(), y0 + height); y += 1) {
            for (int x = x0; x < Math.min(image.getWidth(), x0 + width); x += 1) {
                int rgb = image.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r > 180 && g < 90 && b < 90) {
                    red += 1;
                }
                total += 1;
            }
        }
        return total == 0 ? 0 : (double) red / total;
    }
}
