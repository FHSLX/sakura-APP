package android.webkit;

/** 最小 ValueCallback 替身（测试专用）。 */
public interface ValueCallback<T> {
    void onReceiveValue(T value);
}
