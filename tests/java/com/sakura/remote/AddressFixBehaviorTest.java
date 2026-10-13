package com.sakura.remote;

import android.content.Context;

import java.util.Map;

/**
 * 「聊天与立绘连接地址分裂」补充修复的真实行为测试（普通 JVM，无需 Android/JUnit）。
 *
 * <p>编译的是**真实生产类**：{@link ConnectionProfile}、{@link PetConfig}、
 * {@link ConnectionSync}、{@link RemoteBridge}，Android 系统接口由
 * {@code tests/androidstub-overlay} 的最小替身提供（其中 SharedPreferences 是
 * 真实存值的内存实现，并记录每次落盘快照，用来证明原子写入）。
 *
 * <p>覆盖复审清单：
 * <ul>
 *   <li>原生已有不可解析旧主机 + 聊天页新 IP → 开启/重载立绘必须指向新 origin；</li>
 *   <li>token 与新 origin 同一次原子写入；</li>
 *   <li>HTTPS / 默认端口 / IPv6 的规范化与保留；</li>
 *   <li>无效地址、本地 Capacitor 页面、空 token、非 HTTP(S)、无效主机不覆盖；</li>
 *   <li>相同地址重复上报不触发重载；</li>
 *   <li>旧立绘页面不回写主配置。</li>
 * </ul>
 */
public final class AddressFixBehaviorTest {

    private static int failures = 0;

    public static void main(String[] args) {
        normalizeRemoteUrlBasics();
        normalizePortsAndIpv6();
        normalizeRejectsBadInput();
        legacyStoredConfigIsNormalizedWithoutSpuriousChange();
        userSelectionNormalizesAndPersistsAtomically();
        headlineStaleHostRepairedByChatPageReport();
        emptyTokenLocalPageAndInvalidReportsDoNotOverwrite();
        invalidSelectionKeepsPreviousConfig();
        overlayPageNeverWritesBack();
        repeatedIdenticalReportDoesNotReload();
        httpsAndIpv6SelectionPersist();
        diagnosticsAreSanitized();
        bridgeRoutesReportSource();
        System.out.println();
        if (failures > 0) {
            System.err.println(failures + " 项失败");
            System.exit(1);
        }
        System.out.println("PASS");
    }

    // ---- 规范化 ------------------------------------------------------

    private static void normalizeRemoteUrlBasics() {
        ConnectionProfile profile =
                ConnectionProfile.fromRemoteUrl("http://192.168.1.5:8770/?token=abc");
        check("远程页 origin 规范化", "http://192.168.1.5:8770".equals(profile.origin));
        check("远程页 token 解析", "abc".equals(profile.token));
        check("远程页 profile 可连接", profile.isConfigured());
        check("远程页 chatUrl 形态",
                "http://192.168.1.5:8770/?token=abc".equals(profile.chatUrl()));

        ConnectionProfile mode =
                ConnectionProfile.fromRemoteUrl("http://10.0.0.2:8770/?token=zz&mode=overlay");
        check("带 mode 参数仍解析 token", "zz".equals(mode.token));
        check("带 mode 参数 origin 不变", "http://10.0.0.2:8770".equals(mode.origin));

        ConnectionProfile noToken = ConnectionProfile.fromRemoteUrl("http://192.168.1.5:8770/");
        check("无 token 时 origin 有效", noToken.hasOrigin());
        check("无 token 时 token 为空", !noToken.hasToken());
    }

    private static void normalizePortsAndIpv6() {
        check("https 默认端口折叠",
                "https://host".equals(
                        ConnectionProfile.fromRemoteUrl("https://host/?token=t").origin));
        check("http 显式默认端口折叠",
                "http://host".equals(
                        ConnectionProfile.fromRemoteUrl("http://host:80/?token=t").origin));
        check("显式非默认端口保留",
                "http://host:8770".equals(
                        ConnectionProfile.fromRemoteUrl("http://host:8770/?token=t").origin));
        ConnectionProfile ipv6 =
                ConnectionProfile.fromRemoteUrl("http://[::1]:8770/?token=t");
        check("IPv6 主机保留括号", "http://[::1]:8770".equals(ipv6.origin));
        check("IPv6 类别为 ipv6", ipv6.categoriesJson(true, true).contains("\"hostKind\":\"ipv6\""));
        check("IPv6 默认端口折叠",
                "https://[2001:db8::1]".equals(
                        ConnectionProfile.fromRemoteUrl("https://[2001:db8::1]/?token=t").origin));
        check("IPv6 chatUrl 可用",
                "http://[::1]:8770/?token=t".equals(ipv6.chatUrl()));
    }

