package com.sakura.remote;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 处理通知栏「关闭桌面立绘」按钮。
 *
 * 用 BroadcastReceiver 而不是直接调 stopService，是为了让通知里的
 * PendingIntent 更明确，也方便以后扩展其它控制动作。
 */
public class ConfigReceiver extends BroadcastReceiver {

    public static final String ACTION_STOP_OVERLAY = "com.sakura.remote.STOP_OVERLAY";
    public static final String ACTION_REFRESH_OVERLAY = "com.sakura.remote.REFRESH_OVERLAY";
    public static final String ACTION_RESCALE_OVERLAY = "com.sakura.remote.RESCALE_OVERLAY";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_STOP_OVERLAY.equals(action)) {
            // 用户主动关闭：清掉「应常驻」标记，下次打开 App 不再自动拉起
            PetConfig.setOverlayEnabled(context, false);
            context.stopService(new Intent(context, OverlayService.class));
            return;
        }
        if (ACTION_REFRESH_OVERLAY.equals(action) || ACTION_RESCALE_OVERLAY.equals(action)) {
            Intent service = new Intent(context, OverlayService.class);
            service.putExtra(OverlayService.EXTRA_REFRESH, ACTION_REFRESH_OVERLAY.equals(action));
            service.putExtra(OverlayService.EXTRA_RESCALE, ACTION_RESCALE_OVERLAY.equals(action));
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(service);
                } else {
                    context.startService(service);
                }
            } catch (Exception ignored) {
                // 后台启动受限时忽略
            }
        }
    }
}
