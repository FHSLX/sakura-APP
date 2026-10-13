package android.net;

/** 最小 Uri 替身（测试专用）：只提供产品代码用到的解析/比较/scheme。 */
public final class Uri {

    private final String raw;

    private Uri(String raw) {
        this.raw = raw == null ? "" : raw;
    }

    public static Uri parse(String text) {
        return new Uri(text);
    }

    public String getScheme() {
        int colon = raw.indexOf(':');
        return colon > 0 ? raw.substring(0, colon) : null;
    }

    public String getLastPathSegment() {
        int slash = raw.lastIndexOf('/');
        return slash >= 0 && slash + 1 < raw.length() ? raw.substring(slash + 1) : null;
    }

    @Override
    public String toString() {
        return raw;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Uri && raw.equals(((Uri) other).raw);
    }

    @Override
    public int hashCode() {
        return raw.hashCode();
    }
}
