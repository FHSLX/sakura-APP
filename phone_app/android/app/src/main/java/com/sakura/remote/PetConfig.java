package com.sakura.remote;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 手机端和悬浮窗服务共用的配置。
 *
 * 关键点：网页（WebView 里的 localStorage）和原生悬浮窗通过同一个 key 共享
 * 「角色缩放」。网页改完会广播给服务，服务实时生效；服务被捏合改完也会写回来，
 * 网页下次读取时拿到新值。两边都写 SharedPreferences，避免状态分裂。
 */
public final class PetConfig {

    /** 网页和原生都用这个文件名/key，保证两边读的是同一份。 */
    public static final String PREFS = "sakura_pet";

    public static final String KEY_SERVER = "server";
    public static final String KEY_TOKEN = "token";
    /** 角色缩放倍率，1.0 = 屏幕宽度的 100%。 */
    public static final String KEY_SCALE = "scale";
    /** 悬浮窗左上角位置（像素）；-1 表示还没定过，由服务自己选默认位置。 */
    public static final String KEY_POS_X = "pos_x";
    public static final String KEY_POS_Y = "pos_y";
    /**
     * 桌面立绘「应该」处于开启状态。
     *
     * 用来记住用户的意图：App 启动时据此自动拉起悬浮窗，
     * 用户在通知里点「关闭」时清掉，下次启动就不会再自动开。
     */
    public static final String KEY_OVERLAY_ENABLED = "overlay_enabled";
    /** 悬浮窗宽度（不超过屏幕宽度的比例，0.5–0.98）。 */
    public static final String KEY_WINDOW_WIDTH = "window_width_ratio";
    /** 悬浮窗高度（相对宽度的比例）。 */
    public static final String KEY_WINDOW_HEIGHT = "window_height_ratio";

    public static final float MIN_WINDOW_WIDTH_RATIO = 0.45f;
    public static final float MAX_WINDOW_WIDTH_RATIO = 0.98f;
    public static final float DEFAULT_WINDOW_WIDTH_RATIO = 0.82f;
    public static final float MIN_WINDOW_HEIGHT_RATIO = 0.5f;
    public static final float MAX_WINDOW_HEIGHT_RATIO = 2.4f;
    public static final float DEFAULT_WINDOW_HEIGHT_RATIO = 1.05f;

    public static final float MIN_SCALE = 0.25f;
    public static final float MAX_SCALE = 2.0f;
    public static final float DEFAULT_SCALE = 0.75f;

    private PetConfig() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String server(Context context) {
        return prefs(context).getString(KEY_SERVER, "");
    }

    public static String token(Context context) {
        return prefs(context).getString(KEY_TOKEN, "");
    }