    private static void normalizeRejectsBadInput() {
        check("非 HTTP(S) 拒绝",
                !ConnectionProfile.fromRemoteUrl("ftp://host:21/?token=t").hasOrigin());
        check("本地 Capacitor 页面拒绝",
                !ConnectionProfile.fromRemoteUrl("https://localhost/?token=t").hasOrigin());
        check("空 token 的本地页拒绝",
                !ConnectionProfile.fromRemoteUrl("https://localhost/").hasOrigin());
        check("下划线非法主机拒绝",
                !ConnectionProfile.fromRemoteUrl("http://a_b:8770/?token=t").hasOrigin());
        check("空主机拒绝",
                !ConnectionProfile.fromRemoteUrl("http://:8770/?token=t").hasOrigin());
        check("越界端口拒绝",
                !ConnectionProfile.fromRemoteUrl("http://host:99999/?token=t").hasOrigin());
        check("端口 0 拒绝",
                !ConnectionProfile.fromRemoteUrl("http://host:0/?token=t").hasOrigin());
        check("userinfo 混淆主机拒绝",
                !ConnectionProfile.fromRemoteUrl("http://user:pass@host:8770/?token=t").hasOrigin());
        check("空地址拒绝", !ConnectionProfile.fromRemoteUrl("").hasOrigin());
        check("null 地址拒绝", !ConnectionProfile.fromRemoteUrl(null).hasOrigin());
    }

    // ---- PetConfig 真实持久化 ---------------------------------------

    private static void legacyStoredConfigIsNormalizedWithoutSpuriousChange() {
        Context context = new Context();
        // 模拟历史落盘：server 没有 scheme（旧版本格式）
        context.memoryPrefs(PetConfig.PREFS).edit()
                .putString("server", "192.168.1.7:8770")
                .putString("token", "t")
                .apply();
        check("旧格式 baseUrl 归一", "http://192.168.1.7:8770".equals(PetConfig.baseUrl(context)));
        check("旧格式 chatUrl 归一",
                "http://192.168.1.7:8770/?token=t".equals(PetConfig.chatUrl(context)));

        FakeOverlayPort overlay = new FakeOverlayPort();
        overlay.running = true;
        ConnectionSync sync = new ConnectionSync(context, overlay);
        // 归一后与上报相同的地址：不能算变化，也就不能触发重载
        check("归一后相同上报不算变化",
                !sync.report("http://192.168.1.7:8770/?token=t", false));
        check("归一后相同上报不重载", overlay.reloadCount == 0);
    }

    private static void userSelectionNormalizesAndPersistsAtomically() {
        Context context = new Context();
        ConnectionSync sync = new ConnectionSync(context, new FakeOverlayPort());
        check("用户选择写入成功", sync.select("192.168.1.7:8770", "t1"));
        check("选择后 origin 带 scheme", "http://192.168.1.7:8770".equals(PetConfig.baseUrl(context)));
        check("选择后 token 同步", "t1".equals(PetConfig.token(context)));

        android.content.SharedPreferences.Memory prefs = memoryPrefs(context);
        int before = prefs.applyCount;
        check("只换 token 也算变化", sync.select("192.168.1.7:8770", "t2"));
        check("一次变化只落盘一次", prefs.applyCount == before + 1);
        Map<String, Object> snapshot = lastSnapshot(prefs);
        check("落盘快照同时含新 origin 与新 token",
                "http://192.168.1.7:8770".equals(snapshot.get("server"))
                        && "t2".equals(snapshot.get("token")));
        check("相同选择不再落盘", !sync.select("192.168.1.7:8770", "t2"));
        check("相同选择后落盘次数不变", prefs.applyCount == before + 1);
    }

    // ---- 核心缺陷场景 -----------------------------------------------

