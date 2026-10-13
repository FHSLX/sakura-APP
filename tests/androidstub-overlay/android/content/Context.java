package android.content;

import android.content.pm.PackageManager;
import android.content.res.Resources;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 最小 Context 替身：提供 Resources（卡片 dp 计算用）与内存 SharedPreferences。 */
public class Context {

    public static final int MODE_PRIVATE = 0;

    private static final AtomicLong CACHE_SEQ = new AtomicLong(0);

    private final Resources resources = new Resources();
    private final Map<String, SharedPreferences.Memory> prefsStore = new HashMap<>();
    private final PackageManager packageManager = new PackageManager();
    private File cacheDir;

    public Resources getResources() {
        return resources;
    }

    public Context getApplicationContext() {
        return this;
    }

    public String getPackageName() {
        return "com.sakura.remote.test";
    }

    public PackageManager getPackageManager() {
        return packageManager;
    }

    /** 拍照输出用的 cache 目录；每个 Context 实例默认独立，避免测试互相清理。 */
    public File getCacheDir() {
        if (cacheDir == null) {
            cacheDir = new File(System.getProperty("java.io.tmpdir"),
                    "sakura-test-cache-" + CACHE_SEQ.incrementAndGet());
        }
        if (!cacheDir.isDirectory()) {
            cacheDir.mkdirs();
        }
        return cacheDir;
    }

    public void setCacheDirForTest(File dir) {
        cacheDir = dir;
    }

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return memoryPrefs(name);
    }

    /** 文件选择辅助页用：默认不可读的 ContentResolver 替身，可注入。 */
    private ContentResolver contentResolver = new ContentResolver();

    public ContentResolver getContentResolver() {
        return contentResolver;
    }

    public void setContentResolverForTest(ContentResolver resolver) {
        contentResolver = resolver;
    }

    /** 记录最近一次 startActivity 的 Intent（测试断言接线用）。 */
    public Intent lastStartedIntent;

    public void startActivity(Intent intent) {
        lastStartedIntent = intent;
    }

    /** 测试辅助：同名 prefs 返回同一内存实例，便于断言落盘次数与原子快照。 */
    public SharedPreferences.Memory memoryPrefs(String name) {
        SharedPreferences.Memory existing = prefsStore.get(name);
        if (existing == null) {
            existing = new SharedPreferences.Memory();
            prefsStore.put(name, existing);
        }
        return existing;
    }
}
