package com.sakura.remote;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MEDIA-01 图片选择回调的真实生产行为测试。
 *
 * <p>只驱动 {@link FileChooserBridge} / {@link OverlayFileChooser} /
 * {@link FileChooserActivity} 这三个真实生产类，系统接口用 tests/androidstub-overlay 替身。
 * 覆盖：
 *   1) 回调恰好一次；重复 deliver / cancel 无效；
 *   2) 接管失败用 abandon（不调用回调）返回 false，符合 WebChromeClient 契约；
 *   3) owner 作用域取消：旧窗口 destroy 不取消新窗口请求；
 *   4) 新请求替换旧请求时旧回调恰好取消一次；
 *   5) 有界大小校验：未知/超大图片按上限拒绝，非图片与 file:// 拒绝；
 *   6) 选中的 content Uri 交给回调，且流被关闭。
 */
public final class PickerBehaviorTest {

    private static int failures;

    private static void check(String name, boolean ok, Object detail) {
        System.out.println((ok ? "[PASS] " : "[FAIL] ") + name
                + (ok ? "" : " -> " + String.valueOf(detail)));
        if (!ok) {
            failures++;
        }
    }

    /** 只记录结果的 ValueCallback。 */
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

    /** 抛出异常的 Context：模拟 startActivity 失败。 */
    private static final class ThrowingContext extends Context {
        @Override
        public void startActivity(Intent intent) {
            throw new IllegalStateException("no activity");
        }
    }

    /** 可注入图片类型/内容的 ContentResolver 替身。 */
    private static final class FakeResolver extends ContentResolver {
        String type = "image/png";
        byte[] payload = new byte[0];
        boolean throwOnOpen;
        CountingStream opened;

        @Override
        public String getType(Uri uri) {
            return type;
        }

        @Override
        public InputStream openInputStream(Uri uri) {
            if (throwOnOpen) {
                throw new IllegalStateException("cloud provider error");
            }
            opened = new CountingStream(payload);
            return opened;
        }
    }

    /** 统计实际读取字节数的流。 */
    private static final class CountingStream extends ByteArrayInputStream {
        long readBytes;
        boolean closed;

        CountingStream(byte[] data) {
            super(data);
        }

        @Override
        public synchronized int read(byte[] buffer, int offset, int length) {
            int read = super.read(buffer, offset, length);
            if (read > 0) {
                readBytes += read;
            }
            return read;
        }

        @Override
        public synchronized int read() {
            int value = super.read();
            if (value >= 0) {
                readBytes++;
            }
            return value;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    public static void main(String[] args) throws Exception {
        bridgeExactlyOnce();
        bridgeAbandonDoesNotCall();
        bridgeOwnerScopedCancel();
        bridgeReplacementCancelsOld();
        overlayStartFailureKeepsContract();
        overlaySuccessRegistersToken();
        boundedSizeValidation();
        lateValidationCannotDeliverToReplacement();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production sources: FileChooserBridge/OverlayFileChooser/FileChooserActivity)");
    }

    private static void bridgeExactlyOnce() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        String token = FileChooserBridge.begin("owner-1", callback);
        Uri uri = Uri.parse("content://media/1");
        check("deliver 生效一次", FileChooserBridge.deliver(token, new Uri[]{uri}), true);
        check("回调恰好一次", callback.calls.get() == 1 && callback.last != null
                && callback.last.length == 1, callback.calls.get());
        check("重复 deliver 无效", !FileChooserBridge.deliver(token, new Uri[]{uri}), true);
        check("重复 deliver 不重复回调", callback.calls.get() == 1, callback.calls.get());
    }

