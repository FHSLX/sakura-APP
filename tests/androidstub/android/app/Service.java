package android.app;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

/**
 * 最小 Service 替身（INT-07）。
 *
 * 它只记录系统交互（前台开始/停止、stopSelf、stopSelf(startId)），供测试观察
 * 真实 CaptureService 的生命周期结果；不含任何 CaptureService 的控制流。
 */
public class Service extends Context {

    public static final int START_STICKY = 1;
    public static final int START_NOT_STICKY = 2;
    public static final int START_REDELIVER_INTENT = 3;
    public static final int STOP_FOREGROUND_REMOVE = 1;
    public static final int STOP_FOREGROUND_DETACH = 2;

    /** 所有前台启动记录（含类型参数的重载）。 */
    public final List<Integer> startForegroundCalls = new ArrayList<>();
    public int stopForegroundCount;
    public final List<Integer> stopSelfCalls = new ArrayList<>();

    public void onCreate() {
    }

    public void onDestroy() {
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    public final void startForeground(int id, Notification notification) {
        startForegroundCalls.add(id);
    }

    public final void startForeground(int id, Notification notification, int foregroundServiceType) {
        startForegroundCalls.add(id);
    }

    public final void stopForeground(int flags) {
        stopForegroundCount++;
    }

    public final void stopForeground(boolean removeNotification) {
        stopForegroundCount++;
    }

    public final void stopSelf() {
        stopSelfCalls.add(-1);
    }

    public final void stopSelf(int startId) {
        stopSelfCalls.add(startId);
    }
}
