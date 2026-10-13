package android.content;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 测试用最小 SharedPreferences 替身：真实存值，并记录每次落盘快照。
 *
 * <p>记录快照是为了在行为测试里证明「origin + token 原子写入」：
 * 不允许出现只写了 host 或只写了 token 的中间态。
 */
public interface SharedPreferences {

    String getString(String key, String defValue);

    int getInt(String key, int defValue);

    float getFloat(String key, float defValue);

    boolean getBoolean(String key, boolean defValue);

    boolean contains(String key);

    Editor edit();

    interface Editor {
        Editor putString(String key, String value);

        Editor putInt(String key, int value);

        Editor putFloat(String key, float value);

        Editor putBoolean(String key, boolean value);

        Editor remove(String key);

        Editor clear();

        void apply();

        boolean commit();
    }

    /** 内存实现：同一 Context 同名 prefs 返回同一实例。 */
    final class Memory implements SharedPreferences {

        private final Map<String, Object> values = new HashMap<>();
        /** 每次 apply/commit 后的完整快照（深拷贝）。 */
        public final List<Map<String, Object>> appliedSnapshots = new ArrayList<>();
        public int applyCount = 0;
        /** 测试注入：为 true 时 commit() 返回 false 且不写入（模拟磁盘写入失败）。 */
        public boolean failCommits;
        /** 失败提交次数。 */
        public int failedCommits;

        @Override
        public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }

        @Override
        public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }

        @Override
        public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }

        @Override
        public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }

        @Override
        public boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new EditorImpl();
        }

        private final class EditorImpl implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removed = new HashSet<>();
            private boolean clearRequested = false;

            @Override
            public Editor putString(String key, String value) {
                pending.put(key, value == null ? "" : value);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                pending.put(key, value);
                return this;
            }

            @Override
            public Editor remove(String key) {
                removed.add(key);
                pending.remove(key);
                return this;
            }

            @Override
            public Editor clear() {
                clearRequested = true;
                pending.clear();
                removed.clear();
                return this;
            }

            @Override
            public void apply() {
                commit();
            }

            @Override
            public boolean commit() {
                if (Memory.this.failCommits) {
                    Memory.this.failedCommits++;
                    return false;
                }
                if (clearRequested) {
                    values.clear();
                }
                for (String key : removed) {
                    values.remove(key);
                }
                values.putAll(pending);
                applyCount++;
                appliedSnapshots.add(new HashMap<>(values));
                return true;
            }
        }
    }
}
