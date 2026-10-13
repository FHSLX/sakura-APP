package com.sakura.remote;

import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.Locale;

/**
 * 单一经过规范化的「连接目标」：完整 origin + token。
 *
 * <p>为什么要有这个类：连接设置页跑在 {@code https://localhost}（Capacitor 本地页），
 * 远程聊天页跑在 {@code http://电脑IP:8770}，两者不同源；原生悬浮窗又要独立读配置。
 * 之前「网页 localStorage」「PetConfig.server」「远程页回报」三份地址各说各话，
 * 出现「聊天页连的是新 IP、桌面立绘还去连旧主机」的地址分裂（真机表现为
 * {@code ERROR_HOST_LOOKUP = -2}）。
 *
 * <p>本类把连接目标收敛成唯一形态：
 * <ul>
 *   <li>origin = {@code scheme://host[:port]}，scheme 只允许 http/https；</li>
 *   <li>默认端口（http:80 / https:443）折叠掉，显式非默认端口保留；</li>
 *   <li>IPv6 保留 {@code [..]} 括号形式；</li>
 *   <li>拒绝非 HTTP(S)、空/非法主机、带 userinfo 的地址；</li>
 *   <li>{@link #fromRemoteUrl(String)} 额外拒绝本地 Capacitor 页面
 *       （{@code https://localhost}），它们是「连接设置页自己」而不是远程目标。</li>
 * </ul>
 *
 * <p>纯 Java、无 Android 依赖：同一份真实决策代码既跑在 App 里，也直接跑在
 * 离线行为测试里，避免「测试复制了一份逻辑」的自欺。
 */
public final class ConnectionProfile {

    /** token 查询参数名，与远程页 /api 一致。 */
    public static final String TOKEN_PARAM = "token";

    public static final int DEFAULT_HTTP_PORT = 80;
    public static final int DEFAULT_HTTPS_PORT = 443;

    /** 规范化 origin；空串表示未配置/无效。 */
    public final String origin;
    /** 规范化 token；空串表示没有。 */
    public final String token;

    private final String scheme;
    private final String hostKind;
    private final String portKind;

    private ConnectionProfile(String origin, String token,
                              String scheme, String hostKind, String portKind) {
        this.origin = origin == null ? "" : origin;
        this.token = token == null ? "" : token;
        this.scheme = scheme == null ? "" : scheme;
        this.hostKind = hostKind == null ? "" : hostKind;
        this.portKind = portKind == null ? "" : portKind;
    }

    public static ConnectionProfile empty() {
        return new ConnectionProfile("", "", "", "", "");
    }

    public boolean hasOrigin() {
        return !origin.isEmpty();
    }

    public boolean hasToken() {
        return !token.isEmpty();
    }

    /** 只有 origin 和 token 都在，才算「可连接」。 */
    public boolean isConfigured() {
        return hasOrigin() && hasToken();
    }

    /** origin + token 是否完全相同（用于「配置有没有真的变化」的判定）。 */
    public boolean sameAs(ConnectionProfile other) {
        return other != null && origin.equals(other.origin) && token.equals(other.token);
    }

    /** 聊天页地址；未配置时返回空串。 */
    public String chatUrl() {
        if (origin.isEmpty()) {
            return "";
        }
        if (token.isEmpty()) {
            return origin + "/";
        }
        return origin + "/?" + TOKEN_PARAM + "=" + encodeToken(token);
    }

