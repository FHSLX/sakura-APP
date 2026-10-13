package android.app;

import android.content.Context;

/** 最小 Notification 替身 + Builder 链式 API。 */
public class Notification {

    public static class Builder {

        public Builder() {
        }

        public Builder(Context context) {
        }

        public Builder(Context context, String channelId) {
        }

        public Builder setContentTitle(CharSequence title) {
            return this;
        }

        public Builder setSmallIcon(int icon) {
            return this;
        }

        public Builder setOngoing(boolean ongoing) {
            return this;
        }

        public Builder setContentIntent(Object pendingIntent) {
            return this;
        }

        public Notification build() {
            return new Notification();
        }
    }
}
