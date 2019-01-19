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
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;
import org.sofwerx.sqan.manet.common.issues.SqAnAppIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PingPacket;
import org.sofwerx.sqan.receivers.BootReceiver;
import org.sofwerx.sqan.receivers.ConnectivityReceiver;
import org.sofwerx.sqan.receivers.PowerReceiver;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetworkUtil;

import java.util.ArrayList;

/**
 * SqAnService is the main service that keeps SqAN running and coordinates all other actions
 */
public class SqAnService extends Service {
    public final static String ACTION_STOP = "STOP";
    public final static String EXTRA_KEEP_ACTIVITY = "keepActivity";
    private final static int SQAN_NOTIFICATION_ID = 60;
    private long HELPER_INTERVAL = 1000l * 10l;
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
    private ManetOps manetOps;
    private Status lastNotifiedStatus = Status.ERROR; //the last status provided in a notification (used to prevent the notifications from firing multiple times when there is no meaningful status change)
    private int numDevicesInLastNotification = 0;
    private long lastPositiveOutgoingComms = Long.MIN_VALUE;
    private int lastHeartbeatLevel = 0;
    private long lastDevicesCleanup = Long.MIN_VALUE;

    private final static long MAX_INTERVAL_BETWEEN_COMMS = 1000l * 15l;
    private final static long INTERVAL_BETWEEN_DEVICES_CLEANUP = 1000l * 15l;

    private static ArrayList<AbstractManetIssue> issues = null; //issues currently blocking or degrading the MANET

    public static ArrayList<AbstractManetIssue> getIssues() {
        return issues;
    }

    //FIXME build an intent receiver to send/receive ChannelBytePackets as a way to use SqAN over IPC

