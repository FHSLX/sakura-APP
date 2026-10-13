package com.sakura.remote;

import android.content.Context;

/**
 * 连接配置的唯一接线点。
 *
 * <p>所有「连接目标」的变更都从这里过一遍：
 * <ol>
 *   <li>规范化为完整 origin + token 后<b>原子</b>写入 {@link PetConfig}；</li>
 *   <li>只有有效配置<b>真的变了</b>才请求运行中的立绘重载 —— 相同地址重复上报
 *       不会触发无限重载；</li>
 *   <li>桌面立绘 / 小球页面（{@code overlayPage = true}）的上报永不覆盖主 App
 *       已经配好的地址，避免「旧立绘把新配置写回旧地址」。</li>
 * </ol>
 *
 * <p>纯接线逻辑（只依赖 {@link PetConfig} 与一个效果出口），因此可以在普通 JVM 上
 * 用真实 {@link PetConfig} + 内存 SharedPreferences 做行为测试。
 */
public final class ConnectionSync {

    /** 立绘侧效果出口：由宿主（MainActivity）实现真正的 Service/Broadcast 操作。 */
    public interface OverlayPort {
        boolean isRunning();

        /** 请求运行中的立绘重载到给定聊天页地址（只在配置真的变化时被调用）。 */
        void reload(String chatUrl);
    }

    private final Context context;
    private final OverlayPort overlay;

    /** 最近一次主 App 远程页上报的目标，仅用于「配置是否一致」的去敏诊断。 */
    private volatile ConnectionProfile lastReported;

    public ConnectionSync(Context context, OverlayPort overlay) {
        this.context = context;
        this.overlay = overlay;
    }

    /**
     * 用户在连接设置页明确选择目标。
     *
     * @return true 表示配置确实变了（调用方可用于 UI 提示）
     */
    public boolean select(String server, String token) {
        boolean changed = PetConfig.applySelection(context, server, token);
        if (changed) {
            reloadRunningOverlay();
        }
        return changed;
    }

    /**
     * 远程页成功加载后回报自己的地址。
     *
     * @param overlayPage true = 来自立绘/小球页面
     * @return true 表示原生配置确实被这次上报改写
     */
    public boolean report(String url, boolean overlayPage) {
        if (!overlayPage && url != null && !url.isEmpty()) {
            ConnectionProfile parsed = ConnectionProfile.fromRemoteUrl(url);
            if (parsed.hasOrigin()) {
                // 只记去敏 profile，不记 URL/token 原文
                lastReported = parsed;
            }
        }
        boolean changed = PetConfig.applyPageReport(context, url, overlayPage);
        if (changed) {
            reloadRunningOverlay();
        }
        return changed;
    }

    /** 立绘启动/重载使用的地址；所有启动路径唯一来源，避免某条路径读旧值。 */
    public String overlayUrl() {
        return PetConfig.chatUrl(context);
    }

    /** 运行中的立绘按当前有效配置重载；没有运行或未配置时什么都不做。 */
    public void reloadRunningOverlay() {
        if (overlay == null || !overlay.isRunning()) {
            return;
        }
        String chat = overlayUrl();
        if (chat.isEmpty()) {
            return;
        }
        overlay.reload(chat);
    }

    /**
     * 去敏连接诊断（JSON）。
     *
     * <p>只输出 scheme / 主机类别 / 端口类别 / token 是否存在 / 配置是否与最近一次
     * 远程页上报一致；不含主机名、端口号、完整 URL、token 或任何用户内容。
     */
    public String diagnostics() {
        ConnectionProfile current = PetConfig.currentProfile(context);
        ConnectionProfile reported = lastReported;
        boolean consistent = reported == null ? current.isConfigured() : current.sameAs(reported);
        return current.categoriesJson(current.isConfigured(), consistent);
    }
}
