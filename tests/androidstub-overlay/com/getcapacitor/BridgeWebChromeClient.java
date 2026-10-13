package com.getcapacitor;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

/**
 * Capacitor WebChromeClient 的最小替身（测试专用）。
 *
 * <p>保留「父类会自己接管 onShowFileChooser」这一关键行为，
 * 便于断言 {@code AppFileChooserClient} 只在接管失败时才回退父类。
 */
public class BridgeWebChromeClient extends WebChromeClient {

    protected final Bridge bridge;
    /** 父类 onShowFileChooser 被调用的次数。 */
    public int superFileChooserCalls;
    /** 父类是否已接管（真实实现返回 true）。 */
    public boolean superHandles = true;

    public BridgeWebChromeClient(Bridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        superFileChooserCalls += 1;
        return superHandles;
    }
}