    /**
     * 用户在连接设置页明确选择的目标。
     *
     * <p>接受 {@code host}、{@code host:port}、{@code http(s)://host[:port]}；
     * 没写 scheme 时按 http 处理（历史行为，局域网明文部署）。
     */
    public static ConnectionProfile fromUserInput(String server, String token) {
        String raw = server == null ? "" : server.trim();
        if (raw.isEmpty()) {
            return empty();
        }
        int schemeSep = raw.indexOf("://");
        if (schemeSep >= 0) {
            // 用户显式写了 scheme：只接受 http/https，绝不把 ftp:// 之类
            // 悄悄拼成 http://ftp://... 然后当成合法地址
            String scheme = raw.substring(0, schemeSep).toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return empty();
            }
            return parse(raw, normalizeToken(token), false);
        }
        return parse("http://" + raw, normalizeToken(token), false);
    }

    /**
     * 远程页成功加载后回报的完整地址。
     *
     * <p>非法、非 HTTP(S)、无效主机、本地 Capacitor 页面一律返回 {@link #empty()}。
     * token 从查询参数解析；没有 token 时返回 origin 有效、token 为空的 profile，
     * 由调用方（{@code PetConfig.applyPageReport}）决定是否接受。
     */
    public static ConnectionProfile fromRemoteUrl(String url) {
        return parse(url, "", true);
    }

    private static ConnectionProfile parse(String url, String token, boolean remotePage) {
        if (url == null) {
            return empty();
        }
        String value = url.trim();
        if (value.isEmpty()) {
            return empty();
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return empty();
        }
        try {
            URL parsed = new URL(value);
            String scheme = parsed.getProtocol() == null
                    ? "" : parsed.getProtocol().toLowerCase(Locale.ROOT);
            if (!"http".equals(scheme) && !"https".equals(scheme)) {
                return empty();
            }
            // 带 userinfo 的地址会掩盖真实主机（http://evil@host/），直接拒绝
            String userInfo = parsed.getUserInfo();
            if (userInfo != null && !userInfo.isEmpty()) {
                return empty();
            }
            String host = parsed.getHost();
            int port = parsed.getPort();
            if (port == 0 || port > 65535) {
                // 显式端口 0 / 越界：java.net.URL 不校验，这里必须自己拒绝
                return empty();
            }
            if (!validHost(host)) {
                return empty();
            }
            if (remotePage && isLocalCapacitorPage(scheme, host, port)) {
                return empty();
            }
            int defaultPort = "https".equals(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
            boolean explicitPort = port > 0 && port != defaultPort;
            String origin = scheme + "://" + host + (explicitPort ? ":" + port : "");
            String effectiveToken = remotePage ? extractToken(parsed) : token;
            return new ConnectionProfile(origin, effectiveToken, scheme,
                    hostKind(host), explicitPort ? "explicit" : "default");
        } catch (Exception error) {
            // 解析失败一律当作「没配过」，绝不把半截地址写进配置
            return empty();
        }
    }

    /** 本地 Capacitor 页面：连接设置页自己，不是远程目标。 */
    public static boolean isLocalCapacitorPage(String scheme, String host, int port) {
        return "https".equals(scheme)
                && "localhost".equalsIgnoreCase(host)
                && (port <= 0 || port == DEFAULT_HTTPS_PORT);
    }

    /**
     * 主机合法性（语法层，不做 DNS 解析）。
     *
     * <p>IPv4 / 域名只允许字母、数字、{@code -}、{@code .}；不接受下划线、空格、
     * 空标签或首尾分隔符。IPv6 只接受 {@code [..]} 形式且内部含 {@code :}。
     */
    public static boolean validHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        if (host.charAt(0) == '[') {
            if (host.length() < 4 || host.charAt(host.length() - 1) != ']') {
                return false;
            }
            String inner = host.substring(1, host.length() - 1);
            if (inner.indexOf(':') < 0) {
                return false;
            }
            for (int index = 0; index < inner.length(); index++) {
                char c = inner.charAt(index);
                boolean hex = (c >= '0' && c <= '9')
                        || (c >= 'a' && c <= 'f')
                        || (c >= 'A' && c <= 'F');
                if (!hex && c != ':' && c != '.') {
                    return false;
                }
            }
            return true;
        }
        if (host.indexOf(':') >= 0) {
            return false;
        }
        if (host.charAt(0) == '-' || host.charAt(0) == '.'
                || host.charAt(host.length() - 1) == '-' || host.charAt(host.length() - 1) == '.') {
            return false;
        }
        if (host.contains("..")) {
            return false;
        }
        for (int index = 0; index < host.length(); index++) {
            char c = host.charAt(index);
            boolean alphaNum = (c >= '0' && c <= '9')
                    || (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z');
            if (!alphaNum && c != '-' && c != '.') {
                return false;
            }
        }
        return true;
    }

    private static String hostKind(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        if (host.charAt(0) == '[') {
            return "ipv6";
        }
        return isIpv4(host) ? "ipv4" : "hostname";
    }

    private static boolean isIpv4(String host) {
        int dots = 0;
        int part = -1;
        int value = 0;
        for (int index = 0; index < host.length(); index++) {
            char c = host.charAt(index);
            if (c == '.') {
                if (part < 0 || value > 255) {
                    return false;
                }
                dots++;
                part = -1;
                value = 0;
                continue;
            }
            if (c < '0' || c > '9') {
                return false;
            }
            part = 0;
            value = value * 10 + (c - '0');
            if (value > 255) {
                return false;
            }
        }
        return dots == 3 && part >= 0;
    }

    private static String extractToken(URL parsed) {
        String query = parsed.getQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        for (String pair : query.split("&")) {
            int equal = pair.indexOf('=');
            if (equal <= 0) {
                continue;
            }
            if (TOKEN_PARAM.equals(pair.substring(0, equal))) {
                return normalizeToken(decode(pair.substring(equal + 1)));
            }
        }
        return "";
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Exception error) {
            return "";
        }
    }

    private static String encodeToken(String token) {
        try {
            return URLEncoder.encode(token, "UTF-8");
        } catch (Exception error) {
            return token;
        }
    }

    public static String normalizeToken(String token) {
        return token == null ? "" : token.trim();
    }

    /** 去敏诊断：只输出类别，不含主机、端口、URL、token 或用户内容。 */
    public String categoriesJson(boolean configured, boolean consistent) {
        return "{\"configured\":" + configured
                + ",\"scheme\":\"" + scheme + "\""
                + ",\"hostKind\":\"" + hostKind + "\""
                + ",\"portKind\":\"" + portKind + "\""
                + ",\"token\":\"" + (hasToken() ? "set" : "unset") + "\""
                + ",\"consistent\":" + consistent + "}";
    }

    @Override
    public String toString() {
        // 刻意不输出 origin/token：这个对象可能被日志打印
        return "ConnectionProfile{" + categoriesJson(isConfigured(), true) + "}";
    }
}
