# R8 / ProGuard rules.
#
# Shrinking is enabled for size: without it classes.dex was ~7 MB, i.e. almost
# the whole 6.9 MB APK. But shrinking can silently break the JavaScript bridge,
# so the rules below are the part that actually matters.

# ---- JavaScript bridge -------------------------------------------------------
#
# Every @JavascriptInterface method is called BY NAME from JavaScript through
# the injected `SakuraNative` object. R8 cannot see those call sites, so it would
# strip or rename them and the bridge would fail at runtime with
# "SakuraNative.something is not a function".
#
# Keep the class, all its members, and the annotation itself.
-keep class com.sakura.remote.RemoteBridge { *; }
-keepclassmembers class com.sakura.remote.RemoteBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes *Annotation*

# ---- WebView / Capacitor -----------------------------------------------------
#
# Capacitor resolves the host Activity and plugins reflectively.
-keep class com.getcapacitor.** { *; }
-keep @com.getcapacitor.annotation.CapacitorPlugin class * { *; }
-keep class * extends com.getcapacitor.Plugin { *; }
-keepclassmembers class * extends com.getcapacitor.Plugin {
    @com.getcapacitor.PluginMethod public <methods>;
}
-keepattributes JavascriptInterface

# WebView callbacks are invoked from native code.
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, ...);
}
-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void *(android.webkit.WebView, ...);
}

# ---- Android components ------------------------------------------------------
#
# Activities / Services / Receivers are named in AndroidManifest.xml and
# instantiated by the framework.
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.app.Application

# Custom Views can be inflated from XML by name.
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ---- Diagnostics -------------------------------------------------------------
#
# Keep line numbers so a crash report is still readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