    private static void headlineStaleHostRepairedByChatPageReport() {
        Context context = new Context();
        // 原生已有不可解析的旧主机 + 旧 token（真机 ERROR_HOST_LOOKUP 的来源）
        PetConfig.applySelection(context, "10.0.0.9:8770", "old-token");
        check("旧主机写入基线", "http://10.0.0.9:8770".equals(PetConfig.baseUrl(context)));

        FakeOverlayPort overlay = new FakeOverlayPort();
        overlay.running = true;
        ConnectionSync sync = new ConnectionSync(context, overlay);

        // 用户在连接页填了新 IP；聊天页加载成功后上报新地址
        boolean changed = sync.report("http://192.168.1.7:8770/?token=new-token", false);
        check("聊天页成功上报修复陈旧主机", changed);
        check("原生 origin 指向新 IP", "http://192.168.1.7:8770".equals(PetConfig.baseUrl(context)));
        check("token 同步为新 token", "new-token".equals(PetConfig.token(context)));
        check("开启/重载立绘用新 origin + 新 token",
                "http://192.168.1.7:8770/?token=new-token".equals(sync.overlayUrl()));
        check("运行中的立绘被要求重载一次", overlay.reloadCount == 1);
        check("重载地址就是新配置", sync.overlayUrl().equals(overlay.lastUrl));
        check("不再残留旧主机", !sync.overlayUrl().contains("10.0.0.9"));
        check("不再残留旧 token", !PetConfig.token(context).contains("old-token"));
    }

    private static void emptyTokenLocalPageAndInvalidReportsDoNotOverwrite() {
        Context context = new Context();
        PetConfig.applySelection(context, "192.168.1.7:8770", "keep");
        FakeOverlayPort overlay = new FakeOverlayPort();
        overlay.running = true;
        ConnectionSync sync = new ConnectionSync(context, overlay);

        check("空 token 上报不覆盖", !sync.report("http://192.168.1.9:8770/", false));
        check("本地 Capacitor 页面不覆盖", !sync.report("https://localhost/?token=leak", false));
        check("非 HTTP(S) 不覆盖", !sync.report("ftp://192.168.1.9:8770/?token=x", false));
        check("无效主机不覆盖", !sync.report("http://a_b:8770/?token=x", false));
        check("被拒上报后 origin 不变",
                "http://192.168.1.7:8770".equals(PetConfig.baseUrl(context)));
        check("被拒上报后 token 不变", "keep".equals(PetConfig.token(context)));
        check("被拒上报不触发重载", overlay.reloadCount == 0);
    }

    private static void invalidSelectionKeepsPreviousConfig() {
        Context context = new Context();
        ConnectionSync sync = new ConnectionSync(context, new FakeOverlayPort());
        sync.select("192.168.1.7:8770", "keep");
        check("空地址选择不写入", !sync.select("", "x"));
        check("空 token 选择不写入", !sync.select("192.168.1.7:8770", ""));
        check("非 HTTP(S) 选择不写入", !sync.select("ftp://host/", "x"));
        check("无效主机选择不写入", !sync.select("http://a b:8770", "x"));
        check("无效选择后仍是上一份可用配置",
                "http://192.168.1.7:8770/?token=keep".equals(sync.overlayUrl()));
    }

    private static void overlayPageNeverWritesBack() {
        Context context = new Context();
        FakeOverlayPort overlay = new FakeOverlayPort();
        overlay.running = true;
        ConnectionSync sync = new ConnectionSync(context, overlay);
        check("主 App 先配好新地址", sync.select("192.168.1.7:8770", "new-token"));
        int reloadsAfterSelect = overlay.reloadCount;

        // 旧立绘页面（旧主机 + 旧 token + mode=overlay）回报自己的地址
        boolean changed = sync.report(
                "http://10.0.0.9:8770/?token=old-token&mode=overlay", true);
        check("旧立绘上报不写回", !changed);
        check("旧立绘上报后 origin 仍是新主机",
                "http://192.168.1.7:8770".equals(PetConfig.baseUrl(context)));
        check("旧立绘上报后 token 仍是新 token", "new-token".equals(PetConfig.token(context)));
        check("旧立绘上报不触发重载", overlay.reloadCount == reloadsAfterSelect);

        // PetConfig 层同样拦得住（防御性：任何宿主实现都走同一规则）
        check("PetConfig 层拒绝立绘写回",
                !PetConfig.applyPageReport(
                        context, "http://10.0.0.9:8770/?token=old-token", true));
        check("PetConfig 层拒绝立绘 saveServer 写回",
                !PetConfig.applyOverlaySelection(context, "10.0.0.9:8770", "old-token"));
    }

