package com.sakura.remote;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 从电脑端插件拉取立绘，供悬浮窗显示。
 *
 * 插件提供两样东西：
 *   GET /api/state   -> { characterId, displayName, portraits:[{key,url}], ... }
 *   GET {url}        -> PNG 字节（url 里已经自带 token）
 *
 * 这里用最朴素的两步：先读 state 拿默认立绘地址，再下载图片。
 */
public final class PortraitFetcher {

    public interface Callback {
        void onPortrait(Bitmap bitmap, String characterName);

        void onError(String message);
    }

    /** 上一次成功下载的立绘缓存，服务重启时可以先顶上去，避免白屏。 */
    private static volatile String cachedDataUrl = "";

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sakura-portrait");
        thread.setDaemon(true);
        return thread;
    });

    public void shutdown() {
        executor.shutdownNow();
    }

    /** 后台线程拉取；回调仍在后台线程，调用方自己切回主线程。 */
    public void fetch(final String baseUrl, final String token, final Callback callback) {
        executor.execute(() -> {
            try {
                String stateBody = httpGet(baseUrl + "/api/state?token=" + encode(token), 15000);
                JSONObject state = new JSONObject(stateBody);
                String characterName = state.optString("displayName", "");
                String portraitUrl = defaultPortraitUrl(state);
                if (portraitUrl.isEmpty()) {
                    callback.onError("角色没有可用立绘");
                    return;
                }
                String absolute = absoluteUrl(baseUrl, portraitUrl);
                byte[] payload = httpGetBytes(absolute, 30000);
                Bitmap bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
                if (bitmap == null) {
                    callback.onError("立绘解码失败");
                    return;
                }
                cachedDataUrl = "data:image/png;base64," + Base64.encodeToString(payload, Base64.NO_WRAP);
                callback.onPortrait(bitmap, characterName);
            } catch (Exception error) {
                callback.onError(error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        });
    }

    public static String cachedDataUrl() {
        return cachedDataUrl;
    }

    private static String defaultPortraitUrl(JSONObject state) {
        JSONArray portraits = state.optJSONArray("portraits");
        if (portraits == null || portraits.length() == 0) {
            return "";
        }
        String fallback = "";
        for (int index = 0; index < portraits.length(); index++) {
            JSONObject item = portraits.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String url = item.optString("url", "");
            if (url.isEmpty()) {
                continue;
            }
            if ("__default__".equals(item.optString("key", ""))) {
                return url;
            }
            if (fallback.isEmpty()) {
                fallback = url;
            }
        }
        return fallback;
    }

    private static String absoluteUrl(String baseUrl, String path) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return path;
        }
        return baseUrl + path;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Exception error) {
            return value;
        }
    }

    private static String httpGet(String url, int timeoutMs) throws Exception {
        byte[] body = httpGetBytes(url, timeoutMs);
        return new String(body, "UTF-8");
    }

    private static byte[] httpGetBytes(String url, int timeoutMs) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setRequestMethod("GET");
        connection.setUseCaches(false);
        try {
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IllegalStateException("HTTP " + code + " for " + url);
            }
            try (InputStream stream = connection.getInputStream();
                 ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
                byte[] chunk = new byte[16384];
                int read;
                while ((read = stream.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }
        } finally {
            connection.disconnect();
        }
    }
}
