package android.os;

import java.util.HashMap;
import java.util.Map;

/** 最小 Bundle 替身（键值存取，够真实 Activity 生命周期测试用）。 */
public class Bundle {

    private final Map<String, Object> values = new HashMap<>();

    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }

    public String getString(String key, String defaultValue) {
        String value = getString(key);
        return value == null ? defaultValue : value;
    }

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    public void putBoolean(String key, boolean value) {
        values.put(key, value);
    }

    public long getLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value instanceof Long ? (Long) value : defaultValue;
    }

    public void putLong(String key, long value) {
        values.put(key, value);
    }
}
