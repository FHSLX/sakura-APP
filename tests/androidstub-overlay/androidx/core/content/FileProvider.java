package androidx.core.content;

import android.content.Context;
import android.net.Uri;

import java.io.File;

/**
 * 最小 FileProvider 替身（测试专用）。
 *
 * <p>只负责把 cache 文件映射成一个稳定的 {@code content://} URI，
 * 便于测试断言「相机输出用的是 content URI 而不是 file://」。
 */
public class FileProvider {

    public static Uri getUriForFile(Context context, String authority, File file) {
        if (context == null || authority == null || file == null) {
            throw new IllegalArgumentException("missing argument");
        }
        return Uri.parse("content://" + authority + "/captures/" + file.getName());
    }
}