    @Override
    public void onCreate() {
        super.onCreate();
        thisService = this;
        Config.init(this);
        manetOps = new ManetOps(this);
        ExceptionHelper.set(getApplicationContext());
        alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sqan:SqAnService");
        handler = new Handler();
        handler.postDelayed(periodicHelper,HELPER_INTERVAL);
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
            checkForStaleDevices();
            manetOps.executePeriodicTasks();
            requestHeartbeat();
            if (System.currentTimeMillis() > lastDevicesCleanup) {
                lastDevicesCleanup = System.currentTimeMillis();
                SqAnDevice mergedDevice = SqAnDevice.dedup();
                if ((mergedDevice != null) && (listener != null))
                    listener.onNodesChanged(mergedDevice);
            }
            if (handler != null)
                handler.postDelayed(this, HELPER_INTERVAL);
        }
    };

    public void onPositiveComms() {
        lastPositiveOutgoingComms = System.currentTimeMillis();
    }

    public void requestHeartbeat() {
        if (System.currentTimeMillis() > lastPositiveOutgoingComms + MAX_INTERVAL_BETWEEN_COMMS) {
            //TODO make some more sophisticated needs-based method rather than just cycling through different types of heartbeats
            if (lastHeartbeatLevel == 0)
                burst(new HeartbeatPacket(Config.getThisDevice(),HeartbeatPacket.DetailLevel.MEDIUM));
            else if (lastHeartbeatLevel == 1)
                burst(new PingPacket(Config.getThisDevice().getUUID()));
            else
                burst(new HeartbeatPacket(Config.getThisDevice(),HeartbeatPacket.DetailLevel.BASIC));
            lastHeartbeatLevel++;
            if (lastHeartbeatLevel > 0)
                lastHeartbeatLevel = 0;
        } else
            Log.d(Config.TAG,"SqAnService.requestHeartbeat(), but no heartbeat needed right now");
    }

    public static void onIssueDetected(AbstractManetIssue issue) {
        if (issue != null) {
            if (issues == null)
                issues = new ArrayList<>();
            issues.add(issue);
            if (issue.isBlocker())
                Log.e(Config.TAG,"New issue blocking MANET: "+issue.toString());
            else
                Log.w(Config.TAG,"New issue degrading MANET: "+issue.toString());
        }
    }

    /**
     * Is there a system issue currently effecting the performance of the MANET
     * (i.e. like the device memory is low)
     * @return true == issue present
     */
    public static boolean hasSystemIssues() {
        return (issues != null) && !issues.isEmpty();
    }

    /**
     * Is there a system issue currently preventing the MANET from operating at all
     * (i.e. like there is no WiFi capability)
     * @return true == the MANET cannot function
     */
    public static boolean hasBlockerSystemIssues() {
        if ((issues != null) && !issues.isEmpty()) {
            for (AbstractManetIssue issue:issues) {
                if (issue.isBlocker())
                    return true;
            }
        }
        return false;
    }

    /**
     * Checks the system to see if all required settings are in place to operate the MANET
     */
    public static boolean checkSystemReadiness() {
        boolean systemReady = true;
        issues = null;

        ManetOps manetOps;
        if (thisService == null)
            manetOps = null;
        else
            manetOps = thisService.manetOps;

        if (manetOps == null) {
            onIssueDetected(new SqAnAppIssue(true,"ManetOps is null"));
            systemReady = false;
        } else {
            if (manetOps.getManet() == null) {
                onIssueDetected(new SqAnAppIssue(true, "No MANET selected"));
                systemReady = false;
            } else
                systemReady = manetOps.getManet().checkForSystemIssues();
        }

        if (thisService != null) {
            if (thisService.listener != null)
                thisService.listener.onSystemReady(systemReady);
        }

        return systemReady;
    }

    /**
     * Sends a packet over the MANET
     * @param packet packet to send
     * @return true == attempting to send; false = unable to send (MANET not ready)
     */
    public boolean burst(final AbstractPacket packet) {
        if ((packet == null) || !StatusHelper.isActive(manetOps.getStatus())) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to burst packet: " + ((packet == null) ? "null packet" : "MANET is not active"));
            return false;
        }
        if (handler == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"SqAnService handler is not ready yet; sending burst directly to ManetOps");
            manetOps.burst(packet);
        } else
            handler.post(() -> manetOps.burst(packet));
        return true;
    }

    private void checkForStaleDevices() {
        if (StatusHelper.isActive(manetOps.getStatus()) && (SqAnDevice.getActiveConnections() != numDevicesInLastNotification))
            notifyStatusChange(Status.CHANGING_MEMBERSHIP,null);

        //TODO actually do something other than show their stale status
        if (listener != null)
            listener.onNodesChanged(null);
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
            Log.d(Config.TAG, "Cannot acquire wakeLock");
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
        CommsLog.clear();
        if (handler == null) {
            shutdown();
            SqAnDevice.clearAllDevices(null);
            stopSelf();
        } else {
            handler.post(() -> {
                shutdown();
                SqAnDevice.clearAllDevices(null);
                stopSelf();
            });
        }
    }

    private void shutdown() {
        manetOps.shutdown();
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
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

    public void notifyStatusChange(String message) {
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
        if (StatusHelper.isActive(lastNotifiedStatus)) {
            numDevicesInLastNotification = SqAnDevice.getActiveConnections();
            if (numDevicesInLastNotification == 0) {
                builder.setContentTitle("SqAN is Waiting");
                builder.setSmallIcon(R.drawable.ic_notifiction_none);
                builder.setContentText("Searching for other nodes...");
            } else {
                builder.setContentTitle("SqAN is Active");
                builder.setSmallIcon(R.drawable.ic_notification);
                builder.setContentText("Connected to "+numDevicesInLastNotification+((numDevicesInLastNotification == 1)?" device":" devices"));
            }
        } else {
            builder.setContentTitle(getString(R.string.notify_status_title, StatusHelper.getName(lastNotifiedStatus)));
            builder.setSmallIcon(R.drawable.ic_notification_down);
            if (message == null)
                builder.setTicker("Squad Area Network");
            else
                builder.setTicker(message);
        }
        builder.setAutoCancel(true);

        Intent intentAction = new Intent(this, SqAnService.class);
        intentAction.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentAction, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.icon_nofity_power_off, getString(R.string.turn_off), pIntentShutdown);

        startForeground(SQAN_NOTIFICATION_ID,builder.build());
    }

    public void notifyStatusChange(Status status, String message) {
        if (StatusHelper.isNotificationWarranted(lastNotifiedStatus, status)) {
            lastNotifiedStatus = status;
            notifyStatusChange(message);
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

    public ManetOps getManetOps() { return manetOps; }
}