    private static void repeatedIdenticalReportDoesNotReload() {
        Context context = new Context();
        FakeOverlayPort overlay = new FakeOverlayPort();
        overlay.running = true;
        ConnectionSync sync = new ConnectionSync(context, overlay);

        check("首次上报写入", sync.report("http://192.168.1.7:8770/?token=t", false));
        check("首次上报重载一次", overlay.reloadCount == 1);
        check("相同上报不再变化", !sync.report("http://192.168.1.7:8770/?token=t", false));
        check("相同上报不再重载", overlay.reloadCount == 1);
        check("带额外查询参数的同地址也不再变化",
                !sync.report("http://192.168.1.7:8770/?token=t&x=1", false));
        check("多次重复上报后仍只重载一次", overlay.reloadCount == 1);
    }

    private static void httpsAndIpv6SelectionPersist() {
        Context https = new Context();
        ConnectionSync httpsSync = new ConnectionSync(https, new FakeOverlayPort());
        check("https 选择写入", httpsSync.select("https://sakura.example", "t"));
        check("https origin 保留", "https://sakura.example".equals(PetConfig.baseUrl(https)));
        check("https 默认端口不补 80/443",
                "https://sakura.example/?token=t".equals(httpsSync.overlayUrl()));

        Context ipv6 = new Context();
        ConnectionSync ipv6Sync = new ConnectionSync(ipv6, new FakeOverlayPort());
        check("IPv6 选择写入", ipv6Sync.select("http://[::1]:8770", "t"));
        check("IPv6 origin 保留括号", "http://[::1]:8770".equals(PetConfig.baseUrl(ipv6)));
        check("IPv6 聊天地址可用",
                "http://[::1]:8770/?token=t".equals(ipv6Sync.overlayUrl()));

        Context defaultPort = new Context();
        ConnectionSync defaultSync = new ConnectionSync(defaultPort, new FakeOverlayPort());
        check("显式默认端口选择写入", defaultSync.select("http://host:80", "t"));
        check("显式默认端口被折叠", "http://host".equals(PetConfig.baseUrl(defaultPort)));
    }

    private static void diagnosticsAreSanitized() {
        Context context = new Context();
        FakeOverlayPort overlay = new FakeOverlayPort();
        ConnectionSync sync = new ConnectionSync(context, overlay);
        sync.report("http://192.168.1.7:8770/?token=secret-token", false);

        String diagnostics = sync.diagnostics();
        check("诊断不含主机", !diagnostics.contains("192.168.1.7"));
        check("诊断不含端口", !diagnostics.contains("8770"));
        check("诊断不含 token", !diagnostics.contains("secret-token"));
        check("诊断含 scheme 类别", diagnostics.contains("\"scheme\":\"http\""));
        check("诊断含主机类别", diagnostics.contains("\"hostKind\":\"ipv4\""));
        check("诊断含端口类别", diagnostics.contains("\"portKind\":\"explicit\""));
        check("诊断含 token 类别", diagnostics.contains("\"token\":\"set\""));
        check("与上报一致时为 consistent",
                diagnostics.contains("\"consistent\":true"));

        // 用户在连接页改成另一个地址后，尚未有新页面上报 → 不一致
        sync.select("10.0.0.9:8770", "secret-token");
        check("改了地址但页面未上报 → 不一致",
                sync.diagnostics().contains("\"consistent\":false"));
        check("不一致诊断仍不含主机",
                !sync.diagnostics().contains("10.0.0.9"));
    }

    // ---- 桥接接线 ----------------------------------------------------

