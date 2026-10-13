package android.app;

/** 最小 NotificationManager 替身。 */
public class NotificationManager {

    public static final int IMPORTANCE_MIN = 1;

    public NotificationChannel getNotificationChannel(String channelId) {
        return null;
    }

    public void createNotificationChannel(NotificationChannel channel) {
    }

    public void notify(int id, Notification notification) {
    }
}
