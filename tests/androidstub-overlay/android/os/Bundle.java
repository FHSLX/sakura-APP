package android.os;

import java.util.HashMap;
import java.util.Map;

/** 最小 Bundle 替身（测试专用）。 */
public class Bundle {

    private final Map<String, Object> values = new HashMap<>();

    public void putString(String key, String value) {
        values.put(key, value);
    }

    public String getString(String key) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : null;
    }
}