    /** 只接受 http/https 的 host:port，其余一律当作未配置。 */
    public static String baseUrl(Context context) {
        String server = server(context);
        if (server == null || server.isEmpty()) {
            return "";
        }
        String value = server.trim();
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return trimTrailingSlash(value);
        }
        return "http://" + trimTrailingSlash(value);
    }

    private static String trimTrailingSlash(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    public static boolean isConfigured(Context context) {
        return !baseUrl(context).isEmpty() && !token(context).isEmpty();
    }

    /** 聊天页地址；未配置时返回空串。 */
    public static String chatUrl(Context context) {
        String base = baseUrl(context);
        String token = token(context);
        if (base.isEmpty()) {
            return "";
        }
        if (token.isEmpty()) {
            return base + "/";
        }
        try {
            return base + "/?token=" + java.net.URLEncoder.encode(token, "UTF-8");
        } catch (Exception error) {
            return base + "/";
        }
    }

    public static float scale(Context context) {
        float value = prefs(context).getFloat(KEY_SCALE, DEFAULT_SCALE);
        return clampScale(value);
    }

    public static void setScale(Context context, float value) {
        prefs(context).edit().putFloat(KEY_SCALE, clampScale(value)).apply();
    }

    public static float clampScale(float value) {
        if (Float.isNaN(value) || value <= 0f) {
            return DEFAULT_SCALE;
        }
        if (value < MIN_SCALE) {
            return MIN_SCALE;
        }
        if (value > MAX_SCALE) {
            return MAX_SCALE;
        }
        return value;
    }

    public static int posX(Context context) {
        return prefs(context).getInt(KEY_POS_X, -1);
    }

    public static int posY(Context context) {
        return prefs(context).getInt(KEY_POS_Y, -1);
    }

    public static void setPosition(Context context, int x, int y) {
        prefs(context).edit().putInt(KEY_POS_X, x).putInt(KEY_POS_Y, y).apply();
    }

    /** 用户是否希望桌面立绘常驻（App 启动时据此自动拉起悬浮窗）。 */
    public static boolean overlayEnabled(Context context) {
        return prefs(context).getBoolean(KEY_OVERLAY_ENABLED, false);
    }

    public static void setOverlayEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_OVERLAY_ENABLED, enabled).apply();
    }

    /** 悬浮窗宽度占屏幕的比例（用户可拖动右下角调整）。 */
    public static float windowWidthRatio(Context context) {
        float value = prefs(context).getFloat(KEY_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH_RATIO);
        if (Float.isNaN(value)) {
            return DEFAULT_WINDOW_WIDTH_RATIO;
        }
        return Math.min(MAX_WINDOW_WIDTH_RATIO, Math.max(MIN_WINDOW_WIDTH_RATIO, value));
    }

    /** 悬浮窗高度相对宽度的比例。 */
    public static float windowHeightRatio(Context context) {
        float value = prefs(context).getFloat(KEY_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT_RATIO);
        if (Float.isNaN(value)) {
            return DEFAULT_WINDOW_HEIGHT_RATIO;
        }
        return Math.min(MAX_WINDOW_HEIGHT_RATIO, Math.max(MIN_WINDOW_HEIGHT_RATIO, value));
    }

    public static void setWindowSize(Context context, float widthRatio, float heightRatio) {
        prefs(context).edit()
                .putFloat(KEY_WINDOW_WIDTH, Math.min(MAX_WINDOW_WIDTH_RATIO,
                        Math.max(MIN_WINDOW_WIDTH_RATIO, widthRatio)))
                .putFloat(KEY_WINDOW_HEIGHT, Math.min(MAX_WINDOW_HEIGHT_RATIO,
                        Math.max(MIN_WINDOW_HEIGHT_RATIO, heightRatio)))
                .apply();
    }

    /**
     * 网页算好的窗口尺寸（CSS 像素）。
     *
     * 为什么让网页算：窗口尺寸 = 立绘 + 对话框 + 输入栏，而立绘尺寸取决于
     * 立绘原始宽高比和缩放倍率 —— 这些只有网页知道。
     *
     * 为什么要存下来：必须在**加载页面前**就按这个尺寸建窗口，
     * 否则 WebView 会按旧尺寸算布局视口，而它改窗口后不会更新视口
     *（这是整套悬浮窗布局问题的根源）。存下来让原生在 attach 时直接用。
     */
    public static final String KEY_WANT_W = "want_window_w";
    public static final String KEY_WANT_H = "want_window_h";

    public static int wantWindowWidth(Context context) {
        return prefs(context).getInt(KEY_WANT_W, 0);
    }

    public static int wantWindowHeight(Context context) {
        return prefs(context).getInt(KEY_WANT_H, 0);
    }

    public static void setWantWindowSize(Context context, int widthPx, int heightPx) {
        prefs(context).edit()
                .putInt(KEY_WANT_W, Math.max(120, widthPx))
                .putInt(KEY_WANT_H, Math.max(120, heightPx))
                .apply();
    }

    /** 网页保存服务器配置时同步进来，悬浮窗才知道该连哪儿。 */
    public static void saveServer(Context context, String server, String token) {
        prefs(context).edit()
                .putString(KEY_SERVER, server == null ? "" : server.trim())
                .putString(KEY_TOKEN, token == null ? "" : token.trim())
                .apply();
    }

    /**
     * 从远程页地址里反推配置，作为兜底。
     *
     * 连接设置页和远程页是不同源（https://localhost vs http://IP:port），
     * localStorage 不互通；用户在远程页操作时原生侧可能还是空的。
     * 只有「完全没配过」时才写入，避免覆盖用户在设置页认真填的地址。
     */
    public static void rememberFromUrl(Context context, String url) {
        if (url == null || url.isEmpty()) {
            return;
        }
        String value = url.trim();
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            return;
        }
        try {
            java.net.URL parsed = new java.net.URL(value);
            String host = parsed.getHost();
            int port = parsed.getPort();
            if (host == null || host.isEmpty() || port <= 0) {
                return;
            }
            String server = host + ":" + port;
            String token = "";
            String query = parsed.getQuery();
            if (query != null) {
                for (String pair : query.split("&")) {
                    int equal = pair.indexOf('=');
                    if (equal > 0 && "token".equals(pair.substring(0, equal))) {
                        token = java.net.URLDecoder.decode(pair.substring(equal + 1), "UTF-8");
                        break;
                    }
                }
            }
            SharedPreferences prefs = prefs(context);
            String existingServer = prefs.getString(KEY_SERVER, "");
            if (existingServer != null && !existingServer.isEmpty()) {
                // 已经配过：只补 token（用户可能换了 token 但从远程页进来的）
                if (token != null && !token.isEmpty()) {
                    prefs.edit().putString(KEY_TOKEN, token).apply();
                }
                return;
            }
            prefs.edit()
                    .putString(KEY_SERVER, server)
                    .putString(KEY_TOKEN, token == null ? "" : token)
                    .apply();
        } catch (Exception ignored) {
            // 地址解析失败就当作没配过
        }
    }
}
