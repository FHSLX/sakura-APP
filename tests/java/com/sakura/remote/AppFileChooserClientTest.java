package com.sakura.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * App 内文件选择/拍照接线的真实行为测试。
 *
 * <p>被测的是真实生产类 {@link AppFileChooserClient}：它必须
 *   - 仍然继承 Capacitor 的 {@link BridgeWebChromeClient}（不覆盖丢弃已有桥）；
 *   - 把拍照/图库请求转进与悬浮窗共用的
 *     {@link OverlayFileChooser} → {@link FileChooserActivity} 通道
 *     （有预算/EXIF/精确一次回调），并转发 capture 标志；
 *   - 接管失败时回退父类实现，回调不会被调用两次。
 */
public final class AppFileChooserClientTest {

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

        @Override
        public void onReceiveValue(Uri[] value) {
            calls.incrementAndGet();
            last = value;
        }
    }

    private static final class ThrowingContext extends Context {
        @Override
        public void startActivity(Intent intent) {
            throw new IllegalStateException("no helper activity");
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

    public static void main(String[] args) {
        keepsCapacitorParentBridge();
        routesCaptureThroughBoundedChooser();
        routesGalleryWithoutCaptureFlag();
        fallsBackToParentWhenHelperCannotStart();
        nullCallbackIsNotHandled();

        System.out.println();
        if (failures > 0) {
            System.out.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS (real production source: AppFileChooserClient)");
    }

    private static void keepsCapacitorParentBridge() {
        AppFileChooserClient client = new AppFileChooserClient(new Bridge(), "app-owner");
        check("仍然继承 Capacitor BridgeWebChromeClient",
                client instanceof BridgeWebChromeClient, client.getClass().getName());
        check("父类默认行为未被替换（console 回调仍走父类）",
                client.onConsoleMessage(null) == false, "inherited");
    }

    private static void routesCaptureThroughBoundedChooser() {
        FileChooserBridge.resetForTest();
        Context context = new Context();
        WebView webView = new WebView(context);
        AppFileChooserClient client = new AppFileChooserClient(new Bridge(), "app-owner");
        RecordingCallback callback = new RecordingCallback();
        boolean handled = client.onShowFileChooser(webView, callback, new CaptureParams(true));
        check("拍照请求被接管", handled, handled);
        check("接管后不再调用父类相机路径",
                ((BridgeWebChromeClient) client).superFileChooserCalls == 0,
                ((BridgeWebChromeClient) client).superFileChooserCalls);
        check("走共用辅助页且带 capture=true",
                context.lastStartedIntent != null
                        && context.lastStartedIntent.getBooleanExtra(
                                FileChooserActivity.EXTRA_CAPTURE, false),
                context.lastStartedIntent == null ? null : "no intent");
        check("回调仍 pending（等辅助页结果）", FileChooserBridge.hasPending(), true);
        FileChooserBridge.cancelAll("app-owner");
        check("取消后回调恰好一次", callback.calls.get() == 1 && callback.last == null,
                callback.calls.get());
    }

    private static void routesGalleryWithoutCaptureFlag() {
        FileChooserBridge.resetForTest();
        Context context = new Context();
        WebView webView = new WebView(context);
        AppFileChooserClient client = new AppFileChooserClient(new Bridge(), "app-owner");
        RecordingCallback callback = new RecordingCallback();
        boolean handled = client.onShowFileChooser(webView, callback, new CaptureParams(false));
        check("图库请求被接管", handled, handled);
        check("图库请求不带 capture 标志",
                context.lastStartedIntent != null
                        && !context.lastStartedIntent.getBooleanExtra(
                                FileChooserActivity.EXTRA_CAPTURE, false),
                context.lastStartedIntent == null ? null : "no intent");
        check("图库请求 owner 记录正确",
                "app-owner".equals(FileChooserBridge.pendingOwnerForTest()),
                FileChooserBridge.pendingOwnerForTest());
        FileChooserBridge.cancelAll("app-owner");
    }

    private static void fallsBackToParentWhenHelperCannotStart() {
        FileChooserBridge.resetForTest();
        ThrowingContext context = new ThrowingContext();
        WebView webView = new WebView(context);
        AppFileChooserClient client = new AppFileChooserClient(new Bridge(), "app-owner");
        RecordingCallback callback = new RecordingCallback();
        boolean handled = client.onShowFileChooser(webView, callback, new CaptureParams(true));
        check("辅助页起不来时回退父类并接管",
                handled && ((BridgeWebChromeClient) client).superFileChooserCalls == 1,
                ((BridgeWebChromeClient) client).superFileChooserCalls);
        check("回退路径不重复调用回调", callback.calls.get() == 0, callback.calls.get());
        check("回退路径不留 pending", !FileChooserBridge.hasPending(), true);
    }

    private static void nullCallbackIsNotHandled() {
        AppFileChooserClient client = new AppFileChooserClient(new Bridge(), "app-owner");
        boolean handled = client.onShowFileChooser(new WebView(new Context()), null,
                new CaptureParams(true));
        check("空回调不接管（WebChromeClient 契约）", !handled, handled);
    }
}
