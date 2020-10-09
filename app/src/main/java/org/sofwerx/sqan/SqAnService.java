package org.sofwerx.sqan;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Location;
import android.net.VpnService;
import android.os.BatteryManager;
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

import org.sofwerx.sqan.listeners.PeripheralStatusListener;
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
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.receivers.BootReceiver;
import org.sofwerx.sqan.receivers.ConnectivityReceiver;
import org.sofwerx.sqan.receivers.PowerReceiver;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.vpn.SqAnVpnService;
import org.sofwerx.sqandr.util.ContinuityGapSAR;

import java.io.StringWriter;
import java.util.ArrayList;

/**
 * SqAnService is the main service that keeps SqAN running and coordinates all other actions
 */
public class SqAnService extends Service implements LocationService.LocationUpdateListener {
    public static final int REQUEST_ENABLE_VPN = 421;
    private final static long CLEANUP_DELAY = 1000l * 1l; //time to wait before completing shutdown
    private final static long MAX_INTERVAL_BETWEEN_COMMS = 1000l * 7l;
    private final static long INTERVAL_BETWEEN_DEVICES_CLEANUP = 1000l * 15l;
    private final static long INTERVAL_BETWEEN_HEALTH_CHECK = 1000l * 60l;
    private final static long MIN_TIME_BETWEEN_HEARTBEATS = 1000l * 1l;
    private final static long MAX_TIME_BETWEEN_HEARTBEATS = 1000l * 7l;
    public final static String ACTION_STOP = "STOP";
    public final static String EXTRA_KEEP_ACTIVITY = "keepActivity";
    private final static int SQAN_NOTIFICATION_ID = 60;
    private final static int SQAN_NOTIFICATION_VPN_ACTIVITY_BUT_NOT_ON = 71;
    private final static long HELPER_INTERVAL = 1000l * 5l;
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
    private Status lastNotifiedStatus = Status.OFF; //the last status provided in a notification (used to prevent the notifications from firing multiple times when there is no meaningful status change)
    private int numDevicesInLastNotification = 0;
    private long lastPositiveOutgoingComms = Long.MIN_VALUE;
    private int lastHeartbeatLevel = 0;
    private long nextDevicesCleanup = Long.MIN_VALUE;
    private long nextAvailableHeartbeat = Long.MIN_VALUE; //prevent multiple heartbeats from firing in close succession
    private long nextMandatoryHeartbeat = Long.MIN_VALUE;
    private long nextHealthCheck = Long.MIN_VALUE;
    private LocationService locationService;
    private SqAnVpnService vpnService;
    private int missedVpnPacketCount = 0;
    private long nextNoiseNotificationWindow = Long.MIN_VALUE;
    private final static long TIME_BETWEEN_NOISE_NOTIFICATIONS = 1000l * 60l * 5l;

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

    public boolean isWiFiManetAvailable() {
        if (manetOps == null)
            return false;
        return manetOps.isWiFiManetAvailable();
    }

    public boolean isBtManetAvailable() {
        if (manetOps == null)
            return false;
        return manetOps.isBtManetAvailable();
    }

    public boolean isSdrManetAvailable() {
        if (manetOps == null)
            return false;
        return manetOps.isSdrManetAvailable();
    }

    public boolean isSdrManetActive() {
        if (manetOps == null)
            return false;
        return manetOps.isSdrManetActive();
    }

