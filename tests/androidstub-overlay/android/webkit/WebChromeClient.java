package android.webkit;

import android.net.Uri;

/** 最小 WebChromeClient 替身：控制台诊断 + 文件选择回调。 */
public class WebChromeClient {

    public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
        return false;
    }

    @SuppressWarnings("deprecation")
    public void onConsoleMessage(String message, int lineNumber, String sourceID) {
    }

    /** 文件选择请求参数（最小替身）。 */
    public static class FileChooserParams {
        public static final int MODE_OPEN = 0;
        public static final int MODE_OPEN_MULTIPLE = 1;
        public static final int MODE_OPEN_FOLDER = 2;
        public static final int MODE_SAVE = 3;

        public String[] getAcceptTypes() {
            return new String[0];
        }

        public int getMode() {
            return MODE_OPEN;
        }

        public boolean isCaptureEnabled() {
            return false;
        }

        public String getTitle() {
            return "";
        }

        public String getFilenameHint() {
            return "";
        }
    }

    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                     FileChooserParams fileChooserParams) {
        return false;
    }
}