    private static void bridgeAbandonDoesNotCall() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        String token = FileChooserBridge.begin("owner-1", callback);
        FileChooserBridge.abandon(token);
        check("abandon 后没有 pending", !FileChooserBridge.hasPending(), true);
        check("abandon 不调用回调（返回 false 由 WebView 取消）",
                callback.calls.get() == 0, callback.calls.get());
        check("abandon 后旧 token 失效",
                !FileChooserBridge.deliver(token, null) && callback.calls.get() == 0,
                callback.calls.get());
    }

    private static void bridgeOwnerScopedCancel() {
        FileChooserBridge.resetForTest();
        RecordingCallback oldOwner = new RecordingCallback();
        FileChooserBridge.begin("window-old", oldOwner);
        // 旧窗口销毁：只影响自己的 owner
        FileChooserBridge.cancelAll("window-old");
        check("本 owner 取消生效", oldOwner.calls.get() == 1, oldOwner.calls.get());

        RecordingCallback newOwner = new RecordingCallback();
        String token = FileChooserBridge.begin("window-new", newOwner);
        FileChooserBridge.cancelAll("window-old");   // 旧窗口迟到的 destroy
        check("旧窗口 destroy 不取消新窗口请求",
                FileChooserBridge.hasPending() && newOwner.calls.get() == 0, newOwner.calls.get());
        check("新窗口请求仍可正常结束",
                FileChooserBridge.deliver(token, null) && newOwner.calls.get() == 1,
                newOwner.calls.get());
    }

    private static void bridgeReplacementCancelsOld() {
        FileChooserBridge.resetForTest();
        RecordingCallback first = new RecordingCallback();
        RecordingCallback second = new RecordingCallback();
        FileChooserBridge.begin("window-1", first);
        FileChooserBridge.begin("window-1", second);   // 同一窗口重复点
        check("新请求按取消结束旧回调",
                first.calls.get() == 1 && first.last == null, first.calls.get());
        check("旧 token 不能再影响新请求",
                !FileChooserBridge.deliver("stale-token", null) && second.calls.get() == 0,
                second.calls.get());
        check("新请求仍 pending", FileChooserBridge.hasPending(), true);
        FileChooserBridge.cancelAll("window-1");
        check("取消新请求恰好一次", second.calls.get() == 1, second.calls.get());
    }

    private static void overlayStartFailureKeepsContract() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        boolean handled = OverlayFileChooser.request(new ThrowingContext(), "window-1", callback, null);
        check("startActivity 失败返回 false（交给 WebView 取消）", !handled, handled);
        check("startActivity 失败不调用回调", callback.calls.get() == 0, callback.calls.get());
        check("startActivity 失败不留 pending", !FileChooserBridge.hasPending(), true);
    }

    private static void overlaySuccessRegistersToken() {
        FileChooserBridge.resetForTest();
        RecordingCallback callback = new RecordingCallback();
        Context context = new Context();
        boolean handled = OverlayFileChooser.request(context, "window-1", callback, null);
        check("接管成功返回 true", handled, handled);
        check("接管成功登记 pending 且属主正确",
                FileChooserBridge.hasPending() && "window-1".equals(FileChooserBridge.pendingOwnerForTest()),
                FileChooserBridge.pendingOwnerForTest());
        FileChooserBridge.cancelAll("window-1");
        check("成功后按 owner 取消一次", callback.calls.get() == 1, callback.calls.get());
    }

    private static void boundedSizeValidation() throws Exception {
        // 允许：1MiB 图片
        FileChooserActivity activity = prepareActivity();
        FakeResolver resolver = new FakeResolver();
        resolver.payload = new byte[1024 * 1024];
        activity.setContentResolverForTest(resolver);
        RecordingCallback okCallback = new RecordingCallback();
        String okToken = FileChooserBridge.begin("window-1", okCallback);
        drive(activity, okToken, false, "content://media/ok");
        check("正常图片被接受", okCallback.calls.get() == 1 && okCallback.last != null
                && okCallback.last.length == 1, okCallback.calls.get());
        check("接受后流已关闭", resolver.opened != null && resolver.opened.closed,
                resolver.opened == null ? null : resolver.opened.closed);

        // 拒绝：超过上限（未知大小时有界读取）
        FileChooserActivity big = prepareActivity();
        FakeResolver bigResolver = new FakeResolver();
        bigResolver.payload = new byte[7 * 1024 * 1024];
        big.setContentResolverForTest(bigResolver);
        RecordingCallback bigCallback = new RecordingCallback();
        String bigToken = FileChooserBridge.begin("window-1", bigCallback);
        drive(big, bigToken, false, "content://media/big");
        check("超过上限的图片被拒绝", bigCallback.calls.get() == 1 && bigCallback.last == null,
                bigCallback.calls.get());
        long limit = 6L * 1024 * 1024 + 64 * 1024;
        check("有界读取没有整份读入超大图",
                bigResolver.opened != null && bigResolver.opened.readBytes <= limit,
                bigResolver.opened == null ? null : bigResolver.opened.readBytes);

        // 拒绝：非图片
        FileChooserActivity wrongType = prepareActivity();
        FakeResolver textResolver = new FakeResolver();
        textResolver.type = "text/plain";
        textResolver.payload = new byte[16];
        wrongType.setContentResolverForTest(textResolver);
        RecordingCallback textCallback = new RecordingCallback();
        String textToken = FileChooserBridge.begin("window-1", textCallback);
        drive(wrongType, textToken, false, "content://media/text");
        check("非图片类型被拒绝", textCallback.calls.get() == 1 && textCallback.last == null,
                textCallback.calls.get());

        // 拒绝：file:// 私有路径
        FileChooserActivity fileScheme = prepareActivity();
        FakeResolver fileResolver = new FakeResolver();
        fileResolver.payload = new byte[16];
        fileScheme.setContentResolverForTest(fileResolver);
        RecordingCallback fileCallback = new RecordingCallback();
        String fileToken = FileChooserBridge.begin("window-1", fileCallback);
        drive(fileScheme, fileToken, false, "file:///data/private/secret.png");
        check("file:// 路径被拒绝", fileCallback.calls.get() == 1 && fileCallback.last == null,
                fileCallback.calls.get());
    }

    private static void lateValidationCannotDeliverToReplacement() throws Exception {
        FileChooserBridge.resetForTest();
        CountDownLatch oldOpened = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldUiFinished = new CountDownLatch(1);
        FileChooserActivity activity = new FileChooserActivity() {
            @Override public void runOnUiThread(Runnable action) {
                action.run();
                oldUiFinished.countDown();
            }
        };
        activity.setContentResolverForTest(new ContentResolver() {
            @Override public String getType(Uri uri) { return "image/png"; }
            @Override public InputStream openInputStream(Uri uri) {
                if (uri.toString().endsWith("/old")) {
                    oldOpened.countDown();
                    try { releaseOld.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException error) { Thread.currentThread().interrupt(); }
                }
                return new ByteArrayInputStream(new byte[16]);
            }
        });
        RecordingCallback oldCallback = new RecordingCallback();
        String oldToken = FileChooserBridge.begin("same-window", oldCallback);
        activity.setIntent(new Intent().putExtra(FileChooserActivity.EXTRA_TOKEN, oldToken));
        activity.onCreate(null);
        final int oldRequestCode = activity.lastActivityRequestCode;
        activity.onActivityResult(oldRequestCode, Activity.RESULT_OK,
                new Intent().setData(Uri.parse("content://synthetic/old")));
        check("旧校验已进入异步读取", oldOpened.await(5, TimeUnit.SECONDS), true);
        RecordingCallback replacement = new RecordingCallback();
        String newToken = FileChooserBridge.begin("same-window", replacement);
        activity.onNewIntent(new Intent().putExtra(FileChooserActivity.EXTRA_TOKEN, newToken));
        final int newRequestCode = activity.lastActivityRequestCode;
        releaseOld.countDown();
        check("旧校验任务完成", oldUiFinished.await(5, TimeUnit.SECONDS), true);
        check("旧异步校验不能投递到替换请求", replacement.calls.get() == 0, replacement.calls.get());
        check("旧结果不结束新选择请求", FileChooserBridge.hasPending(), FileChooserBridge.hasPending());
        activity.onActivityResult(newRequestCode, Activity.RESULT_OK,
                new Intent().setData(Uri.parse("content://synthetic/new")));
        replacement.latch.await(5, TimeUnit.SECONDS);
        check("替换请求只收到新选择的图片", replacement.calls.get() == 1 && replacement.last != null
                && "content://synthetic/new".equals(replacement.last[0].toString()), replacement.calls.get());
    }

    private static FileChooserActivity prepareActivity() {
        FileChooserActivity activity = new FileChooserActivity();
        Intent intent = new Intent();
        activity.setIntent(intent);
        return activity;
    }

    /** 用真实生命周期驱动：onActivityResult → 后台有界校验 → 回调。 */
    private static void drive(FileChooserActivity activity, String token, boolean multiple,
                              String uri) throws Exception {
        activity.getIntent().putExtra(FileChooserActivity.EXTRA_TOKEN, token);
        activity.getIntent().putExtra(FileChooserActivity.EXTRA_MULTIPLE, multiple);
        activity.onCreate((Bundle) null);
        Intent data = new Intent();
        data.setData(Uri.parse(uri));
        // 等待回调（后台校验线程）；请求码按本次 launch 记录取，
        // 不能再写死常量（生产代码已改为每次 launch 独立请求码）
        activity.onActivityResult(activity.lastActivityRequestCode, Activity.RESULT_OK, data);
        // 回调由 bridge 触发；用 pending 状态轮询等待后台线程完成
        long deadline = System.currentTimeMillis() + 5000;
        while (FileChooserBridge.hasPending() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
    }
}