    public static void burstVia(AbstractPacket packet, TransportPreference transportPreference) {
        if ((thisService == null) || (packet == null) || (thisService.manetOps == null) || (transportPreference == null))
            return;
        thisService.burst(packet,transportPreference);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thisService = this;
        CommsLog.init(this);
        Config.init(this);
        manetOps = new ManetOps(this);
        ExceptionHelper.set();
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
        CommsLog.close();
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
                SqAnDevice.dedup();
                checkForStaleDevices();
            }
            if (System.currentTimeMillis() > nextHealthCheck)
                checkOverallHealth();
            if (handler != null)
                handler.postDelayed(this, HELPER_INTERVAL);
        }
    };

    /**
     * This looks at the device's overall health
     */
    private void checkOverallHealth() {
        nextHealthCheck = System.currentTimeMillis() + INTERVAL_BETWEEN_HEALTH_CHECK;

        StringWriter out = new StringWriter();
        out.append("Health: ");
        //check memory
        try {
            final Runtime runtime = Runtime.getRuntime();
            final float freeSize = ((float)runtime.freeMemory())/1048576f;
            final float totalSize = ((float)runtime.totalMemory())/1048576f;
            final int freePercent = (int)(100l*freeSize/totalSize);
            out.append("App memory usage: "+String.format("%.1f",totalSize-freeSize)+"mB out of "+String.format("%.1f",totalSize)+"mB allocated ("+freePercent+"% free)");
        } catch (Exception ignore) {
        }

        //check battery
        try {
            Intent batteryStatus = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            boolean isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            int batteryPct = 100*level/scale;
            out.append("; Battery at "+batteryPct+"%"+(isCharging?" (charging) ":""));
        } catch (Exception ignore) {
        }

        //report connections
        out.append("; "+SqAnDevice.getActiveConnections()+" active connections");

        CommsLog.log(CommsLog.Entry.Category.STATUS,out.toString());

        //TODO add more checks here
        //TODO make any adjustments to the meshes based on health
    }

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
            if ((manetOps.getWifiManet() == null) && (manetOps.getBtManet() == null) && (manetOps.getSdrManet() == null)) {
                onIssueDetected(new SqAnAppIssue(true, "No MANET selected"));
                systemReady = false;
            } else {
                boolean issuesBt;
                if (manetOps.getBtManet() != null)
                    issuesBt = !manetOps.getBtManet().checkForSystemIssues();
                else
                    issuesBt = false;
                boolean issuesWiFi;
                if (manetOps.getWifiManet() != null)
                    issuesWiFi = !manetOps.getWifiManet().checkForSystemIssues();
                else
                    issuesWiFi = false;
                boolean issuesSDR;
                if (manetOps.getSdrManet() != null)
                    issuesSDR = !manetOps.getSdrManet().checkForSystemIssues();
                else
                    issuesSDR = false;

                systemReady = !issuesBt && !issuesWiFi && !issuesSDR;
            }
        }

        if (thisService != null) {
            if (thisService.listener != null)
                thisService.listener.onSystemReady(systemReady);
        }

        return systemReady;
    }

    public boolean burst(final AbstractPacket packet) {
        return burst(packet, TransportPreference.AGNOSTIC);
    }

    /**
     * Sends a packet over the MANET
     * @param packet packet to send
     * @return true == attempting to send; false = unable to send (MANET not ready)
     */
    public boolean burst(final AbstractPacket packet, final TransportPreference preferredTransport) {
        if ((packet == null) || (manetOps == null) || !StatusHelper.isActive(manetOps.getStatus())) {
            Log.d(Config.TAG, "Unable to burst packet: " + ((packet == null) ? "null packet" : "MANET is not active"));
            return false;
        }
        if (handler == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"SqAnService handler is not ready yet; sending burst directly to ManetOps");
            manetOps.burst(packet,preferredTransport);
        } else
            handler.post(() -> manetOps.burst(packet,preferredTransport));
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
        if (thisService != null) {
            thisService.requestShutdown(keepActivity);
            thisService = null;
        }
    }

    public void requestShutdown(boolean keepActivity) {
        if (!keepActivity && (listener != null) && (listener instanceof Activity)) {
            try {
                ((Activity)listener).finish();
            } catch (Exception e) {
            }
        }
        if (handler == null)
            shutdown();
        else {
            handler.post(() -> {
                shutdown();
            });
        }
    }

    private void shutdown() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"SqAnService shutdown initiated");
        releaseWakeLock();
        SqAnVpnService.stop(this);
        vpnService = null;
        CommsLog.clear();
        Config.savePrefs(this);
        if (manetOps != null) {
            manetOps.shutdown();
            manetOps = null;
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
        if (handler != null) {
            handler.removeCallbacks(periodicHelper);
            Log.d(Config.TAG,"SqAnService removing periodicHelper callback");
            handler.postDelayed(() -> {
                clearServiceAndNotifications();
                handler = null;
            },CLEANUP_DELAY); //let the MANET clean-up before completing shutdown
        } else
            clearServiceAndNotifications();
        SqAnService.thisService = null;
    }

    private void clearServiceAndNotifications() {
        Log.d(Config.TAG,"Clearing notifications and foreground service");
        try {
            stopForeground(true);
        } catch (Exception ignore) {
        }
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            notificationManager.cancelAll();
        }
        thisService = null;
        SqAnDevice.clearAllDevices(null);
        Log.d(Config.TAG,"Shutdown complete. Stopping....");
        stopSelf();
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
            if (listener != null)
                listener.onStatus(status);
        }
    }

    public Status getStatus() { return lastNotifiedStatus; }

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

    /**
     * Does this MANET only use SDRs
     * @return
     */
    public boolean isOnlySdr() {
        if (manetOps != null) {
            if (manetOps.isSdrManetSelected())
                return !manetOps.isBtManetSelected() && !manetOps.isWiFiDirectManetSelected() && !manetOps.isWiFiAwareManetSelected();
        }
        return false;
    }

    public void handleHighNoise(float snr) {
        if (System.currentTimeMillis() > nextNoiseNotificationWindow) {
            nextNoiseNotificationWindow = System.currentTimeMillis() + TIME_BETWEEN_NOISE_NOTIFICATIONS;
            if (listener != null)
                listener.onHighNoise(snr);
            notifyStatusChange("SqAN is receiving an unusually large amount of corrupted data. Check connections and RF environment.");
        }
    }

    public class SqAnServiceBinder extends Binder {
        public SqAnService getService() {
            return SqAnService.this;
        }
    }

    public ManetOps getManetOps() { return manetOps; }

    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        if (manetOps != null)
            manetOps.setPeripheralStatusListener(listener);
    }
}
