package com.sakura.remote;

import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.getcapacitor.Bridge;
import com.getcapacitor.BridgeWebChromeClient;

/**
 * App（MainActivity）里 WebView 的文件选择/拍照接线。
 *
 * <p>为什么需要：Capacitor 自带的 {@link BridgeWebChromeClient#onShowFileChooser}
 * 会直接 {@code resolveActivity} 后开相机，并把结果原样交给网页 ——
 * 没有附件预算校验、没有 EXIF 方向处理；Android 11+ 的包可见性限制还可能
 * 让它查不到相机而回退图库，违背「直接拍照」入口。
 *
 * <p>这里**只覆写 onShowFileChooser**，把它转给与悬浮窗共用的
 * {@link OverlayFileChooser} → {@link FileChooserActivity}：同一条有界验证、
 * 缩图、EXIF 规范方向、精确一次回调的通道。父类的其它行为（JS 对话框、
 * 权限请求、地理位置、console 等）全部保留，不覆盖丢弃已有桥。
 *
 * <p>接管失败时父类实现仍会被调用，回调不会丢、也不会被调用两次：
 * {@link OverlayFileChooser#request} 失败路径走的是
 * {@link FileChooserBridge#abandon}（移除登记但不调用回调）。
 */
public class AppFileChooserClient extends BridgeWebChromeClient {

    private final String owner;

    public AppFileChooserClient(Bridge bridge, String owner) {
        super(bridge);
        this.owner = owner == null ? "" : owner;
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        if (filePathCallback == null) {
            return false;
        }
        try {
            if (OverlayFileChooser.request(webView != null ? webView.getContext() : null,
                    owner, filePathCallback, fileChooserParams)) {
                return true;
            }
        } catch (Throwable error) {
            // 接线异常：回退父类，保证回调恰好一次
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }
}