    private static void bridgeRoutesReportSource() {
        FakeHost host = new FakeHost();
        RemoteBridge bridge = new RemoteBridge(host);

        bridge.rememberUrl("http://192.168.1.7:8770/?token=t");
        check("rememberUrl 记为 App 页面",
                "http://192.168.1.7:8770/?token=t".equals(host.lastUrl) && !host.lastOverlay);
        bridge.rememberOverlayUrl("http://10.0.0.9:8770/?token=old&mode=overlay");
        check("rememberOverlayUrl 记为立绘页面",
                host.lastOverlay && host.lastUrl.contains("mode=overlay"));

        bridge.open("javascript:alert(1)");
        check("open 拒绝非 HTTP(S) scheme", host.loadedUrl.isEmpty());
        bridge.open("http://192.168.1.7:8770/?token=t");
        check("open 接受 HTTP(S)", host.loadedUrl.startsWith("http://192.168.1.7:8770/"));
    }

    // ---- 测试替身 ----------------------------------------------------

    /** 记录重载次数/地址，用来断言「不重载/只重载一次」。 */
    private static final class FakeOverlayPort implements ConnectionSync.OverlayPort {
        boolean running;
        int reloadCount = 0;
        String lastUrl = "";

        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void reload(String chatUrl) {
            reloadCount++;
            lastUrl = chatUrl;
        }
    }

    /** 记录桥接上报来源；其余 Host 方法对本测试无意义。 */
    private static final class FakeHost implements RemoteBridge.Host {
        String lastUrl = "";
        boolean lastOverlay = false;
        String loadedUrl = "";
        String savedServer = "";
        String savedToken = "";

        @Override
        public void loadRemote(String url) {
            loadedUrl = url == null ? "" : url;
        }

        @Override
        public void setPetScale(float scale) {
        }

        @Override
        public float getPetScale() {
            return 1f;
        }

        @Override
        public boolean isOverlayRunning() {
            return false;
        }

        @Override
        public boolean startOverlay() {
            return false;
        }

        @Override
        public void stopOverlay() {
        }

        @Override
        public void requestBatteryExemption() {
        }

        @Override
        public boolean isIgnoringBatteryOptimizations() {
            return false;
        }

        @Override
        public void saveServer(String server, String token) {
            savedServer = server == null ? "" : server;
            savedToken = token == null ? "" : token;
        }

        @Override
        public void rememberRemoteUrl(String url, boolean overlayPage) {
            lastUrl = url == null ? "" : url;
            lastOverlay = overlayPage;
        }

        @Override
        public void enterOverlayMode() {
        }

        @Override
        public void setKeyboardOpen(boolean open) {
        }

        @Override
        public void setBubbleMode(boolean bubble) {
        }

        @Override
        public void setTouchable(boolean touchable) {
        }

        @Override
        public void setDragging(boolean dragging) {
        }

        @Override
        public void moveWindowBy(float dx, float dy) {
        }

        @Override
        public void setWindowPositionPx(float x, float y) {
        }

        @Override
        public String overlayLayoutInfo() {
            return "{}";
        }

        @Override
        public void setWindowSizePx(float width, float height) {
        }

        @Override
        public void requestScreenshot(String pageSessionId) {
        }

        @Override
        public void requestScreenPermission(String pageSessionId) {
        }

        @Override
        public boolean hasScreenPermission() {
            return false;
        }

        @Override
        public void deliverScreenshot(String dataUrl, String error) {
        }

        @Override
        public void registerPageSession(String pageSessionId) {
        }

        @Override
        public void invalidatePageSession(String pageSessionId) {
        }

        @Override
        public String petStatus() {
            return "{}";
        }

        @Override
        public String setTouchRegion(String json) {
            return "unsupported";
        }

        @Override
        public void clearTouchRegion() {
        }

        @Override
        public boolean isTouchRegionSupported() {
            return false;
        }

        @Override
        public void setPassThroughEnabled(boolean enabled) {
        }

        @Override
        public String touchRegionStatus() {
            return "{}";
        }

        @Override
        public String renderDiagnostics() {
            return "{}";
        }
    }

    // ---- 断言辅助 ----------------------------------------------------

    private static android.content.SharedPreferences.Memory memoryPrefs(Context context) {
        return context.memoryPrefs(PetConfig.PREFS);
    }

    private static Map<String, Object> lastSnapshot(
            android.content.SharedPreferences.Memory prefs) {
        return prefs.appliedSnapshots.get(prefs.appliedSnapshots.size() - 1);
    }

    private static void check(String name, boolean condition) {
        if (condition) {
            System.out.println("  [ok] " + name);
        } else {
            failures++;
            System.err.println("  [FAIL] " + name);
        }
    }
}
