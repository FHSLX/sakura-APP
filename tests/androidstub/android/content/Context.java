package android.content;

import java.util.HashMap;
import java.util.Map;

/**
 * 最小 Context 替身。
 *
 * {@code getSystemService} 从可注入的 map 里取假实现（MediaProjectionManager /
 * WindowManager / NotificationManager），这样真实产品代码调用的就是它自己的
 * 分支，而系统边界被替换成可控替身。
 */
public class Context {

    public static final String MEDIA_PROJECTION_SERVICE = "media_projection";
    public static final String WINDOW_SERVICE = "window";
    public static final String NOTIFICATION_SERVICE = "notification";
    public static final String INPUT_SERVICE = "input";
    public static final String ACTIVITY_SERVICE = "activity";

    private final Map<String, Object> services = new HashMap<>();
    private final java.util.List<Intent> startedServices = new java.util.ArrayList<>();

    public Context getApplicationContext() {
        return this;
    }

    public Object getSystemService(String name) {
        return services.get(name);
    }

    public void setSystemServiceForTest(String name, Object service) {
        services.put(name, service);
    }

    public String getString(int resId) {
        return "res-" + resId;
    }

    public void startService(Intent intent) {
        startedServices.add(intent);
    }

    public void startForegroundService(Intent intent) {
        startedServices.add(intent);
    }

    public void startActivity(Intent intent) {
        startedServices.add(intent);
    }

    public java.util.List<Intent> startedServicesForTest() {
        return startedServices;
    }
}
