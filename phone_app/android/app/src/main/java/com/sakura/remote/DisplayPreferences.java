package com.sakura.remote;

/**
 * 「显示 / 穿透」偏好的**去敏扁平 JSON** 读写（纯 Java，无 Android 依赖）。
 *
 * <p>为什么单独一个类：App（MainActivity）与悬浮窗（OverlayService）必须用
 * **同一份**偏好协议，否则两个 WebView 会各自持久化、互相覆盖。协议刻意做得
 * 极小，只支持固定 key 的布尔值，既不引入 JSON 依赖（测试替身的 org.json
 * 只有 quote），也没有任何自由字符串入口 —— 不可能把 URL/token/聊天内容
 * 混进偏好或诊断。
 *
 * <p>协议：
 * <pre>
 * 读：{"float":{"has":bool,"value":bool},
 *      "passThrough":{"has":bool,"value":bool,"supported":bool}}
 * 写：{"float":bool,"passThrough":bool}   // 只处理出现的 key
 *     -> {"ok":true,...} 或 {"ok":false,"error":"code"}
 * </pre>
 */
final class DisplayPreferences {

    static final String KEY_FLOAT = "float";
    static final String KEY_PASS_THROUGH = "passThrough";

    private DisplayPreferences() {
    }

    /** 读扁平 key 的布尔值；key 不存在或不是 true/false 时返回 null（= 无偏好）。 */
    static Boolean bool(String json, String key) {
        if (json == null || key == null) {
            return null;
        }
        String needle = "\"" + key + "\"";
        int at = json.indexOf(needle);
        if (at < 0) {
            return null;
        }
        int colon = json.indexOf(':', at + needle.length());
        if (colon < 0) {
            return null;
        }
        int i = colon + 1;
        int length = json.length();
        while (i < length && Character.isWhitespace(json.charAt(i))) {
            i++;
        }
        if (json.startsWith("true", i)) {
            return Boolean.TRUE;
        }
        if (json.startsWith("false", i)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 写入请求里是否至少带了一个可识别的偏好。 */
    static boolean hasAny(String json) {
        return bool(json, KEY_FLOAT) != null || bool(json, KEY_PASS_THROUGH) != null;
    }

    /** 只允许稳定错误码，避免任何自由字符串进入响应。 */
    static String failure(String code) {
        StringBuilder safe = new StringBuilder();
        if (code != null) {
            for (int i = 0; i < code.length() && safe.length() < 32; i++) {
                char c = code.charAt(i);
                if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-') {
                    safe.append(c);
                }
            }
        }
        if (safe.length() == 0) {
            safe.append("invalid");
        }
        return "{\"ok\":false,\"error\":\"" + safe + "\"}";
    }

    /** 偏好快照（供网页渲染开关）。 */
    static String snapshot(boolean floatHas, boolean floatValue,
                           boolean passHas, boolean passValue, boolean supported) {
        return "{\"float\":{\"has\":" + floatHas + ",\"value\":" + floatValue + "},"
                + "\"passThrough\":{\"has\":" + passHas + ",\"value\":" + passValue
                + ",\"supported\":" + supported + "}}";
    }

    /**
     * 写入成功后的可校验回执。
     *
     * <p>只在 {@code commit()} 返回 true（磁盘写入确认成功）后才使用；
     * 回执里的 value 是提交后的实际偏好值，不是「内存里先改了就假定成功」。
     */
    static String applied(boolean floatHas, boolean floatValue,
                          boolean passHas, boolean passValue) {
        return "{\"ok\":true"
                + ",\"float\":{\"has\":" + floatHas + ",\"value\":" + floatValue + "}"
                + ",\"passThrough\":{\"has\":" + passHas + ",\"value\":" + passValue + "}}";
    }
}
