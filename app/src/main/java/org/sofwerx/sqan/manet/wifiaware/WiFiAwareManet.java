package org.sofwerx.sqan.manet.wifiaware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.IdentityChangedListener;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.NetUtil;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 *
 * FIXME WiFiAwareManager is showing as Not Available for an unknown reason
 *
 *  TODO add support for Out of Band (OOB) discovery
 */
public class WiFiAwareManet extends AbstractManet {
    private static final String SERVICE_ID = "sqan";
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private WifiAwareManager wifiAwareManager;
    private BroadcastReceiver hardwareStatusReceiver;
    private final AttachCallback attachCallback;
    private final IdentityChangedListener identityChangedListener;
    private WifiAwareSession awareSession;
    private final PublishConfig configPub;
    private final SubscribeConfig configSub;
    private static final long INTERVAL_LISTEN_BEFORE_PUBLISH = 1000l * 60l; //amount of time to listen for an existing hub before assuming the hub role
    private Role role = Role.NONE;
    private DiscoverySession discoverySession;
    private HashMap<PeerHandle,Long> nodes = new HashMap<>();

    private enum Role {HUB, SPOKE, NONE}

    public WiFiAwareManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        wifiAwareManager = null;
        hardwareStatusReceiver = null;
        identityChangedListener = new IdentityChangedListener() {
            @Override
            public void onIdentityChanged(byte[] mac) { onMacChanged(mac); }
        };
        discoverySession = null;
        configPub = new PublishConfig.Builder()
                .setServiceName(SERVICE_ID)
                .build();
        configSub = new SubscribeConfig.Builder()
                .setServiceName(SERVICE_ID)
                .build();
        attachCallback = new AttachCallback() {
            @Override
            public void onAttached(WifiAwareSession session) {
                Log.d(Config.TAG,"onAttached(session)");
                awareSession = session;
                isRunning = true;
                findOrCreateHub();
            }

            @Override
            public void onAttachFailed() {
                Log.e(Config.TAG,"unable to attach to WiFiAware manager");
                setStatus(Status.ERROR);
                wifiAwareManager = null;
                isRunning = false;
            }
        };
    }

    @Override
    public ManetType getType() { return ManetType.WIFI_AWARE; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            SqAnService.onIssueDetected(new WiFiIssue(true,"This device does not have WiFi Aware"));
            passed = false;
        } else {
            WifiAwareManager mngr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
            if (!mngr.isAvailable()) {
                SqAnService.onIssueDetected(new WiFiIssue(true,"WiFi Aware is supported but the system is not making it available"));
                passed = false;
            }
        }
        return passed;
    }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public void onNodeLost(SqAnDevice node) {
        //TODO
    }

    @Override
    public String getName() { return "WiFi Aware™"; }

    @Override
    public void init() throws ManetException {
        if (!isRunning) {
            isRunning = true;
            if (hardwareStatusReceiver == null) {
                hardwareStatusReceiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        onWiFiAwareStatusChanged();
                    }
                };
                IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
                context.registerReceiver(hardwareStatusReceiver, filter);
            }
            if (wifiAwareManager == null) {
                NetUtil.turnOnWiFiIfNeeded(context);
                NetUtil.forceLeaveWiFiNetworks(context); //TODO include a check to protect an active connection if its used for data backhaul
                wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                if (wifiAwareManager.isAvailable())
                    wifiAwareManager.attach(attachCallback,identityChangedListener,handler);
                else {
                    Log.e(Config.TAG,"WiFi Aware Manager is not available");
                    setStatus(Status.ERROR);
                }
            }
        }
    }

    private PeerHandle findPeer(PeerHandle peerHandle) {
        if ((peerHandle != null) && (nodes != null) && !nodes.isEmpty()) {
            if (nodes.containsKey(peerHandle))
                return peerHandle;
        }
        return null;
    }

    private PeerHandle findPeer(String uuid) {
        if ((uuid != null) && (nodes != null) && !nodes.isEmpty()) {
            try {
                int hash = Integer.parseInt(uuid);
                Iterator it = nodes.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry pair = (Map.Entry)it.next();
                    PeerHandle handle = (PeerHandle) pair.getKey();
                    if ((handle != null) && (handle.hashCode() == hash))
                        return handle;
                }
            } catch (NumberFormatException ignore) {
            }
        }
        return null;
    }

    private void updatePeer(PeerHandle peerHandle) {
        if (peerHandle == null)
            return;
        PeerHandle old = findPeer(peerHandle);
        if (old == null) {
            if (nodes == null)
                nodes = new HashMap<>();
            SqAnDevice.add(new SqAnDevice(Integer.toString(peerHandle.hashCode())));
        }
        nodes.put(peerHandle,System.currentTimeMillis());
    }

    private void startAdvertising() {
        if (discoverySession != null) {
            discoverySession.close();
            discoverySession = null;
            setStatus(Status.CHANGING_MEMBERSHIP);
        }
        if (awareSession != null) {
            setStatus(Status.ADVERTISING);
            awareSession.publish(configPub, new DiscoverySessionCallback() {
                @Override
                public void onPublishStarted(PublishDiscoverySession session) {
                    discoverySession = session;
                    role = Role.HUB;
                    nodes = new HashMap<>();
                    setStatus(Status.CONNECTED);
                    //TODO
                }

                @Override
                public void onMessageReceived(PeerHandle peerHandle, byte[] message) {
                    updatePeer(peerHandle);
                    //TODO consume the message
                }
            }, handler);
        }
    }

    private void startDiscovery() {
        if (discoverySession != null) {
            discoverySession.close();
            discoverySession = null;
            setStatus(Status.CHANGING_MEMBERSHIP);
        }
        if (awareSession != null) {
            role = Role.NONE;
            setStatus(Status.DISCOVERING);
            awareSession.subscribe(configSub, new DiscoverySessionCallback() {
                @Override
                public void onSubscribeStarted(SubscribeDiscoverySession session) {
                    discoverySession = session;
                    role = Role.SPOKE;
                    nodes = new HashMap<>();
                    setStatus(Status.CONNECTED);
                    //TODO
                }

                @Override
                public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                    updatePeer(peerHandle);
                    //TODO
                }
            }, handler);
        }
    }

    private void burst(AbstractPacket packet, PeerHandle peerHandle) {
        if (packet == null) {
            Log.d(Config.TAG,"Cannot send empty packet");
            return;
        }
        burst(packet.toByteArray(),peerHandle);
    }

    private void burst(final byte[] bytes, final PeerHandle peerHandle) {
        if (bytes == null) {
            Log.d(Config.TAG,"Cannot send empty byte array");
            return;
        }
        if (peerHandle == null) {
            Log.d(Config.TAG,"Cannot send packet to an empty PeerHandle");
            return;
        }
        if (discoverySession == null) {
            Log.d(Config.TAG,"Cannot send packet as no DiscoverySession exists");
            return;
        }
        if (bytes.length > getMaximumPacketSize()) {
            Log.d(Config.TAG,"Packet larger than WiFi Aware max; segmenting and sending");
            //TODO segment and burst
        } else
            handler.post(() -> discoverySession.sendMessage(peerHandle, 0, bytes));
    }

    @Override
    public void burst(final AbstractPacket packet) throws ManetException {
        if (handler != null) {
            handler.post(() -> {
                if ((nodes != null) && !nodes.isEmpty()) {
                    Iterator it = nodes.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry pair = (Map.Entry)it.next();
                        burst(packet,(PeerHandle) pair.getKey());
                    }
                }
            });
        }
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        if (device == null)
            burst(packet);
        else {
            PeerHandle peerHandle = findPeer(device.getUUID());
            if (peerHandle != null)
                burst(packet,peerHandle);
        }
    }

    @Override
    public void connect() throws ManetException {
        //TODO
    }

    @Override
    public void pause() throws ManetException {
        //TODO
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    /**
     * Looks for an existing hub on the network, if one isn't found, then assume that role
     */
    private void findOrCreateHub() {
        if (awareSession != null) {
            startDiscovery();
            if (handler != null) {
                handler.postDelayed(() -> {
                    if (role == Role.NONE) {
                        setStatus(Status.CHANGING_MEMBERSHIP);
                        //no hub was found, assume hub role
                        assumeHubRole();
                    }
                },INTERVAL_LISTEN_BEFORE_PUBLISH);
            }
        }
    }

    /**
     * Take over the role as the Hub for this mesh
     */
    private void assumeHubRole() {
        startAdvertising();
    }

    @Override
    public void disconnect() throws ManetException {
        if (hardwareStatusReceiver != null) {
            context.unregisterReceiver(hardwareStatusReceiver);
            hardwareStatusReceiver = null;
        }
        if (discoverySession != null) {
            discoverySession.close();
            discoverySession = null;
        }
        if (awareSession != null) {
            awareSession.close();
            awareSession = null;
        }
        setStatus(Status.OFF);
        CommsLog.log("MANET disconnected");
        isRunning = false;
    }

    @Override
    public void executePeriodicTasks() {
        if (!isRunning) {
            try {
                Log.d(Config.TAG,"Attempting to restart WiFi Aware manager");
                init();
            } catch (ManetException e) {
                Log.e(Config.TAG, "Unable to initialize WiFi Aware: " + e.getMessage());
            }
        }
        //clear out stale nodes
        if ((nodes != null) && !nodes.isEmpty()) {
            Iterator it = nodes.entrySet().iterator();
            long timeToConsiderStale = System.currentTimeMillis() + TIME_TO_CONSIDER_STALE_DEVICE;
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                if ((long)pair.getValue() > timeToConsiderStale) {
                    it.remove();
                    //TODO consider notifying the link that the culling has occurred
                }
            }
        }
    }

    /**
     * Entry point when a change in the availability of WiFiAware is detected
     */
    private void onWiFiAwareStatusChanged() {
        WifiAwareManager mgr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
        if (mgr != null) {
            Log.d(Config.TAG, "WiFi Aware state changed: "+(mgr.isAvailable()?"available":"not available"));
            //TODO
        }
    }

    /**
     * Called based on the frequently (30min or less) randomization of MACs assigned for WiFiAware
     * @param mac the new MAC assigned to this device for WiFiAware use
     */
    public void onMacChanged (byte[] mac) {
        //TODO
    }
}
