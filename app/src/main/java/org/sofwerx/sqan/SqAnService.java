package org.sofwerx.sqan;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.location.Location;
import android.net.VpnService;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import org.sofwerx.sqan.ipc.IpcSaBroadcastTransmitter;
import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;
import org.sofwerx.sqan.manet.common.issues.SqAnAppIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.VpnPacket;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;
import org.sofwerx.sqan.receivers.BootReceiver;
import org.sofwerx.sqan.receivers.ConnectivityReceiver;
import org.sofwerx.sqan.receivers.PowerReceiver;
import org.sofwerx.sqan.ui.MainActivity;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.vpn.SqAnVpnService;

import java.util.ArrayList;
import java.util.Random;

import static android.app.Activity.RESULT_OK;

/**
 * SqAnService is the main service that keeps SqAN running and coordinates all other actions
 */
public class SqAnService extends Service implements LocationService.LocationUpdateListener {
    public static final int REQUEST_ENABLE_VPN = 421;
    private final static long MIN_TIME_BETWEEN_HEARTBEATS = 1000l * 2l;
    private final static long MAX_TIME_BETWEEN_HEARTBEATS = 1000l * 50l;
    public final static String ACTION_STOP = "STOP";
    public final static String EXTRA_KEEP_ACTIVITY = "keepActivity";
    private final static int SQAN_NOTIFICATION_ID = 60;
    private final static int SQAN_NOTIFICATION_VPN_ACTIVITY_BUT_NOT_ON = 71;
    private final static long HELPER_INTERVAL = 1000l * 5l;
    private final static String NOTIFICATION_CHANNEL = "sqan_notify";
    private Random random = new Random();

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
    private long nextDevicesCleanup = Long.MIN_VALUE;
    private long nextAvailableHeartbeat = Long.MIN_VALUE; //prevent multiple heartbeats from firing in close succession
    private long nextMandatoryHeartbeat = Long.MIN_VALUE;
    private LocationService locationService;
    private SqAnVpnService vpnService;
    private int missedVpnPacketCount = 0;

    private final static long MAX_INTERVAL_BETWEEN_COMMS = 1000l * 7l;
    private final static long INTERVAL_BETWEEN_DEVICES_CLEANUP = 1000l * 15l;

    private static ArrayList<AbstractManetIssue> issues = null; //issues currently blocking or degrading the MANET

    public static ArrayList<AbstractManetIssue> getIssues() {
        return issues;
    }

    public static void clearIssue(AbstractManetIssue issue) {
        if ((issue == null) || (issues == null) || issues.isEmpty())
            return;
        if (issue instanceof WiFiInUseIssue) {
            for (AbstractManetIssue current:issues) {
                if (current instanceof WiFiInUseIssue)
                    issues.remove(current);
            }
        }
    }

