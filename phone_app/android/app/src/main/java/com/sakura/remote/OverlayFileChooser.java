package com.sakura.remote;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;

/**
 * 悬浮窗 WebView 文件选择的原生接线。
 *
 * <p>为什么需要：{@link PetWindow} 的 {@code OverlayChromeClient} 只实现了
 * console 回调，没有 {@code onShowFileChooser}。默认 WebView 会直接取消文件选择，
 * 所以悬浮窗里的「选择图片」永远点不动。
 *
 * <p>这里用公开的 {@link WebChromeClient#onShowFileChooser} 把回调登记到
 * {@link FileChooserBridge}，再启动一个**不导出**的辅助 Activity
 * {@link FileChooserActivity} 调系统图片选择器；结果通过 Uri 回调交还原 WebView。
 * 悬浮窗是 Service 窗口，拿不到 Activity 结果，所以必须用这个安全通道。
 *
 * <p>WebChromeClient 契约：只有返回 true 才表示「已接管并会回调」。因此
 * 启动失败时移除登记但**不调用**回调，并返回 false，交给 WebView 自己取消，
 * 保证回调恰好一次。
 */
final class OverlayFileChooser {

    private OverlayFileChooser() {
    }

    /**
     * 处理一次文件选择请求。
     *
     * @param owner 窗口实例标识：销毁时只取消这个 owner 的待处理选择
     * @return true 表示已接管（调用方须返回 true 给 WebView）
     */
    static boolean request(Context context, String owner, ValueCallback<Uri[]> callback,
                           WebChromeClient.FileChooserParams params) {
        if (context == null || callback == null) {
            return false;
        }
        String token = FileChooserBridge.begin(owner, callback);
        Intent intent = new Intent(context, FileChooserActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        intent.putExtra(FileChooserActivity.EXTRA_TOKEN, token);
        intent.putExtra(FileChooserActivity.EXTRA_OWNER, owner == null ? "" : owner);
        if (params != null) {
            intent.putExtra(FileChooserActivity.EXTRA_ACCEPT, params.getAcceptTypes());
            intent.putExtra(FileChooserActivity.EXTRA_MULTIPLE,
                    params.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE);
            /*
             * 「拍照」入口（网页 <input capture="environment">）必须显式转发。
             *
             * 旧实现忽略了这个标志，所有请求都当图库处理 —— 用户点「拍照」
             * 结果弹出的是图库。带上 isCaptureEnabled() 后，辅助 Activity
             * 才会走公开的 ACTION_IMAGE_CAPTURE + EXTRA_OUTPUT 路径。
             */
            intent.putExtra(FileChooserActivity.EXTRA_CAPTURE,
                    params.isCaptureEnabled());
        }
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception error) {
            // 起不来：移除登记、不调用回调，返回 false 让 WebView 自己取消
            FileChooserBridge.abandon(token);
            return false;
        }
    }
}
