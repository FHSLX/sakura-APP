package android.content;

import android.net.Uri;

import java.io.InputStream;

/** 最小 ContentResolver 替身（测试专用）：默认不可读。 */
public class ContentResolver {

    public String getType(Uri uri) {
        return null;
    }

    public InputStream openInputStream(Uri uri) {
        return null;
    }

    public void takePersistableUriPermission(Uri uri, int modeFlags) {
    }
}
