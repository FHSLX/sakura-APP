package org.json;

import java.util.List;

/** 最小 JSONArray 替身（配套 JSONObject 的自带解析器）。 */
public class JSONArray {

    private final List<Object> values;

    JSONArray(List<Object> values) {
        this.values = values;
    }

    public int length() {
        return values.size();
    }

    private Object at(int index) {
        return index >= 0 && index < values.size() ? values.get(index) : null;
    }

    public JSONArray optJSONArray(int index) {
        Object value = at(index);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }

    public double optDouble(int index, double defaultValue) {
        Object value = at(index);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public long optLong(int index, long defaultValue) {
        double value = optDouble(index, Double.NaN);
        return Double.isNaN(value) ? defaultValue : (long) value;
    }

    public String optString(int index, String defaultValue) {
        Object value = at(index);
        return value == null ? defaultValue : String.valueOf(value);
    }
}
