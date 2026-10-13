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
