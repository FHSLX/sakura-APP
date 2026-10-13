package com.sakura.remote;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/** Optional, user-enabled window owner. No content retrieval or input automation. */
public final class PortraitOverlayAccess extends AccessibilityService {
    private static volatile PortraitOverlayAccess connected;

    static boolean isConnected() { return connected != null; }

    static void openSettings(Context context) {
        Intent details = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
        details.putExtra("android.intent.extra.COMPONENT_NAME",
                new android.content.ComponentName(context, PortraitOverlayAccess.class));
        details.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(details);
        } catch (RuntimeException unsupported) {
            try {
                context.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (RuntimeException ignored) {
                android.widget.Toast.makeText(context, "请在系统设置中打开无障碍服务", android.widget.Toast.LENGTH_LONG).show();
            }
        }
    }

    static WindowManager windowManager() {
        PortraitOverlayAccess service = connected;
        return service == null ? null
                : (WindowManager) service.getSystemService(Context.WINDOW_SERVICE);
    }

    @Override protected void onServiceConnected() {
        // Subscribe to no events, even from our own app. Content and gesture
        // capabilities are disabled in XML, not merely unused in Java.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = 0;
            info.flags = 0;
            info.packageNames = new String[]{getPackageName()};
            setServiceInfo(info);
        }
        connected = this;
        OverlayService.onPortraitAccessChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() { }

    private void disconnect() {
        if (connected == this) {
            connected = null;
            OverlayService.onPortraitAccessChanged();
        }
    }
    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }
    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }
}
