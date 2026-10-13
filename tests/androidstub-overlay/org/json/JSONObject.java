package org.json;

/**
 * 最小 JSONObject 替身：只提供产品代码用到的 {@code quote}。
 *
 * OverlayService/PetWindow 只用它把去敏字符串转义进 JSON，不需要解析能力。
 */
public class JSONObject {

    public static String quote(String string) {
        if (string == null || string.length() == 0) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder("\"");
        for (int i = 0; i < string.length(); i++) {
            char c = string.charAt(i);
            switch (c) {
                case '"':
                    builder.append("\\\"");
                    break;
                case '\\':
                    builder.append("\\\\");
                    break;
                case '\n':
                    builder.append("\\n");
                    break;
                case '\r':
                    builder.append("\\r");
                    break;
                case '\t':
                    builder.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        builder.append(String.format("\\u%04x", (int) c));
                    } else {
                        builder.append(c);
                    }
            }
        }
        builder.append('"');
        return builder.toString();
    }
}
