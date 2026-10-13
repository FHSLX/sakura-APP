package com.sakura.remote;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 悬浮窗/App 文件选择与拍照用的**不导出**辅助 Activity。
 *
 * <p>宿主（Service 窗口或 App 的 WebView）拿不到 Activity 结果，所以本 Activity
 * 只为调系统选择器/相机并回传结果而存在，不展示自己的界面（透明主题，选完即 finish）。
 *
 * <p>两个入口：
 * <ul>
 *   <li>{@link #EXTRA_CAPTURE} 为 false → 系统图库（{@code ACTION_OPEN_DOCUMENT}）；</li>
 *   <li>{@link #EXTRA_CAPTURE} 为 true → 系统相机
 *       （{@code ACTION_IMAGE_CAPTURE} + {@code EXTRA_OUTPUT} + FileProvider content URI）。
 *       绝不退化成「打开图库」或「只回传 thumbnail」。</li>
 * </ul>
 *
 * <p><b>每次 launch 独立 requestCode</b>：同一实例可能在一次相机/图库还在途时被
 * 新请求复用（singleTop + onNewIntent）。如果所有相机共用一个 requestCode，
 * 旧结果回来时会读到新请求的输出文件、按新 token 结束新请求。这里给每次真实
 * launch 分配唯一 requestCode，并保存 {@link Launch} 记录；结果回来先按
 * requestCode 找到所属 launch，token 不匹配的旧结果只清理自己的临时文件，
 * 绝不读取/清空/取消当前新请求。
 *
 * <p>安全约束：
 *   - 只接受 {@code content://}（拒绝任意 {@code file://} 私有路径）；
 *   - MIME 必须是 image/*，并做**有界读取**校验：未知大小时也最多读
 *     {@link #MAX_FILE_BYTES}+1 字节，绝不把云端超大图整份读进内存；
 *   - 拍照输出只写在本应用 cache 目录（复用已配置的 {@code cache-path}，
 *     不扩大共享路径），通过 FileProvider 临时授权给相机；
 *   - 相机结果可能 {@code RESULT_OK} 但 {@code data == null}，因此只认
 *     **预先持有的输出 URI**，不读 thumbnail；
 *   - 超过附件预算的照片在后台缩小并**按 EXIF Orientation 规范方向**后重编码，
 *     再核对预算；
 *   - 校验/缩小在后台线程做，主线程不阻塞；
 *   - 取消/返回/无相机/写失败都通过 {@link FileChooserBridge} 恰好一次结束，
 *     并给出明确提示；
 *   - 临时照片有界清理（TTL + 数量），但**回调后不立即删除**，否则 WebView 读不到；
 *   - 不索要全盘存储权限，不申请任何危险权限。
 */
public class FileChooserActivity extends Activity {

    static final String EXTRA_TOKEN = "file_chooser_token";
    static final String EXTRA_OWNER = "file_chooser_owner";
    static final String EXTRA_ACCEPT = "file_chooser_accept";
    static final String EXTRA_MULTIPLE = "file_chooser_multiple";
    /** true = 「拍照」入口（capture=environment），走系统相机。 */
    static final String EXTRA_CAPTURE = "file_chooser_capture";

    /** 请求码起始值；每次 launch 从 {@link #REQUEST_SEQ} 取一个唯一值。 */
    private static final int REQUEST_CODE_BASE = 0x5345;
    /** Activity 请求码只能是 16 位有符号范围。 */
    private static final int REQUEST_CODE_MAX = 0x7FFF;
    private static final AtomicLong REQUEST_SEQ = new AtomicLong(REQUEST_CODE_BASE);

    /**
     * 与后端 MAX_IMAGE_BYTES(8MiB)/MAX_REQUEST_BYTES(16MiB) 对齐：
     * 6MiB 二进制 → base64 约 8MiB，加 JSON 也远低于 16MiB 请求体上限。
     */
    private static final long MAX_FILE_BYTES = 6L * 1024 * 1024;
    /** 有界读取的块大小。 */
    private static final int READ_CHUNK = 64 * 1024;

    /** 临时照片目录（位于 cache-path 内，复用已有 FileProvider 配置）。 */
    private static final String CAPTURE_DIR = "sakura-captures";
    /** 临时照片保留时长：足够 WebView 读取，又不会无限堆积。 */
    private static final long CAPTURE_TTL_MS = 24L * 60 * 60 * 1000;
    /** 目录里最多保留几张临时照片。 */
    private static final int CAPTURE_KEEP_MAX = 8;
    /** 缩放后 JPEG 质量。 */
    private static final int JPEG_QUALITY = 82;
    /** 缩小尺寸的目标最长边档位（从大到小逐级尝试）。 */
    private static final int[] SHRINK_TARGETS = {2048, 1536, 1024, 720};

    private static final AtomicLong CAPTURE_SEQ = new AtomicLong(0);

    /** 一次真实 launch 的归属记录（按 requestCode 索引）。 */
    static final class Launch {
        final int requestCode;
        final boolean capture;
        final String token;
        final File file;
        final Uri uri;

        Launch(int requestCode, boolean capture, String token, File file, Uri uri) {
            this.requestCode = requestCode;
            this.capture = capture;
            this.token = token == null ? "" : token;
            this.file = file;
            this.uri = uri;
        }
    }

    private String token = "";
    private boolean multiple;
    private boolean captureRequested;
    private boolean handled;

    /** 在途 launch：requestCode → 记录。 */
    private final Map<Integer, Launch> launches = new HashMap<>();
    /** 最近一次分配的 requestCode（测试断言用）。 */
    private int lastRequestCode = -1;
    /** 最近一次明确反馈文案（测试断言用，不参与生产逻辑）。 */
    private String lastProblem = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        token = intent == null ? "" : intent.getStringExtra(EXTRA_TOKEN);
        multiple = intent != null && intent.getBooleanExtra(EXTRA_MULTIPLE, false);
        captureRequested = intent != null && intent.getBooleanExtra(EXTRA_CAPTURE, false);
        if (token == null || token.isEmpty()) {
            finish();
            return;
        }
        if (savedInstanceState != null) {
            // 进程死亡后的重建：Bridge 里已没有对应回调，重新弹选择器没有意义
            finish();
            return;
        }
        launchChooser();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String next = intent == null ? "" : intent.getStringExtra(EXTRA_TOKEN);
        if (next == null || next.isEmpty() || next.equals(token)) {
            return;
        }
        // singleTop 复用：旧 token 已由新的 begin 按取消结束。旧 launch 记录保留在
        // launches 里，等它自己的结果按旧 requestCode 回来时只做清理，绝不影响本次。
        FileChooserBridge.abandon(token);
        token = next;
        multiple = intent.getBooleanExtra(EXTRA_MULTIPLE, false);
        captureRequested = intent.getBooleanExtra(EXTRA_CAPTURE, false);
        handled = false;
        launchChooser();
    }

    /** 按入口分派：拍照走系统相机，其余走图库。 */
    private void launchChooser() {
        if (captureRequested) {
            launchCamera();
        } else {
            launchPicker();
        }
    }

    /** 取一个当前没有在途记录的 requestCode。 */
    private int nextRequestCode() {
        for (int attempt = 0; attempt < 64; attempt += 1) {
            long raw = REQUEST_SEQ.incrementAndGet();
            int code = (int) (raw % REQUEST_CODE_MAX);
            if (code < REQUEST_CODE_BASE) {
                code += REQUEST_CODE_BASE;
            }
            if (!launches.containsKey(code)) {
                return code;
            }
        }
        // 理论上不会走到：极端情况下回到起始值附近顺延
        return REQUEST_CODE_BASE + launches.size();
    }

    private void launchPicker() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType(resolveMimeType());
        pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multiple);
        int requestCode = nextRequestCode();
        launches.put(requestCode, new Launch(requestCode, false, token, null, null));
        lastRequestCode = requestCode;
        try {
            startActivityForResult(pick, requestCode);
        } catch (Exception error) {
            launches.remove(requestCode);
            finishWith(null);
        }
    }

    /**
     * 「拍照」入口：公开的 {@code ACTION_IMAGE_CAPTURE} + {@code EXTRA_OUTPUT}。
     *
     * <p>输出 URI 由本应用 FileProvider 提供，位置在 cache 目录内，
     * 只给相机临时读写授权，不需要全盘存储权限，也不新增 CAMERA 权限
     * （调用外部相机通常不需要；系统相机自己持有 CAMERA）。
     */
    private void launchCamera() {
        Intent capture = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        try {
            if (capture.resolveActivity(getPackageManager()) == null) {
                notifyProblem("没有可用的相机应用，无法拍照。");
                finishWith(null);
                return;
            }
        } catch (Throwable error) {
            notifyProblem("无法查询相机应用。");
            finishWith(null);
            return;
        }
        File output = createCaptureFile("photo-");
        if (output == null) {
            notifyProblem("无法创建照片临时文件。");
            finishWith(null);
            return;
        }
        Uri uri = uriFor(output);
        if (uri == null) {
            deleteQuietly(output);
            notifyProblem("无法准备照片输出位置。");
            finishWith(null);
            return;
        }
        int requestCode = nextRequestCode();
        launches.put(requestCode, new Launch(requestCode, true, token, output, uri));
        lastRequestCode = requestCode;
        capture.putExtra(MediaStore.EXTRA_OUTPUT, uri);
        capture.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(capture, requestCode);
        } catch (Exception error) {
            // 启动失败：清掉刚建的文件与本记录，不留下垃圾，也不留 pending
            launches.remove(requestCode);
            deleteQuietly(output);
            notifyProblem("无法打开相机。");
            finishWith(null);
        }
    }

    /** 只做图片选择；WebView 的 accept 里没有可用图片类型时也退回 image/*。 */
    private String resolveMimeType() {
        Intent intent = getIntent();
        String[] accept = intent == null ? null : intent.getStringArrayExtra(EXTRA_ACCEPT);
        if (accept != null) {
            for (String type : accept) {
                if (type != null && type.startsWith("image/")) {
                    return type;
                }
            }
        }
        return "image/*";
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        // 结果先按 requestCode 找归属：找不到说明是已被替换/未知的旧 launch，
        // 直接忽略，绝不触碰当前请求。
        final Launch launch = launches.remove(requestCode);
        if (launch == null) {
            return;
        }
        if (launch.capture) {
            handleCaptureResult(launch, resultCode);
            return;
        }
        final List<Uri> candidates = (resultCode == RESULT_OK && data != null)
                ? collectCandidates(data) : new ArrayList<>();
        final boolean wantMultiple = multiple;
        final String validationToken = launch.token;
        // 有界校验可能要读云端大图：放后台线程，主线程不阻塞
        Thread worker = new Thread(() -> {
            final Uri[] accepted = validate(candidates, wantMultiple);
            runOnUiThread(() -> {
                // singleTop can replace this request while a provider is still reading.
                // A late result must never be delivered using the new request's token.
                if (handled || isFinishing() || !validationToken.equals(token)) {
                    return;
                }
                finishWith(accepted);
            });
        }, "sakura-file-chooser-validate");
        worker.start();
    }

    /**
     * 相机返回。
     *
     * <p>只认本次 launch 预先持有的输出 URI；{@code data} 可能是 null（系统相机常态）。
     */
    private void handleCaptureResult(final Launch launch, int resultCode) {
        if (!launch.token.equals(token)) {
            // 被替换掉的旧相机的迟到结果：只清理自己的临时文件，
            // 不投递、不结束替换后的新请求
            deleteQuietly(launch.file);
            return;
        }
        if (resultCode != RESULT_OK) {
            // 用户取消：立刻清掉本次临时文件；已有待发附件不受影响
            deleteQuietly(launch.file);
            finishWith(null);
            return;
        }
        final String validationToken = launch.token;
        Thread worker = new Thread(() -> {
            final Uri accepted = validateCapture(launch.file, launch.uri);
            runOnUiThread(() -> {
                if (handled || isFinishing() || !validationToken.equals(token)) {
                    return;
                }
                finishWith(accepted == null ? null : new Uri[]{accepted});
            });
        }, "sakura-camera-validate");
        worker.start();
    }

    /**
     * 后台：校验相机写出的照片，必要时缩小到附件预算内。
     *
     * @return 可交给 WebView 的 content URI；失败返回 null（已给出明确提示）
     */
    private Uri validateCapture(File output, Uri uri) {
        if (output == null || uri == null || !output.isFile() || output.length() <= 0) {
            notifyProblem("相机没有返回照片。");
            deleteQuietly(output);
            return null;
        }
        File candidate = output;
        if (candidate.length() > MAX_FILE_BYTES) {
            // 全尺寸照片可能超过 6MiB 附件预算：缩小尺寸后重编码
            File shrunk = shrinkToBudget(candidate);
            if (shrunk == null) {
                notifyProblem("照片太大（上限 6 MB），已取消。");
                deleteQuietly(candidate);
                return null;
            }
            candidate = shrunk;
        }
        Uri candidateUri = uriFor(candidate);
        if (candidateUri == null || !isReadableBoundedImage(candidateUri)) {
            notifyProblem("相机返回的文件不是有效图片。");
            deleteQuietly(candidate);
            return null;
        }
        return candidateUri;
    }

    /**
     * 把超过预算的图片按最长边逐级缩小、按 EXIF 方向规范旋转后重编码为 JPEG，
     * 再核对预算。
     *
     * <p>包内可见：测试可以覆盖它，但生产测试也用真实实现跑端到端用例
     * （见 {@code CameraCaptureBehaviorTest} 的缩图 + EXIF 用例）。
     *
     * @return 缩小后的新文件；无法解码或仍然超预算时返回 null
     */
    File shrinkToBudget(File source) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try {
            BitmapFactory.decodeFile(source.getAbsolutePath(), bounds);
        } catch (Throwable error) {
            return null;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        // EXIF Orientation 不在解码结果里，必须显式读取并应用，否则竖拍会变横向/倒向
        final int orientation = readOrientation(source);
        int longest = Math.max(bounds.outWidth, bounds.outHeight);
        for (int target : SHRINK_TARGETS) {
            int sample = 1;
            while (longest / (sample * 2) >= target) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            Bitmap bitmap = null;
            try {
                bitmap = BitmapFactory.decodeFile(source.getAbsolutePath(), options);
            } catch (Throwable error) {
                bitmap = null;
            }
            if (bitmap == null) {
                return null;
            }
            Bitmap oriented = applyOrientation(bitmap, orientation);
            File out = createCaptureFile("shrunk-");
            if (out == null) {
                recycleQuietly(oriented);
                return null;
            }
            FileOutputStream stream = null;
            boolean written = false;
            try {
                stream = new FileOutputStream(out);
                written = oriented != null
                        && oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream);
                stream.flush();
            } catch (Throwable error) {
                written = false;
            } finally {
                closeQuietly(stream);
                recycleQuietly(oriented);
            }
            if (!written || out.length() <= 0 || out.length() > MAX_FILE_BYTES) {
                deleteQuietly(out);
                continue;
            }
            deleteQuietly(source);
            return out;
        }
        return null;
    }

    /**
     * 按 EXIF 方向把位图旋转/镜像到「看起来正」的方向。
     *
     * <p>这是缩图不丢方向的唯一保证：{@code BitmapFactory} 不会替你转。
     */
    Bitmap applyOrientation(Bitmap bitmap, int orientation) {
        if (bitmap == null || !CaptureOrientation.needsTransform(orientation)) {
            return bitmap;
        }
        try {
            Matrix matrix = new Matrix();
            int degrees = CaptureOrientation.rotationDegrees(orientation);
            if (degrees != 0) {
                matrix.postRotate(degrees);
            }
            float scaleX = CaptureOrientation.flipHorizontal(orientation) ? -1f : 1f;
            float scaleY = CaptureOrientation.flipVertical(orientation) ? -1f : 1f;
            if (scaleX != 1f || scaleY != 1f) {
                matrix.postScale(scaleX, scaleY);
            }
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                    bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != null && rotated != bitmap) {
                recycleQuietly(bitmap);
                return rotated;
            }
            return bitmap;
        } catch (Throwable error) {
            // 变换失败时退回未旋转的原图：宁可方向不对，也不能丢失附件
            return bitmap;
        }
    }

    /** 读取 EXIF Orientation；读不到按 1（正常）处理。 */
    int readOrientation(File file) {
        if (file == null) {
            return CaptureOrientation.NORMAL;
        }
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            return exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    CaptureOrientation.NORMAL);
        } catch (Throwable error) {
            return CaptureOrientation.NORMAL;
        }
    }

    /** 新建一张临时照片文件；同时做有界清理（包内可见：测试可覆盖以模拟写失败）。 */
    File createCaptureFile(String prefix) {
        try {
            File dir = new File(getCacheDir(), CAPTURE_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return null;
            }
            pruneCaptureDir(dir);
            String name = prefix + System.currentTimeMillis() + "-"
                    + CAPTURE_SEQ.incrementAndGet() + ".jpg";
            return new File(dir, name);
        } catch (Throwable error) {
            return null;
        }
    }

    /**
     * 有界清理：删掉超过 TTL 的旧文件，并只保留最近 {@link #CAPTURE_KEEP_MAX} 张。
     *
     * <p>绝不删除本次刚建的文件，也绝不删除「刚交给 WebView」的文件 ——
     * 回调后立即删除会让 WebView 读不到图片。
     */
    private void pruneCaptureDir(File dir) {
        try {
            File[] files = dir.listFiles();
            if (files == null || files.length == 0) {
                return;
            }
            long now = System.currentTimeMillis();
            List<File> keep = new ArrayList<>();
            for (File file : files) {
                if (file == null || !file.isFile()) {
                    continue;
                }
                if (now - file.lastModified() > CAPTURE_TTL_MS) {
                    deleteQuietly(file);
                    continue;
                }
                keep.add(file);
            }
            if (keep.size() <= CAPTURE_KEEP_MAX) {
                return;
            }
            keep.sort((left, right) -> Long.compare(right.lastModified(), left.lastModified()));
            for (int index = CAPTURE_KEEP_MAX; index < keep.size(); index += 1) {
                deleteQuietly(keep.get(index));
            }
        } catch (Throwable ignored) {
            // 清理失败不影响本次拍照
        }
    }

    /** 包内可见：测试可覆盖以模拟 FileProvider 配置失败。 */
    Uri uriFor(File file) {
        if (file == null) {
            return null;
        }
        try {
            return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
        } catch (Throwable error) {
            return null;
        }
    }

    /** 明确反馈（Toast）：后台线程也能安全调用。同时留档供测试断言。 */
    private void notifyProblem(final String message) {
        lastProblem = message == null ? "" : message;
        runOnUiThread(() -> {
            try {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
                // 没有 UI 环境时忽略
            }
        });
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            if (file.exists()) {
                file.delete();
            }
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void closeQuietly(FileOutputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private static void recycleQuietly(Bitmap bitmap) {
        if (bitmap == null) {
            return;
        }
        try {
            bitmap.recycle();
        } catch (Throwable ignored) {
            // 忽略
        }
    }

    private List<Uri> collectCandidates(Intent data) {
        List<Uri> candidates = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount(); index += 1) {
                ClipData.Item item = clip.getItemAt(index);
                if (item != null && item.getUri() != null) {
                    candidates.add(item.getUri());
                }
            }
        } else if (data.getData() != null) {
            candidates.add(data.getData());
        }
        return candidates;
    }

    /** 后台线程：校验类型 + 有界大小 + 可读性，并尽量取得持久读权限。 */
    private Uri[] validate(List<Uri> candidates, boolean wantMultiple) {
        List<Uri> accepted = new ArrayList<>();
        for (Uri uri : candidates) {
            if (isReadableBoundedImage(uri)) {
                accepted.add(uri);
            }
        }
        if (accepted.isEmpty()) {
            return null;
        }
        if (!wantMultiple) {
            // 单选语义：只回传第一张，避免网页 input 收到多值
            return new Uri[]{accepted.get(0)};
        }
        return accepted.toArray(new Uri[0]);
    }

    private boolean isReadableBoundedImage(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) {
            return false;
        }
        InputStream stream = null;
        try {
            String type = getContentResolver().getType(uri);
            if (type == null || !type.startsWith("image/")) {
                return false;
            }
            stream = getContentResolver().openInputStream(uri);
            if (stream == null) {
                return false;
            }
            // 有界读取：未知大小也能在超过上限时立即拒绝，绝不整份读入
            long total = 0;
            byte[] buffer = new byte[READ_CHUNK];
            int read;
            while ((read = stream.read(buffer)) > 0) {
                total += read;
                if (total > MAX_FILE_BYTES) {
                    return false;
                }
            }
            // 让辅助页 finish 后 WebView 仍能读取（不扩权，只持久化本次选择）
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (Throwable ignored) {
                // 某些 provider 不支持持久化：本次会话内仍可读
            }
            return true;
        } catch (Throwable error) {
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // 忽略
                }
            }
        }
    }

    private void finishWith(Uri[] uris) {
        if (handled) {
            return;
        }
        handled = true;
        if (uris == null || uris.length == 0) {
            FileChooserBridge.cancel(token);
        } else {
            FileChooserBridge.deliver(token, uris);
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        // 用户直接返回 = 取消，必须让网页知道，不能让文件输入永久 pending
        finishWith(null);
    }

    @Override
    protected void onDestroy() {
        launches.clear();
        if (!handled) {
            handled = true;
            FileChooserBridge.cancel(token);
        }
        super.onDestroy();
    }

    // ---- 测试钩子（包内可见，不改变生产行为）----------------------------

    boolean captureRequestedForTest() {
        return captureRequested;
    }

    int lastRequestCodeForTest() {
        return lastRequestCode;
    }

    int launchCountForTest() {
        return launches.size();
    }

    /** 最近一次仍未结束的拍照 launch 的输出 URI。 */
    Uri captureUriForTest() {
        Launch launch = lastCaptureLaunch();
        return launch == null ? null : launch.uri;
    }

    /** 最近一次仍未结束的拍照 launch 的输出文件。 */
    File captureFileForTest() {
        Launch launch = lastCaptureLaunch();
        return launch == null ? null : launch.file;
    }

    String lastProblemForTest() {
        return lastProblem;
    }

    private Launch lastCaptureLaunch() {
        Launch best = null;
        for (Launch launch : launches.values()) {
            if (launch.capture && (best == null || launch.requestCode > best.requestCode)) {
                best = launch;
            }
        }
        return best;
    }
}
