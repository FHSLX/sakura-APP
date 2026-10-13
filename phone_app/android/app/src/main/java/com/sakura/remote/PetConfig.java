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

    /**
     * 「透明处点击穿透」的用户意图（App / 悬浮窗 / 小球共用同一份）。
     *
     * <p>用一对 key 表达三态：SET 未置位 = 没有偏好（由调用方取默认），
     * SET 置位 = 用户明确选过，VALUE 是选择。这样「默认」与「用户选过默认值」
     * 不会混在一起，重启/换页面都保持。
     */
    public static final String KEY_PASS_THROUGH = "pass_through";
    public static final String KEY_PASS_THROUGH_SET = "pass_through_set";
    /** 立绘上下浮动（呼吸动画）的用户意图。 */
    public static final String KEY_PORTRAIT_FLOAT = "portrait_float";
    public static final String KEY_PORTRAIT_FLOAT_SET = "portrait_float_set";

    /** 没有偏好时穿透默认开启（API33+ 的桌面立绘历史行为）。 */
    public static final boolean DEFAULT_PASS_THROUGH = true;
    /** 没有偏好时上下浮动默认关闭。 */
    public static final boolean DEFAULT_PORTRAIT_FLOAT = false;

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

    /**
     * 当前持久配置规范化后的连接目标；未配置/无效时返回空 profile。
     *
     * <p>历史数据里 server 可能是「host:port」（没有 scheme），也可能是完整 origin，
     * 统一交给 {@link ConnectionProfile} 归一，避免出现「两份地址」。
     */
    public static ConnectionProfile currentProfile(Context context) {
        return ConnectionProfile.fromUserInput(server(context), token(context));
    }

    /** 规范化 origin（如 http://192.168.1.5:8770）；未配置或无效时返回空串。 */
    public static String baseUrl(Context context) {
        return currentProfile(context).origin;
    }

    public static boolean isConfigured(Context context) {
        return currentProfile(context).isConfigured();
    }

    /** 聊天页地址；未配置时返回空串。 */
    public static String chatUrl(Context context) {
        return currentProfile(context).chatUrl();
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

    // ---- 用户意图偏好（App / 浮窗 / 小球共用）----------------------

    /** 用户是否明确选过「透明处穿透」。 */
    public static boolean hasPassThroughPreference(Context context) {
        return prefs(context).getBoolean(KEY_PASS_THROUGH_SET, false);
    }

    /** 是否开启穿透；没有用户偏好时取 {@link #DEFAULT_PASS_THROUGH}。 */
    public static boolean passThroughEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PASS_THROUGH, DEFAULT_PASS_THROUGH);
    }

    public static void setPassThroughEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_PASS_THROUGH, enabled)
                .putBoolean(KEY_PASS_THROUGH_SET, true)
                .apply();
    }

    /** 用户是否明确选过「立绘上下浮动」。 */
    public static boolean hasPortraitFloatPreference(Context context) {
        return prefs(context).getBoolean(KEY_PORTRAIT_FLOAT_SET, false);
    }

    /** 是否上下浮动；没有用户偏好时**默认关闭**。 */
    public static boolean portraitFloatEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PORTRAIT_FLOAT, DEFAULT_PORTRAIT_FLOAT);
    }

    public static void setPortraitFloatEnabled(Context context, boolean enabled) {
        prefs(context).edit()
                .putBoolean(KEY_PORTRAIT_FLOAT, enabled)
                .putBoolean(KEY_PORTRAIT_FLOAT_SET, true)
                .apply();
    }

    /**
     * 应用网页/App 提交的偏好 JSON；只处理出现的 key。
     *
     * <p><b>可确认的写入</b>：用 {@link SharedPreferences.Editor#commit()} 而不是
     * {@code apply()}。{@code apply()} 只保证内存即刻更新（随后 get 读到的也是内存值），
     * 无法证明磁盘写入成功；这里以 {@code commit()} 的返回值为准。
     * 提交失败时**还原旧偏好**并返回稳定错误码 {@code commit-failed}，
     * 绝不留下半写状态、也不覆盖用户原有选择。
     */
    public static String applyDisplayPreferences(Context context, String json) {
        Boolean wantFloat = DisplayPreferences.bool(json, DisplayPreferences.KEY_FLOAT);
        Boolean wantPass = DisplayPreferences.bool(json, DisplayPreferences.KEY_PASS_THROUGH);
        if (wantFloat == null && wantPass == null) {
            return DisplayPreferences.failure("invalid");
        }
        // 先记下旧值：commit 失败时要原样还原
        boolean oldFloatHas = hasPortraitFloatPreference(context);
        boolean oldFloat = portraitFloatEnabled(context);
        boolean oldPassHas = hasPassThroughPreference(context);
        boolean oldPass = passThroughEnabled(context);

        SharedPreferences.Editor editor = prefs(context).edit();
        if (wantFloat != null) {
            editor.putBoolean(KEY_PORTRAIT_FLOAT, wantFloat)
                    .putBoolean(KEY_PORTRAIT_FLOAT_SET, true);
        }
        if (wantPass != null) {
            editor.putBoolean(KEY_PASS_THROUGH, wantPass)
                    .putBoolean(KEY_PASS_THROUGH_SET, true);
        }
        boolean committed = editor.commit();
        if (!committed) {
            restoreDisplayPreferences(context, oldFloatHas, oldFloat, oldPassHas, oldPass);
            return DisplayPreferences.failure("commit-failed");
        }
        return DisplayPreferences.applied(
                hasPortraitFloatPreference(context), portraitFloatEnabled(context),
                hasPassThroughPreference(context), passThroughEnabled(context));
    }

    /** commit 失败后把偏好还原到写入前的状态（best effort，同样走 commit）。 */
    private static void restoreDisplayPreferences(Context context, boolean floatHas, boolean floatValue,
                                                  boolean passHas, boolean passValue) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (floatHas) {
            editor.putBoolean(KEY_PORTRAIT_FLOAT, floatValue)
                    .putBoolean(KEY_PORTRAIT_FLOAT_SET, true);
        } else {
            editor.remove(KEY_PORTRAIT_FLOAT).remove(KEY_PORTRAIT_FLOAT_SET);
        }
        if (passHas) {
            editor.putBoolean(KEY_PASS_THROUGH, passValue)
                    .putBoolean(KEY_PASS_THROUGH_SET, true);
        } else {
            editor.remove(KEY_PASS_THROUGH).remove(KEY_PASS_THROUGH_SET);
        }
        editor.commit();
    }

    /** 偏好快照 JSON，供网页渲染开关。 */
    public static String displayPreferences(Context context, boolean passThroughSupported) {
        return DisplayPreferences.snapshot(
                hasPortraitFloatPreference(context), portraitFloatEnabled(context),
                hasPassThroughPreference(context), passThroughEnabled(context),
                passThroughSupported);
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
    /** 尺寸契约版本：旧版本可能存了越界值，需要显式迁移而不是让用户清数据。 */
    public static final String KEY_WANT_VERSION = "want_window_v";
    public static final int WANT_WINDOW_VERSION = 2;
    /** 网页报来的 CSS 像素尺寸的合法范围。 */
    public static final int WANT_MIN_PX = 160;
    public static final int WANT_MAX_PX = 4096;

    public static int wantWindowWidth(Context context) {
        migrateWantWindowSize(context);
        return clampWant(prefs(context).getInt(KEY_WANT_W, 0));
    }

    public static int wantWindowHeight(Context context) {
        migrateWantWindowSize(context);
        return clampWant(prefs(context).getInt(KEY_WANT_H, 0));
    }

    public static void setWantWindowSize(Context context, int widthPx, int heightPx) {
        prefs(context).edit()
                .putInt(KEY_WANT_W, clampWant(widthPx))
                .putInt(KEY_WANT_H, clampWant(heightPx))
                .putInt(KEY_WANT_VERSION, WANT_WINDOW_VERSION)
                .apply();
    }

    /** 0 表示「还没有有效值」；其余一律夹到 [WANT_MIN_PX, WANT_MAX_PX]。 */
    private static int clampWant(int value) {
        if (value <= 0) {
            return 0;
        }
        return Math.min(WANT_MAX_PX, Math.max(WANT_MIN_PX, value));
    }

    /**
     * 显式版本迁移。
     *
     * 旧实现把网页算出来的窗口尺寸原样落盘。一旦网页侧出现过计算循环
     *（窗口越大→视口越旧→算出更大的窗口），就会把越界尺寸写进配置，
     * 之后每次开桌宠都按这个坏值建窗口。这里夹到合法范围并记版本，
     * 而不是要求用户清除 App 数据。
     */
    private static void migrateWantWindowSize(Context context) {
        SharedPreferences prefs = prefs(context);
        int version = prefs.getInt(KEY_WANT_VERSION, 1);
        if (version >= WANT_WINDOW_VERSION) {
            return;
        }
        prefs.edit()
                .putInt(KEY_WANT_W, clampWant(prefs.getInt(KEY_WANT_W, 0)))
                .putInt(KEY_WANT_H, clampWant(prefs.getInt(KEY_WANT_H, 0)))
                .putInt(KEY_WANT_VERSION, WANT_WINDOW_VERSION)
                .apply();
    }

    /**
     * 用户明确选择连接目标：完整 origin + token **原子**写入。
     *
     * <p>返回 true 表示配置确实变了；调用方据此决定要不要重载运行中的立绘。
     * 无效输入（空主机、非 HTTP(S)、空 token）不写入，保留上一份可用配置。
     */
    public static boolean applySelection(Context context, String server, String token) {
        return writeIfChanged(context, ConnectionProfile.fromUserInput(server, token));
    }

    /** 兼容旧调用点：等价于用户在连接设置页的选择。 */
    public static void saveServer(Context context, String server, String token) {
        applySelection(context, server, token);
    }

    /**
     * 远程页成功加载后回报自己的地址，修复陈旧的 server。
     *
     * <p>排除本地 Capacitor 页面、空 token、非 HTTP(S)、无效主机。
     *
     * @param overlayPage true = 上报来自桌面立绘/小球页面（旧页面），
     *                    此时绝不覆盖主 App 已经配好的地址，
     *                    否则旧立绘会把主 App 的新配置写回旧地址。
     * @return true 表示配置确实变了
     */
    public static boolean applyPageReport(Context context, String url, boolean overlayPage) {
        ConnectionProfile reported = ConnectionProfile.fromRemoteUrl(url);
        if (!reported.hasOrigin() || !reported.hasToken()) {
            return false;
        }
        if (overlayPage && currentProfile(context).isConfigured()) {
            return false;
        }
        return writeIfChanged(context, reported);
    }

    /** 兼容旧调用点：等价于主 App 远程页面的成功上报。 */
    public static void rememberFromUrl(Context context, String url) {
        applyPageReport(context, url, false);
    }

    /**
     * 立绘 / 小球页面发起的地址写入。
     *
     * <p>只在原生完全没配置时兜底填充；主 App 已配好时一律忽略，
     * 杜绝「旧立绘把主 App 新配置写回旧地址」。
     */
    public static boolean applyOverlaySelection(Context context, String server, String token) {
        if (currentProfile(context).isConfigured()) {
            return false;
        }
        return writeIfChanged(context, ConnectionProfile.fromUserInput(server, token));
    }

    /**
     * 原子写入 origin + token。
     *
     * <p>一次 editor 里同时落两个值，避免出现「新 host 配旧 token」的中间态；
     * 配置与现有值相同则完全不写，也不触发任何重载。
     */
    private static boolean writeIfChanged(Context context, ConnectionProfile target) {
        if (target == null || !target.isConfigured()) {
            return false;
        }
        if (target.sameAs(currentProfile(context))) {
            return false;
        }
        prefs(context).edit()
                .putString(KEY_SERVER, target.origin)
                .putString(KEY_TOKEN, target.token)
                .apply();
        return true;
    }
}
