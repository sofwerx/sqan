package org.sofwerx.sqan;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.PowerManager;
import android.util.Log;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;

import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.StatusHelper;
import org.sofwerx.sqan.receivers.BootReceiver;
import org.sofwerx.sqan.receivers.ConnectivityReceiver;
import org.sofwerx.sqan.receivers.PowerReceiver;
import org.sofwerx.sqan.util.NetworkUtil;

/**
 * SqAnService is the main service that keeps SqAN running and coordinates all other actions
 */
public class SqAnService extends Service {
    public final static String ACTION_STOP = "STOP";
    public final static String EXTRA_KEEP_ACTIVITY = "keepActivity";
    private final static int SQAN_NOTIFICATION_ID = 60;
    private long HELPER_INTERVAL = 1000l * 30l;
    private final static String NOTIFICATION_CHANNEL = "sqan_notify";

    private PowerManager.WakeLock wakeLock;
    private PowerManager powerManager;
    private AlarmManager alarmManager = null;
    private final IBinder mBinder = new SqAnServiceBinder();
    private BootReceiver bootReceiver = null;
    private ConnectivityReceiver connectivityReceiver = null;
    private PowerReceiver powerReceiver = null;
    private Handler handler = null;
    private static SqAnService thisService = null;
    protected SqAnStatusListener listener = null;
    private NotificationChannel channel = null;
    private ManetOps manet;
    private Status lastNotifiedStatus = Status.ERROR; //the last status provided in a notification (used to prevent the notifications from firing multiple times when there is no meaningful status change)

    @Override
    public void onCreate() {
        super.onCreate();
        Config.init(this);
        manet = new ManetOps(this);
        ExceptionHelper.set(getApplicationContext());
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sqan:SqAnService");
        handler = new Handler();
        handler.postDelayed(periodicHelper,HELPER_INTERVAL);
        startManet();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    /**
     * The periodicHelper exists to provide a way to do clean-up and optimization tasks as
     * well as health related functions at regular intervals
     */
    private final Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            //TODO anything periodic can be done here
            if (handler != null)
                handler.postDelayed(this, HELPER_INTERVAL);
        }
    };

    private void startManet() {
        manet.start();
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent != null) {
            synchronized (this) {
                String action = intent.getAction();
                if (action != null) {
                    if (action.equalsIgnoreCase(ACTION_STOP)) {
                        Log.d(Config.TAG, "Shutting down the SqANService");
                        requestShutdown(intent.getBooleanExtra(EXTRA_KEEP_ACTIVITY,false));
                        return START_NOT_STICKY;
                    } else if (action.equalsIgnoreCase(NetworkUtil.INTENT_CONNECTIVITY_CHANGED) || action.equalsIgnoreCase(NetworkUtil.INTENT_WIFI_CHANGED)) {
                        Log.d(Config.TAG, "Connectivity changed");
                        return START_STICKY;
                    }
                } else {
                    //acquireWakeLock();
                    //TODO
                    //releaseWakeLock();
                }
                return START_STICKY;
            }
        }
        return START_STICKY;
    }

    public void acquireWakeLock() {
        try {
            wakeLock.acquire(2500l);
        } catch (RuntimeException e) {
            Log.d(Config.TAG, "Cannot aquire wakeLock");
        }
    }

    public void releaseWakeLock() {
        if (wakeLock == null)
            return;
        try {
            if (wakeLock.isHeld())
                wakeLock.release();
        } catch (RuntimeException e) {
            Log.d(Config.TAG, "Cannot release wake lock");
        }
    }

    public void requestShutdown(boolean keepActivity) {
        releaseWakeLock();
        if (!keepActivity && (listener != null) && (listener instanceof Activity)) {
            try {
                ((Activity)listener).finish();
            } catch (Exception e) {
            }
        }
        if (handler == null) {
            shutdown();
            stopSelf();
        } else {
            handler.post(() -> {
                shutdown();
                stopSelf();
            });
        }
    }

    private void shutdown() {
        manet.shutdown();
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            //notificationManager.cancel(SQAN_NOTIFICATION_ID);
            notificationManager.cancelAll();
        }
        if (handler != null) {
            Log.d(Config.TAG,"SqAnService removing periodicHelper callback");
            handler.removeCallbacks(periodicHelper);
            handler = null;
        }
        if (alarmManager != null) {
            /*if (pendingIntentCommsRetry != null) {
                Log.d(Config.TAG,"SqAnService removing pendingIntentCommsRetry alarm");
                alarmManager.cancel(pendingIntentCommsRetry);
                pendingIntentCommsRetry = null;
            }*/
            alarmManager = null;
        }

        try {
            if (bootReceiver != null)
                unregisterReceiver(bootReceiver);
            bootReceiver = null;
            if (connectivityReceiver != null)
                unregisterReceiver(connectivityReceiver);
            connectivityReceiver = null;
            if (powerReceiver != null)
                unregisterReceiver(powerReceiver);
            powerReceiver = null;
        } catch (IllegalArgumentException ignore) {
        }
        thisService = null;
    }

    public void onStatusChange(Status status, String error) {
        handler.post(() -> {
            notifyStatusChange(status,error);
        });
    }

    public void notifyStatusChange(Status status, String message) {
        if (StatusHelper.isNotificationWarranted(lastNotifiedStatus, status)) {
            lastNotifiedStatus = status;
            createNotificationChannel();
            PendingIntent pendingIntent = null;
            try {
                Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqan.ui.MainActivity"));
                pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
            Notification.Builder builder;
            builder = new Notification.Builder(this,NOTIFICATION_CHANNEL);
            builder.setContentIntent(pendingIntent);
            builder.setContentTitle(getString(R.string.notify_status_title,StatusHelper.getName(status)));
            if (message == null)
                builder.setTicker("Test notification"); //TODO
            else
                builder.setTicker(message);
            builder.setSmallIcon(R.drawable.ic_notification);
            //builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setAutoCancel(true);

            Intent intentAction = new Intent(this, SqAnService.class);
            intentAction.setAction(ACTION_STOP);
            PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentAction, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.icon_nofity_power_off, getString(R.string.turn_off), pIntentShutdown);

            startForeground(SQAN_NOTIFICATION_ID,builder.build());
        }
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

    public void setListener(SqAnStatusListener listener) { this.listener = listener; }

    public class SqAnServiceBinder extends Binder {
        public SqAnService getService() {
            return SqAnService.this;
        }
    }
}
