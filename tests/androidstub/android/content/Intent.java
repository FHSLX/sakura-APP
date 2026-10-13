package android.content;

import android.os.Parcelable;

import java.util.HashMap;
import java.util.Map;

/** 最小 Intent 替身：extra 存取 + 目标类，够真实 Service/Activity 接线测试。 */
public class Intent implements Parcelable {

    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_SINGLE_TOP = 0x20000000;

    private final Map<String, Object> extras = new HashMap<>();
    private final Class<?> targetClass;
    private int flags;

    public Intent() {
        this.targetClass = null;
    }

    public Intent(Context context, Class<?> targetClass) {
        this.targetClass = targetClass;
    }

    public Class<?> targetClassForTest() {
        return targetClass;
    }

    public Intent setFlags(int flags) {
        this.flags = flags;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public Intent putExtra(String key, String value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, int value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, boolean value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, Parcelable value) {
        extras.put(key, value);
        return this;
    }

    public String getStringExtra(String key) {
        Object value = extras.get(key);
        return value instanceof String ? (String) value : null;
    }

    public int getIntExtra(String key, int defaultValue) {
        Object value = extras.get(key);
        return value instanceof Integer ? (Integer) value : defaultValue;
    }

    public boolean getBooleanExtra(String key, boolean defaultValue) {
        Object value = extras.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }

    @SuppressWarnings("unchecked")
    public <T extends Parcelable> T getParcelableExtra(String key) {
        return (T) extras.get(key);
    }
}
