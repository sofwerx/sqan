package org.sofwerx.sqan;

import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;

public class SqAnService extends Service {
    private final static int SQAN_NOTIFICATION_ID = 61;
    private final static String NOTIFICATION_CHANNEL = "sqan_notify";
    private NotificationChannel channel = null;

    @Override
    public void onCreate() {
        super.onCreate();
        notifyStatus(); //TODO temp
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void notifyStatus() {
        createNotificationChannel();
        PendingIntent pendingIntent = null;
        try {
            Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqan.MainActivity"));
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        Notification.Builder builder;
        builder = new Notification.Builder(this);
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle("Test notification");
        builder.setTicker("Test notification");
        builder.setSmallIcon(R.drawable.ic_notification);
        //builder.setPriority(Notification.PRIORITY_HIGH);
        builder.setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder.setChannelId(NOTIFICATION_CHANNEL);

        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(SQAN_NOTIFICATION_ID,builder.build());
    }

    private void createNotificationChannel() {
        if ((channel == null) && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)) {
            CharSequence name;
            String description;
            int importance;
            name = getString(R.string.channel_name);
            description = getString(R.string.channel_description);
            importance = NotificationManager.IMPORTANCE_DEFAULT;

            channel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }


}
