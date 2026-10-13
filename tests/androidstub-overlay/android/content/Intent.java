package android.content;

import android.content.pm.PackageManager;
import android.net.Uri;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 最小 Intent 替身（测试专用）：只覆盖文件选择/拍照接线用到的部分。 */
public class Intent {

    public static final String ACTION_OPEN_DOCUMENT = "android.intent.action.OPEN_DOCUMENT";
    public static final String ACTION_IMAGE_CAPTURE = "android.media.action.IMAGE_CAPTURE";
    public static final String CATEGORY_OPENABLE = "android.intent.category.OPENABLE";
    public static final String EXTRA_ALLOW_MULTIPLE = "android.intent.extra.ALLOW_MULTIPLE";

    public static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
    public static final int FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS = 0x00800000;
    public static final int FLAG_GRANT_READ_URI_PERMISSION = 0x00000001;
    public static final int FLAG_GRANT_WRITE_URI_PERMISSION = 0x00000002;

    private final Map<String, Object> extras = new HashMap<>();
    private final List<String> categories = new ArrayList<>();
    private Uri data;
    private ClipData clipData;
    private String type;
    private int flags;

    public Intent() {
    }

    public Intent(String action) {
        this.action = action;
    }

    public Intent(Context context, Class<?> cls) {
    }

    private String action;

    public String getAction() {
        return action;
    }

    public Intent addFlags(int value) {
        flags |= value;
        return this;
    }

    public int getFlags() {
        return flags;
    }

    public Intent setType(String value) {
        type = value;
        return this;
    }

    public String getType() {
        return type;
    }

    public Intent addCategory(String category) {
        categories.add(category);
        return this;
    }

    public Intent putExtra(String key, String value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, String[] value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, boolean value) {
        extras.put(key, value);
        return this;
    }

    public Intent putExtra(String key, Uri value) {
        extras.put(key, value);
        return this;
    }

    public Uri getParcelableExtra(String key) {
        Object value = extras.get(key);
        return value instanceof Uri ? (Uri) value : null;
    }

    /**
     * 测试替身：{@code resolveActivity} 的结果。
     *
     * <p>默认非 null（「有相机应用」），测试可设为 null 模拟无相机。
     */
    public static Object resolveActivityForTest = new Object();

    public Object resolveActivity(PackageManager manager) {
        return resolveActivityForTest;
    }

    public String getStringExtra(String key) {
        Object value = extras.get(key);
        return value instanceof String ? (String) value : null;
    }

    public String[] getStringArrayExtra(String key) {
        Object value = extras.get(key);
        return value instanceof String[] ? (String[]) value : null;
    }

    public boolean getBooleanExtra(String key, boolean fallback) {
        Object value = extras.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public Intent setData(Uri value) {
        data = value;
        return this;
    }

    public Uri getData() {
        return data;
    }

    public Intent setClipData(ClipData value) {
        clipData = value;
        return this;
    }

    public ClipData getClipData() {
        return clipData;
    }
}