    public static SqAnService getInstance() { return thisService; }

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
        locationService = new LocationService(this);
        locationService.start();
        handler = new Handler();
        handler.postDelayed(periodicHelper,HELPER_INTERVAL);
        if (Config.isVpnEnabled()) {
            Intent intent = VpnService.prepare(this);
            if (intent == null)
                SqAnVpnService.start(this);
        }
    }

    @Override
    public void onDestroy() {
        if (locationService != null) {
            locationService.shutdown();
            locationService = null;
        }
        super.onDestroy();
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
            manetOps.executePeriodicTasks();
            requestHeartbeat();
            if (System.currentTimeMillis() > nextDevicesCleanup) {
                nextDevicesCleanup = System.currentTimeMillis() + INTERVAL_BETWEEN_DEVICES_CLEANUP;
                SqAnDevice mergedDevice = SqAnDevice.dedup();
                checkForStaleDevices();
            }
            if (handler != null)
                handler.postDelayed(this, HELPER_INTERVAL);
        }
    };

    public void onPositiveComms() {
        lastPositiveOutgoingComms = System.currentTimeMillis();
    }

    public void requestHeartbeat() { requestHeartbeat(false); }

    public void requestHeartbeat(boolean force) {
        if (force) {
            burst(new HeartbeatPacket(Config.getThisDevice(),HeartbeatPacket.DetailLevel.MEDIUM));
            return;
        }
        if ((System.currentTimeMillis() > lastPositiveOutgoingComms + MAX_INTERVAL_BETWEEN_COMMS) || (System.currentTimeMillis() > nextMandatoryHeartbeat)) {
            if (System.currentTimeMillis() < nextAvailableHeartbeat) {
                Log.d(Config.TAG,"A heartbeat was just requested so this heartbeat request is being skipped.");
                return;
            }
            nextAvailableHeartbeat = System.currentTimeMillis() + MIN_TIME_BETWEEN_HEARTBEATS;
            nextMandatoryHeartbeat = System.currentTimeMillis() + MAX_TIME_BETWEEN_HEARTBEATS;
            //if (lastHeartbeatLevel == 0)
                burst(new HeartbeatPacket(Config.getThisDevice(),HeartbeatPacket.DetailLevel.MEDIUM));
            /*if (lastHeartbeatLevel == 1) {
                //FIXME PingPackets arent working
                //ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
                //if ((devices != null) && !devices.isEmpty()) {
                //    //randomly distribute ping requests
                //    int index = random.nextInt(devices.size());
                //    SqAnDevice device = devices.get(index);
                //    if (device.isActive())
                //        burst(new PingPacket(Config.getThisDevice().getUUID(),device.getUUID()));
                //}
            } */
            //else
            //    burst(new HeartbeatPacket(Config.getThisDevice(),HeartbeatPacket.DetailLevel.BASIC));
            lastHeartbeatLevel++;
            if (lastHeartbeatLevel > 2)
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
            Log.d(Config.TAG, "Unable to burst packet: " + ((packet == null) ? "null packet" : "MANET is not active"));
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
        if (SqAnDevice.getActiveConnections() != numDevicesInLastNotification) {
            if (StatusHelper.isActive(manetOps.getStatus()))
                notifyStatusChange(Status.CHANGING_MEMBERSHIP, null);
            else
                notifyStatusChange(null);
            checkForCollisions();
        }

        if (listener != null)
            listener.onNodesChanged(null);
    }

    private void checkForCollisions() {
        SqAnDevice conflictingDevice = Config.getThisDevice().getConflictingDevice();
        if (conflictingDevice != null) {
            if (listener != null)
                listener.onConflict(conflictingDevice);
        }
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
                    } else if (action.equalsIgnoreCase(NetUtil.INTENT_CONNECTIVITY_CHANGED) || action.equalsIgnoreCase(NetUtil.INTENT_WIFI_CHANGED)) {
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

    public static void shutdown(boolean keepActivity) {
        if (thisService != null)
            thisService.requestShutdown(keepActivity);
    }

    public void requestShutdown(boolean keepActivity) {
        releaseWakeLock();
        if (!keepActivity && (listener != null) && (listener instanceof Activity)) {
            try {
                ((Activity)listener).finish();
            } catch (Exception e) {
            }
        }
        SqAnVpnService.stop(this);
        CommsLog.clear();
        Config.savePrefs(this);
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
        SqAnDevice device = Config.getThisDevice();
        if (device != null) {
            device.setRoleWiFi(SqAnDevice.NodeRole.OFF);
            device.setRoleBT(SqAnDevice.NodeRole.OFF);
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
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this,NOTIFICATION_CHANNEL);
        else
            builder = new Notification.Builder(this);
        if (LocationService.isLocationEnabled(this)) {
            PendingIntent pendingIntent = null;
            try {
                Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqan.ui.MainActivity"));
                pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }
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
                    builder.setContentText("Connected to " + numDevicesInLastNotification + ((numDevicesInLastNotification == 1) ? " device" : " devices"));
                }
            } else {
                builder.setContentTitle(getString(R.string.notify_status_title, StatusHelper.getName(lastNotifiedStatus)));
                builder.setSmallIcon(R.drawable.ic_notification_down);
                if (message == null)
                    builder.setTicker("Squad Area Network");
                else
                    builder.setTicker(message);
            }
        } else {
            PendingIntent pendingIntent = null;
            Intent notificationIntent = new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS);
            builder.setContentIntent(PendingIntent.getActivity(this, 0, notificationIntent, 0));
            builder.setContentTitle("SqAN needs location");
            builder.setSmallIcon(R.drawable.ic_notifiction_loc_disabled);
            builder.setContentText("SqAN needs location services to be turned on...");
            builder.setTicker("SqAN needs location");
        }

        builder.setAutoCancel(true);
        Intent intentAction = new Intent(this, SqAnService.class);
        intentAction.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentAction, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.icon_nofity_power_off, getString(R.string.turn_off), pIntentShutdown);

        startForeground(SQAN_NOTIFICATION_ID,builder.build());
    }

    public void notifyVpnTraffic(SqAnDevice device) {
        createNotificationChannel();
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this,NOTIFICATION_CHANNEL);
        else
            builder = new Notification.Builder(this);
        PendingIntent pendingIntent = null;
        try {
            Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqan.ui.SettingsActivity"));
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        builder.setContentIntent(pendingIntent);
        builder.setContentTitle("VPN Traffic Detected");
        builder.setSmallIcon(R.drawable.ic_vpn);
        String text;
        if ((device == null) || (device.getCallsign() == null))
            text = "SqAN is sending you VPN traffic, but you do not currently have VPN enabled";
        else
            text = device.getCallsign()+" is sending you VPN traffic, but you do not currently have VPN enabled";

        builder.setContentText(text);

        builder.setAutoCancel(true);

        Intent intentAction = new Intent(this, SqAnVpnService.class);
        intentAction.setAction(SqAnVpnService.ACTION_CONNECT);
        PendingIntent pIntentVpn = PendingIntent.getService(this, 0, intentAction, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(R.drawable.ic_vpn, "Turn on VPN", pIntentVpn);

        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        notificationManager.notify(SQAN_NOTIFICATION_VPN_ACTIVITY_BUT_NOT_ON, builder.build());
    }

    public void notifyStatusChange(Status status, String message) {
        if (StatusHelper.isNotificationWarranted(lastNotifiedStatus, status)) {
            lastNotifiedStatus = status;
            notifyStatusChange(message);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (channel == null) {
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
    }

    public void setListener(SqAnStatusListener listener) { this.listener = listener; }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null)
            Config.getThisDevice().setLastLocation(new SpaceTime(location));
    }

    public void onRxVpnPacket(VpnPacket packet) {
        if (packet != null) {
            if (vpnService != null)
                vpnService.onReceived(packet.getData());
            else {
                missedVpnPacketCount++;
                if (missedVpnPacketCount == 2)
                    notifyVpnTraffic(SqAnDevice.findByUUID(packet.getOrigin()));
            }
        }
    }

    public void setVpnService(SqAnVpnService sqAnVpnService) {
        vpnService = sqAnVpnService;
    }

    public class SqAnServiceBinder extends Binder {
        public SqAnService getService() {
            return SqAnService.this;
        }
    }

    public ManetOps getManetOps() { return manetOps; }
}
