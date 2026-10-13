package com.sakura.remote;

import android.net.Uri;
import android.webkit.ValueCallback;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 悬浮窗 WebView 文件选择的一次性回调登记表。
 *
 * <p>为什么单独一层：选择结果由独立的辅助 Activity 回调，而 WebView 的
 * {@link ValueCallback} 必须**恰好被调用一次**（调用 null 表示取消），否则
 * 网页的文件输入会永久 pending，之后再也点不动「选择图片」。
 *
 * <p>规则：
 *   - 同一时刻只允许一个待处理选择；新的请求会把旧的按「取消」结束（不泄漏 pending）；
 *   - deliver/cancel 按 token 匹配，调用一次后立即移除；
 *   - 每次登记记录 **owner**（具体窗口实例）：窗口销毁只取消**自己**的请求，
 *     旧窗口迟到的 destroy 不会取消新窗口刚登记的请求；
 *   - {@link #abandon(String)} 用于「接管失败、由 WebView 自己按取消处理」：
 *     移除登记但**不调用**回调，避免重复调用违反恰好一次契约。
 */
final class FileChooserBridge {

    private static final AtomicLong SEQ = new AtomicLong(0);

    private static String pendingToken = "";
    private static String pendingOwner = "";
    private static ValueCallback<Uri[]> pendingCallback;

    private FileChooserBridge() {
    }

    /** WebView 请求选择文件；返回本次 token（交给辅助 Activity 回传结果）。 */
    static synchronized String begin(String owner, ValueCallback<Uri[]> callback) {
        // 上一次还没结束：按取消收口，绝不留下第二个 pending
        deliverLocked(null);
        pendingToken = "fc-" + SEQ.incrementAndGet() + "-" + System.nanoTime();
        pendingOwner = owner == null ? "" : owner;
        pendingCallback = callback;
        return pendingToken;
    }

    /** 辅助 Activity 拿到结果：成功传 Uri[]，取消传 null。只生效一次。 */
    static synchronized boolean deliver(String token, Uri[] uris) {
        if (token == null || token.isEmpty() || !token.equals(pendingToken)) {
            return false;
        }
        return deliverLocked(uris);
    }

    static synchronized void cancel(String token) {
        deliver(token, null);
    }

    /** 只取消**该 owner** 的待处理选择；别的窗口的请求原样保留。 */
    static synchronized void cancelAll(String owner) {
        if (pendingCallback == null) {
            return;
        }
        String wanted = owner == null ? "" : owner;
        if (!wanted.equals(pendingOwner)) {
            return;
        }
        deliverLocked(null);
    }

    /** 接管失败：移除登记但不调用回调（WebView 返回 false 后自会取消）。 */
    static synchronized void abandon(String token) {
        if (pendingCallback == null || token == null || !token.equals(pendingToken)) {
            return;
        }
        pendingToken = "";
        pendingOwner = "";
        pendingCallback = null;
    }

    static synchronized boolean hasPending() {
        return pendingCallback != null;
    }

    static synchronized String pendingTokenForTest() {
        return pendingToken;
    }

    static synchronized String pendingOwnerForTest() {
        return pendingOwner;
    }

    static synchronized void resetForTest() {
        pendingToken = "";
        pendingOwner = "";
        pendingCallback = null;
    }

    private static boolean deliverLocked(Uri[] uris) {
        ValueCallback<Uri[]> callback = pendingCallback;
        pendingToken = "";
        pendingOwner = "";
        pendingCallback = null;
        if (callback == null) {
            return false;
        }
        try {
            callback.onReceiveValue(uris);
        } catch (Throwable ignored) {
            // WebView 可能已销毁：回调异常不能影响原生状态机
        }
        return true;
    }
}
