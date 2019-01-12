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

import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PingPacket;
import org.sofwerx.sqan.receivers.BootReceiver;
import org.sofwerx.sqan.receivers.ConnectivityReceiver;
import org.sofwerx.sqan.receivers.PowerReceiver;
import org.sofwerx.sqan.util.CommsLog;
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
    //private boolean foregroundLaunched = false;
    private int numDevicesInLastNotification = 0;

    //FIXME build an intent receiver to send/receiove ChannelBytePackets as a way to use SqAN over IPC

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
            Log.d(Config.TAG,"PeriodicHelper executing");
            checkForStaleDevices();
            manet.executePeriodicTasks();
            //TODO anything periodic can be done here
            requestHeartbeat();
            if (handler != null)
                handler.postDelayed(this, HELPER_INTERVAL);
        }
    };

    public void requestHeartbeat() {
        Log.d(Config.TAG,"SqAnService.requestHeartbeat()");
        //TODO temporarily using the ping request instead of a proper heartbeat
        //TODO burst(new HeartbeatPacket());
        burst(new PingPacket());
    }

    /**
     * Sends a packet over the MANET
     * @param packet packet to send
     * @return true == attempting to send; false = unable to send (MANET not ready)
     */
    public boolean burst(final AbstractPacket packet) {
        if ((packet == null) || !StatusHelper.isActive(manet.getStatus())) {
            Log.i(Config.TAG, "Unable to burst packet: " + ((packet == null) ? "null packet" : "MANET is not active"));
            return false;
        }
        if (handler == null) {
            Log.d(Config.TAG, "Sending burst directly to ManetOps (bypassing SqAnService handler)");
            manet.burst(packet);
        } else {
            handler.post(() -> {
                Log.d(Config.TAG, "Sending burst to ManetOps");
                manet.burst(packet);
            });
        }
        return true;
    }

    private void checkForStaleDevices() {
        if (StatusHelper.isActive(manet.getStatus()) && (SqAnDevice.getActiveConnections() != numDevicesInLastNotification))
            notifyStatusChange(Status.CHANGING_MEMBERSHIP,null);

        //TODO actually do something other than show their stale status
        if (listener != null)
            listener.onNodesChanged(null);
    }

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
            SqAnDevice.clearAllDevices(null);
        } else {
            handler.post(() -> {
                shutdown();
                stopSelf();
                SqAnDevice.clearAllDevices(null);
            });
        }
        CommsLog.clear();
    }

    private void shutdown() {
        manet.shutdown();
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        //foregroundLaunched = false;
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

    public void onStatusChange(final Status status, final String error) {
        if (handler != null) {
            handler.post(() -> {
                notifyStatusChange(status, error);
            });
            if (listener != null)
                listener.onStatus(status);
        }
    }

    public void notifyStatusChange(Status status, String message) {
        //if (StatusHelper.isNotificationWarranted(lastNotifiedStatus, status) || ((status != Status.OFF) && !foregroundLaunched)) {
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
            if (StatusHelper.isActive(status)) {
                builder.setContentTitle("SqAN is Active");
                builder.setSmallIcon(R.drawable.ic_notification);
                numDevicesInLastNotification = SqAnDevice.getActiveConnections();
                builder.setContentText("Connected to "+numDevicesInLastNotification+((numDevicesInLastNotification == 1)?" device":" devices"));
            } else {
                builder.setContentTitle(getString(R.string.notify_status_title, StatusHelper.getName(status)));
                builder.setSmallIcon(R.drawable.ic_notification_down);
                if (message == null)
                    builder.setTicker("Squad Area Network");
                else
                    builder.setTicker(message);
            }
            //builder.setPriority(Notification.PRIORITY_HIGH);
            builder.setAutoCancel(true);

            Intent intentAction = new Intent(this, SqAnService.class);
            intentAction.setAction(ACTION_STOP);
            PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentAction, PendingIntent.FLAG_UPDATE_CURRENT);
            builder.addAction(R.drawable.icon_nofity_power_off, getString(R.string.turn_off), pIntentShutdown);

            //foregroundLaunched = true;
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
            importance = NotificationManager.IMPORTANCE_LOW;

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

    public ManetOps getManetOps() { return manet; }
}
