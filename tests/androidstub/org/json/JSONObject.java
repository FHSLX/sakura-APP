package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSONObject 替身（INT-07 测试专用，不进入产品构建）。
 *
 * 带一个自包含的小型递归下降解析器：够解析 OverlayTouchRegionController
 * 的输入区域载荷（对象 / 数组 / 数字 / 字符串 / 布尔 / null）。这样真实产品的
 * {@code parse(json)} 分支能被真正执行，而不是被跳过。
 */
public class JSONObject {

    private final Map<String, Object> values;

    JSONObject(Map<String, Object> values) {
        this.values = values;
    }

    public JSONObject() {
        this(new LinkedHashMap<>());
    }

    public JSONObject(String text) throws JSONException {
        this(parseObject(text));
    }

    private static Map<String, Object> parseObject(String text) throws JSONException {
        Object parsed = new Parser(text).parseValue();
        if (!(parsed instanceof Map)) {
            throw new JSONException("not a JSON object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) parsed;
        return map;
    }

    public long optLong(String key, long defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public double optDouble(String key, double defaultValue) {
        Object value = values.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public String optString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    public boolean optBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return defaultValue;
    }

    public JSONArray optJSONArray(String key) {
        Object value = values.get(key);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    /** 把字符串编码成带引号的 JSON 字符串（与 Android org.json 行为一致）。 */
    public static String quote(String string) {
        if (string == null || string.isEmpty()) {
            return "\"\"";
        }
        StringBuilder builder = new StringBuilder(string.length() + 2);
        builder.append('"');
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
                case '\b':
                    builder.append("\\b");
                    break;
                case '\f':
                    builder.append("\\f");
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

    /** 极简递归下降解析器。 */
    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text == null ? "" : text;
        }

        Object parseValue() throws JSONException {
            skipWhitespace();
            if (index >= text.length()) {
                throw new JSONException("empty json");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return parseObjectValue();
                case '[':
                    return parseArrayValue();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObjectValue() throws JSONException {
            Map<String, Object> map = new LinkedHashMap<>();
            index++;   // '{'
            skipWhitespace();
            if (peek() == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw new JSONException("expected ':'");
                }
                index++;
                map.put(key, parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == '}') {
                    index++;
                    return map;
                }
                throw new JSONException("expected ',' or '}'");
            }
        }

        private JSONArray parseArrayValue() throws JSONException {
            List<Object> list = new ArrayList<>();
            index++;   // '['
            skipWhitespace();
            if (peek() == ']') {
                index++;
                return new JSONArray(list);
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char next = peek();
                if (next == ',') {
                    index++;
                    continue;
                }
                if (next == ']') {
                    index++;
                    return new JSONArray(list);
                }
                throw new JSONException("expected ',' or ']'");
            }
        }

        private String parseString() throws JSONException {
            if (peek() != '"') {
                throw new JSONException("expected string");
            }
            index++;
            StringBuilder builder = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return builder.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw new JSONException("bad escape");
                    }
                    char escape = text.charAt(index++);
                    switch (escape) {
                        case '"':
                            builder.append('"');
                            break;
                        case '\\':
                            builder.append('\\');
                            break;
                        case '/':
                            builder.append('/');
                            break;
                        case 'n':
                            builder.append('\n');
                            break;
                        case 'r':
                            builder.append('\r');
                            break;
                        case 't':
                            builder.append('\t');
                            break;
                        case 'b':
                            builder.append('\b');
                            break;
                        case 'f':
                            builder.append('\f');
                            break;
                        case 'u':
                            if (index + 4 > text.length()) {
                                throw new JSONException("bad unicode");
                            }
                            builder.append((char) Integer.parseInt(
                                    text.substring(index, index + 4), 16));
                            index += 4;
                            break;
                        default:
                            throw new JSONException("bad escape");
                    }
                    continue;
                }
                builder.append(c);
            }
            throw new JSONException("unterminated string");
        }

        private Double parseNumber() throws JSONException {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.'
                        || c == 'e' || c == 'E') {
                    index++;
                } else {
                    break;
                }
            }
            if (start == index) {
                throw new JSONException("unexpected char at " + index);
            }
            try {
                return Double.valueOf(text.substring(start, index));
            } catch (NumberFormatException error) {
                throw new JSONException("bad number");
            }
        }

        private void expect(String literal) throws JSONException {
            if (!text.startsWith(literal, index)) {
                throw new JSONException("expected " + literal);
            }
            index += literal.length();
        }

        private char peek() {
            return index < text.length() ? text.charAt(index) : '\0';
        }

        private void skipWhitespace() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    index++;
                } else {
                    break;
                }
            }
        }
    }
}
