package org.sofwerx.sqan.manet.wifiaware.v1;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
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
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.TeammateConnectionPlanner;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.common.sockets.server.Server;
import org.sofwerx.sqan.manet.common.sockets.server.ServerStatusListener;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;

import java.io.StringWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 *  This approach wasn't working as the IPV6 address was not being accepted. Further
 *  work indicates at the IPV6 address just needs to be associated with the NetworkInterface.
 *  However, this V1 approach was still abandoned as it was becoming unwieldy to
 *  accurately know the status of the connection. Use the WiDiAwareManetV2 instead.
 */
@Deprecated
public class WiFiAwareManet extends AbstractManet implements ServerStatusListener {
    private final static String TAG = Config.TAG+".Aware";
    private static final String SERVICE_ID = "sqan";
    private final static long SERVER_MAX_IDLE_TIME = 1000l * 60l;
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private static final long DELAY_BEFORE_CONNECTING_TO_SERVER = 1000l * 10l; //lead time to give the other device if both are establishing a server-client relationship
    private WifiAwareManager wifiAwareManager;
    private BroadcastReceiver hardwareStatusReceiver;
    private final AttachCallback attachCallback;
    private final IdentityChangedListener identityChangedListener;
    private WifiAwareSession awareSession;
    private final PublishConfig configPub;
    private final SubscribeConfig configSub;
    private static final long INTERVAL_LISTEN_BEFORE_PUBLISH = 1000l * 30l; //amount of time to listen for an existing hub before assuming the hub role
    private static final long INTERVAL_BEFORE_FALLBACK_DISCOVERY = 1000l * 15l; //amount of time to try connecting with devices identified OOB before failing over to WiFi Aware Discovery
    private Role role = Role.NONE;
    private DiscoverySession discoverySession;
    private ArrayList<Connection> connections = new ArrayList<>();
    private AtomicInteger messageIds = new AtomicInteger(0);
    private ConnectivityManager connectivityManager;
    private Client socketClient = null;
    private Server socketServer = null;
    private Inet6Address ipv6 = null;

    private enum Role {HUB, SPOKE, NONE}

    public WiFiAwareManet(android.os.Handler handler, android.content.Context context, ManetListener listener) {
        super(handler, context,listener);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            wifiAwareManager = null;
            identityChangedListener = new IdentityChangedListener() {
                @Override
                public void onIdentityChanged(byte[] mac) {
                    onMacChanged(mac);
                }
            };
            connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
            discoverySession = null;
            configPub = new PublishConfig.Builder()
                    .setServiceName(SERVICE_ID)
                    .build();
            configSub = new SubscribeConfig.Builder()
                    .setServiceName(SERVICE_ID)
                    .build();
            attachCallback = new AttachCallback() {
                @Override
                public void onAttached(final WifiAwareSession session) {
                    if (handler != null) {
                        handler.post(() -> {
                            Log.d(TAG, "onAttached(session)");
                            awareSession = session;
                            findOrCreateHub(true);
                        });
                    }
                    isRunning.set(true);
                }

                @Override
                public void onAttachFailed() {
                    if (handler != null) {
                        handler.post(() -> {
                            Log.e(TAG, "unable to attach to WiFiAware manager");
                            setStatus(Status.ERROR);
                        });
                    }
                    wifiAwareManager = null;
                    isRunning.set(false);
                }
            };
        } else {
            attachCallback = null;
            identityChangedListener = null;
            configPub = null;
            configSub = null;
        }
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WifiAwareManager mngr = (WifiAwareManager) context.toAndroid().getSystemService(Context.WIFI_AWARE_SERVICE);
                if (!mngr.isAvailable()) {
                    SqAnService.onIssueDetected(new WiFiIssue(true, "WiFi Aware is supported but the system is not making it available"));
                    passed = false;
                }
            }
        }
        if (NetUtil.isWiFiConnected(context.toAndroid()))
            SqAnService.onIssueDetected(new WiFiInUseIssue(false,"WiFi is connected to another network"));
        return passed;
    }

    @Override
    public int getMaximumPacketSize() { return 255; /*TODO temp maximum that reflects Aware message limitations */ }

    @Override
    public boolean isCongested() {
        //FIXME
        return false;
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {/*TODO*/ }

    @Override
    public String getName() { return "WiFi Aware™"; }

    @Override
    public void init() throws ManetException {
        if (!isRunning.get()) {
            Log.d(TAG,"WiFiAwareManet init()");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                isRunning.set(true);
                if (hardwareStatusReceiver == null) {
                    hardwareStatusReceiver = new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (handler != null)
                                handler.post(() -> onWiFiAwareStatusChanged());
                        }
                    };
                    IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
                    context.toAndroid().registerReceiver(hardwareStatusReceiver, filter, null, handler.toAndroid());
                }
                if (wifiAwareManager == null) {
                    NetUtil.turnOnWiFiIfNeeded(context.toAndroid());
                    NetUtil.forceLeaveWiFiNetworks(context.toAndroid()); //TODO include a check to protect an active connection if its used for data backhaul
                    wifiAwareManager = (WifiAwareManager) context.toAndroid().getSystemService(Context.WIFI_AWARE_SERVICE);
                    if ((wifiAwareManager != null) && wifiAwareManager.isAvailable())
                        wifiAwareManager.attach(attachCallback, identityChangedListener, handler.toAndroid());
                    else {
                        Log.e(TAG, "WiFi Aware Manager is not available");
                        setStatus(Status.ERROR);
                    }
                }
            }
        }
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return true; }

    private Connection findPeer(PeerHandle peerHandle) {
        if ((peerHandle != null) && (connections != null) && !connections.isEmpty()) {
            int id = peerHandle.hashCode();
            synchronized (connections) {
                for (Connection connection : connections) {
                    if ((connection != null) && (connection.getPeerHandle() != null) && (connection.getPeerHandle().hashCode() == id)) {
                        //Log.d(TAG,"findPeer found Aware "+id);
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    private Connection findPeer(SqAnDevice device) {
        if ((device != null) && (connections != null) && !connections.isEmpty()) {
            int id = device.getUUID();
            synchronized (connections) {
                for (Connection connection : connections) {
                    if ((connection != null) && (connection.getDevice() != null) && (connection.getDevice().getUUID() == id)) {
                        Log.d(TAG,"findPeer found "+connection.getDevice().getLabel());
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    private Connection findConnectionWithTransientID(final int id) {
        if ((connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                for (Connection connection : connections) {
                    if ((connection != null) && (connection.getDevice()!= null) && (connection.getDevice().getTransientAwareId() == id)) {
                        Log.d(TAG,"findConnectionWithTransientID() found a match for Aware ID "+id+" in "+connection.getDevice().getLabel());
                        return connection;
                    }
                }
            }
        }
        return null;
    }

    private void updatePeer(PeerHandle peerHandle) {
        if (peerHandle == null)
            return;
        Connection old = findPeer(peerHandle);
        if (old == null)
            old = findConnectionWithTransientID(peerHandle.hashCode());
        if (old == null) {
            if (connections == null)
                connections = new ArrayList<>();
            synchronized (connections) {
                int netId = peerHandle.hashCode();
                SqAnDevice device = SqAnDevice.findByTransientAwareID(netId);
                if (device == null) {
                    device = new SqAnDevice();
                    Log.d(TAG, "WiFi Aware ID " + netId + " does not match an existing device, creating a new device");
                } else {
                    //Log.d(TAG, "WiFi Aware ID " + netId + " matches existing device: " + device.getLabel());
                }
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "New WiFi Aware connection (" + peerHandle.hashCode() + ") for " + device.getLabel());
                device.setConnected(0, false, true, device.isDirectWiFiHighPerformance());
                old = new Connection(peerHandle, device);
                old.setLastConnection();
                connections.add(old);
            }
            SqAnService.burstVia(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),TransportPreference.WIFI);
        } else {
            //Log.d(TAG,"updatePeer() found existing connection to Aware "+peerHandle.hashCode());
            old.setLastConnection();
        }
    }

    private void updatePeer(SqAnDevice device) {
        if (device == null)
            return;
        Connection old = findPeer(device);
        if (old == null) {
            if (connections == null)
                connections = new ArrayList<>();
            synchronized (connections) {
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "New WiFi Aware connection for "+ device.getLabel());
                device.setConnected(0, false, true, device.isDirectWiFiHighPerformance());
                old = new Connection(null, device);
                old.setLastConnection();
                connections.add(old);
            }
            SqAnService.burstVia(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),TransportPreference.WIFI);
        } else {
            Log.d(TAG,"updatePeer() found existing connection to "+device.getLabel());
            old.setLastConnection();
        }
    }

    private void stopDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (discoverySession != null) {
                Log.d(TAG, "Closing discovery session");
                discoverySession.close();
                discoverySession = null;
            }
        }
    }

    private void startAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware start advertising");
            if (awareSession != null) {
                setStatus(Status.ADVERTISING);
                awareSession.publish(configPub, new DiscoverySessionCallback() {
                    @Override
                    public void onPublishStarted(PublishDiscoverySession session) {
                        Log.d(TAG, "onPublishStarted()");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                        if (listener != null)
                            listener.onStatus(status);
                    }

                    @Override
                    public void onMessageReceived(final PeerHandle peerHandle, final byte[] message) {
                        if (role == Role.NONE) {
                            setStatus(Status.CONNECTED);
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware message received - changing role to "+role.name());
                        } else
                            Log.d(TAG, "onMessageReceived()");
                        if (handler != null)
                            handler.post(() -> {
                                updatePeer(peerHandle);
                                connectToPeer(peerHandle,false);
                                handleMessage(findConnection(peerHandle),message);
                            });
                    }

                    @Override
                    public void onSubscribeStarted(final SubscribeDiscoverySession session) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware subscription started");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                    }

                    @Override
                    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware service discovered");
                        setStatus(Status.CONNECTED);
                        if (handler != null)
                            handler.post(() -> updatePeer(peerHandle));
                        if (listener != null)
                            listener.onStatus(status);
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onMessageSendSucceeded(int messageId) {
                        super.onMessageSendSucceeded(messageId);
                        Log.d(TAG,"Message "+messageId+" was successfully sent");
                    }

                    @Override
                    public void onSessionConfigFailed() {
                        setRole(Role.NONE);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session configuration failed");
                    }

                    @Override
                    public void onSessionTerminated() {
                        setRole(Role.NONE);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"awareSession onSessionTerminated()");
                    }

                    @Override
                    public void onSessionConfigUpdated() {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION, "awareSession onSessionConfigUpdated()");
                    }

                    @Override
                    public void onMessageSendFailed(int messageId) {
                        Log.w(TAG,"Message "+messageId+" failed");
                    }
                }, handler.toAndroid());
            }
        }
    }

    private Connection findConnection(PeerHandle peerHandle) {
        if ((peerHandle != null) && (connections != null) && !connections.isEmpty()) {
            int handleToFind = peerHandle.hashCode();
            synchronized (connections) {
                for (Connection connection:connections) {
                    if ((connection != null) && (connection.getPeerHandle() != null) && (connection.getPeerHandle().hashCode() == handleToFind))
                        return connection;
                }
            }
        }
        return null;
    }

    private Connection findConnection(SqAnDevice device) {
        if ((device != null) && (connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                for (Connection connection:connections) {
                    if ((connection != null) && device.isSame(connection.getDevice()))
                        return connection;
                }
            }
        }
        return null;
    }

    private void handleMessage(Connection connection,byte[] message) {
        if (message == null) {
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received an empty message");
            return;
        }
        if (connection == null) {
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received a message from a null connection; this should never happen");
            return;
        }

        connection.setLastConnection();
        AbstractPacket packet = AbstractPacket.newFromBytes(message);
        if (packet == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "WiFi Aware message from "+((connection.getDevice()==null)?"unknown device":connection.getDevice().getLabel())+" could not be parsed");
            return;
        }
        SqAnDevice device;
        if (packet.isDirectFromOrigin())
            device = SqAnDevice.findByUUID(packet.getOrigin());
        else
            device = connection.getDevice();
        if (device == null)
            CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Aware received a message from an unknown device");
        if (device != null) {
            Log.d(TAG,"WiFi Aware received a message from "+device.getLabel()+" (Aware "+((connection.getPeerHandle()==null)?"unk peer handle":connection.getPeerHandle().hashCode())+")");
            device.setHopsAway(packet.getCurrentHopCount(), false,true, device.isDirectWiFiHighPerformance()); //don't consider WiFi Aware messages as the same thing as direct WiFI connection
            device.setLastConnect();
            device.addToDataTally(message.length);
            if (packet.isDirectFromOrigin())
                connection.setDevice(device);
        }
        relayPacketIfNeeded(connection,message,packet.getSqAnDestination(),packet.getOrigin(),packet.getCurrentHopCount());
        super.onReceived(packet);
        if ((socketServer == null) && (socketClient == null) && (device != null)) {
            if (packet instanceof HeartbeatPacket) {
                Connection connectionHB = findConnection(device);
                if (connectionHB != null) {
                    Log.d(TAG, "Heartbeat packet from "+device.getLabel()+" received; using this to try to establish a socket server or client connection...");
                    findOrCreateHub(true);
                }
            }
        }
    }

    private void relayPacketIfNeeded(Connection originConnection, final byte[] data, final int destination, final int origin, final int hopCount) {
        if ((originConnection != null) && (data != null) && (connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                SqAnDevice device;
                AbstractPacket reconstructed = null;
                for (Connection connection : connections) {
                    if ((connection == null) || (originConnection == connection) || (connection.getDevice() == null))
                        continue;
                    device = connection.getDevice();
                    if ((device.getUUID() != origin) //dont send to ourselves
                        && AddressUtil.isApplicableAddress(device.getUUID(),destination)
                        && hopCount < device.getHopsToDevice(origin)) {
                        CommsLog.log(CommsLog.Entry.Category.COMMS,"WiFi Aware relaying packet from "+origin+" ("+hopCount+" hops) to "+device.getLabel());

                        if (reconstructed == null) {
                            reconstructed = AbstractPacket.newFromBytes(data);
                            reconstructed.incrementHopCount();
                        }
                        boolean sendViaBt = false;
                        if (device.isWiFiPreferred()) {
                            try {
                                burst(reconstructed,device);
                            } catch (ManetException e) {
                                Log.e(TAG,"Exception when trying to send packet to "+device.getLabel()+" over Aware: "+e.getMessage());
                                sendViaBt = true;
                            }
                        }
                        if (device.isBtPreferred() || sendViaBt) {
                            CommsLog.log(CommsLog.Entry.Category.COMMS,"WiFi Aware referring packet from "+origin+" ("+hopCount+" hops) to "+device.getLabel()+" for relay via bluetooth");
                            SqAnService.burstVia(reconstructed, TransportPreference.BLUETOOTH);
                        }
                    }
                }
            }
        } else
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Aware cannot relay a null packet or handle a null connection origin");
    }

    private void startDiscovery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware discovery started");
            if (discoverySession != null) {
                Log.d(TAG,"Stopping previous Aware discoverySession to start new discoverySession");
                discoverySession.close();
                discoverySession = null;
                setStatus(Status.CHANGING_MEMBERSHIP);
            }
            if (awareSession != null) {
                role = Role.NONE;
                setStatus(Status.DISCOVERING);
                awareSession.subscribe(configSub, new DiscoverySessionCallback() {
                    @Override
                    public void onSubscribeStarted(final SubscribeDiscoverySession session) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware subscription started");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                    }

                    @Override
                    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                        //if (role == Role.NONE) {
                        //    setRole(Role.SPOKE);
                        //    CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware service discovered - changing role to "+role.name());
                        //} else
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware service discovered");
                        setStatus(Status.CONNECTED);
                        if (handler != null)
                            handler.post(() -> updatePeer(peerHandle));
                        if (listener != null)
                            listener.onStatus(status);
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onMessageReceived(final PeerHandle peerHandle, final byte[] message) {
                        //if (role == Role.NONE) {
                        //    setRole(Role.SPOKE);
                        //    CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware message received - changing role to "+role.name());
                            setStatus(Status.CONNECTED);
                        //} else
                            Log.d(TAG, "onMessageReceived()");
                        if (handler != null)
                            handler.post(() -> {
                                updatePeer(peerHandle);
                                handleMessage(findConnection(peerHandle),message);
                            });
                        connectToPeer(peerHandle,false);
                    }

                    @Override
                    public void onMessageSendSucceeded(int messageId) {
                        super.onMessageSendSucceeded(messageId);
                        Log.d(TAG,"Message "+messageId+" was successfully sent");
                    }

                    @Override
                    public void onSessionConfigFailed() {
                        setRole(Role.NONE);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session configuration failed");
                    }

                    @Override
                    public void onSessionTerminated() {
                        setRole(Role.NONE);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session terminated");
                    }

                    @Override
                    public void onSessionConfigUpdated() {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION, "onSessionConfigUpdated()");
                    }

                    @Override
                    public void onMessageSendFailed(int messageId) {
                        Log.w(TAG,"Message "+messageId+" failed");
                    }
                }, handler.toAndroid());
            }
        }
    }

    private void connectToPeer(PeerHandle peerHandle) { connectToPeer(peerHandle,true); }
    private void connectToPeer(PeerHandle peerHandle, boolean burst) {
        if (peerHandle == null)
            return;
        Log.d(TAG,"connectToPeer(peerHandle): "+peerHandle.hashCode());
        Connection connection = findPeer(peerHandle);
        if (connection == null) {
            burst = true;
            updatePeer(peerHandle);
            connection = findPeer(peerHandle);
        }
        if (burst)
            burst(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),peerHandle);
        connectToPeer(connection);
    }

    private void connectToPeer(SqAnDevice device) {
        if (device == null)
            return;
        Log.d(TAG,"connectToPeer(device): "+device.getLabel());
        Connection connection = findPeer(device);
        if ((device.getAwareMac() == null) && (device.getAwareServerIp() == null))
            Log.d(TAG,"Cannot connectToPeer("+device.getLabel()+") as that device does not have any Aware info");
        if (connection == null) {
            HeartbeatPacket packet = new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM);
            if (device.isUuidKnown())
                packet.setDestination(device.getUUID());
            try {
                burst(packet,device);
            } catch (ManetException ignore) {
            }
            updatePeer(device);
            connection = findPeer(device);
            connectToPeer(connection);
        }
    }

    private void connectToPeer(Connection connection) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (connection == null) {
                Log.w(TAG,"Cannot connectToPeer - null connection");
                return;
            }
            if ((connection.getCallback() != null) && (connection.getCallback() instanceof AwareManetConnectionCallback)) { //this connection is already being handled
                if (((AwareManetConnectionCallback)connection.getCallback()).isStale()) {
                    Log.d(TAG, "connectToPeer(connection); callback is already in place but is stale. Unregistering stale callback...");
                    try {
                        connectivityManager.unregisterNetworkCallback(connection.getCallback());
                    } catch (Exception e) {
                        Log.e(TAG,"Cannot unregister NetworkCallback for "+connection.toString());
                    }
                    connection.setCallback(null);
                } else {
                    Log.d(TAG, "ignoring connectToPeer(connection) as callback is already in place");
                    return;
                }
            }
            Log.d(TAG,"connectToPeer("+connection.toString()+")");
            SqAnDevice device = connection.getDevice();
            PeerHandle peerHandle = connection.getPeerHandle();
            if (device == null) {
                if (peerHandle == null) {
                    Log.w(TAG,"Cannot connectToPeer, both Device and PeerHandle are null");
                    return;
                }
                Log.d(TAG, "Establishing Aware network connection to " + peerHandle.hashCode());
            } else
                Log.d(TAG, "Establishing Aware network connection to " + device.getLabel());
            //NetworkSpecifier networkSpecifier = discoverySession.createNetworkSpecifierPassphrase(peerHandle, conformPasscodeToWiFiAwareRequirements(Config.getPasscode()));
            String passcode = conformPasscodeToWiFiAwareRequirements(Config.getPasscode()); //TODO
            NetworkSpecifier networkSpecifier = null;
            if ((device.getAwareMac() != null) && device.getAwareMac().isValid()) {
                Log.d(TAG,"Building an aware network specifier based on OOB details as "+((socketServer==null)?"Responder":"Initiator"));
                networkSpecifier = awareSession.createNetworkSpecifierOpen((socketServer==null)?WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER:WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR,device.getAwareMac().toByteArray());
            } else if ((peerHandle != null) && (discoverySession != null)) {
                Log.d(TAG,"Building an aware network specifier based on PeerHandle");
                networkSpecifier = discoverySession.createNetworkSpecifierOpen(peerHandle);
            }
            if (networkSpecifier == null) {
                Log.d(TAG,"Unable to build a network specifier so not establishing an Aware network connection at this time");
                //connection.setCallback(null);
                return;
            }
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(networkSpecifier)
                    .build();

            Log.d(TAG,"Requesting a network connection...");
            connection.setCallback(new AwareManetConnectionCallback(connection));
            connectivityManager.requestNetwork(networkRequest,connection.getCallback());
        }
    }

    private final static int MIN_PASSPHRASE_LENGTH = 8; //TODO unable to find these definitively, so they are guesses
    private final static int MAX_PASSPHRASE_LENGTH = 63; //TODO unable to find these definitively, so they are guesses
    /**
     * Adjusts the passcode to meet WiFi Aware requirements
     * @param passcode
     * @return
     */
    private String conformPasscodeToWiFiAwareRequirements(String passcode) {
        if ((passcode != null) && (passcode.length() >= MIN_PASSPHRASE_LENGTH) && (passcode.length() <= MAX_PASSPHRASE_LENGTH))
            return passcode;
        StringWriter out = new StringWriter();

        int i=0;
        while (i < MIN_PASSPHRASE_LENGTH) {
            if (i < passcode.length())
                out.append(passcode.charAt(i));
            else
                out.append('x');
            i++;
        }
        while ((i < MAX_PASSPHRASE_LENGTH) && (i < passcode.length())) {
            out.append(passcode.charAt(i));
            i++;
        }
        String output = out.toString();
        Log.d(TAG,"Passphrase \""+passcode+"\" adjusted to \""+output+"\" to conform with WiFi Aware requirements");
        return out.toString();
    }

    /*******************/

    @Override
    public void onServerBlacklistClient(InetAddress address) {
        if (address != null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"WiFi Direct Server blacklisted "+address.toString());
        }
        //TODO
    }

    @Override
    public void onServerError(String error) {
        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"WiFi Direct Server error: "+error);
        //ignore this for now as the mesh tries to self-heal
    }

    @Override
    public void onServerFatalError() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Fatal server error");
        stopSocketConnections(false);
    }

    @Override
    public void onServerClosed() {
        socketServer = null;
        if (role == Role.HUB)
            role = Role.NONE;
        //TODO
    }

    @Override
    public void onServerClientDisconnected(InetAddress address) {
        if (address != null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, address.toString()+" disconnected");
            //ignore this for now; maybe revisited later to help address mesh changes
        }
    }

    @Override
    public void onNewClient(SqAnDevice device) {
        if (listener != null)
            listener.onDevicesChanged(device);
        onAuthenticatedOnNet();
    }

    @Override
    public void burst(AbstractPacket packet) throws ManetException {
        if (packet != null) {
            if (socketClient != null) //packets from clients always get sent to the server
                burst(packet, (SqAnDevice)null);
            else {
                SqAnDevice device = SqAnDevice.findByUUID(packet.getSqAnDestination());
                if (socketServer != null) {
                    if (device == null) {
                        boolean multiHopNeeded = false;
                        if (packet.getSqAnDestination() == PacketHeader.BROADCAST_ADDRESS) {
                            if (connections != null)
                                burst(packet, (SqAnDevice)null);
                        } else {
                            //TODO actually look at multi-hop logic here
                            burst(packet,(SqAnDevice) null); //TODO remove this once multihop logic is in place
                        }
                    } else //the destination device is in this manet
                        burst(packet, device);
                } else
                    burst(packet,device);
            }
        } else
            Log.d(TAG,"Cannot send null packet");
    }

    private void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        if (packet != null) {
            boolean sent = false;
            if ((socketClient != null) && socketClient.isReady()) {
                sent = socketClient.burst(packet); //clients only send packets to the server
                Log.d(TAG,"burst("+packet.getClass().getSimpleName()+") "+(sent?"sent":"not sent")+" as Client");
            }
            if (socketServer != null) { //but servers can send packets to clients that are destined for different devices
                if (device == null)
                    sent = socketServer.burst(packet, packet.getSqAnDestination());
                else
                    sent = socketServer.burst(packet, device.getUUID());
                Log.d(TAG,"burst("+packet.getClass().getSimpleName()+") "+(sent?"sent":"not sent")+" as Server");
            }
            if (!sent) {
                if ((connections != null) && !connections.isEmpty()) {
                    synchronized (connections) {
                        for (Connection connection:connections) {
                            if ((connection.getDevice() != null) && (connection.getPeerHandle() != null)) {
                                if (AddressUtil.isApplicableAddress(connection.getDevice().getUUID(), packet.getSqAnDestination())) {
                                    Log.d(TAG,"Failing over to send packet to "+connection.getDevice().getLabel()+" via Aware message (socket connection not available)");
                                    burst(packet, connection.getPeerHandle());
                                    sent = true;
                                }
                            }
                        }
                    }
                } else
                    Log.d(TAG, "Aware tried to burst but no nodes available to receive");
            }
            if (sent) {
                if (listener != null)
                    listener.onTx(packet);
            }
        } else
            Log.d(TAG,"Trying to burst over manet but packet was null");
    }

    /*******************/

    private void burst(AbstractPacket packet, PeerHandle peerHandle) {
        if (packet == null) {
            Log.d(TAG,"Aware Cannot send empty packet");
            return;
        }
        burst(packet.toByteArray(),peerHandle);
    }

    private void burst(final byte[] bytes, final PeerHandle peerHandle) {
        if (bytes == null) {
            Log.d(TAG,"Aware cannot send empty byte array");
            return;
        }
        if (peerHandle == null) {
            Log.d(TAG,"Cannot send packet to an empty PeerHandle");
            return;
        }
        if (discoverySession == null) {
            Log.d(TAG,"Cannot send packet as no discoverySession exists");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (bytes.length > getMaximumPacketSize()) {
                Log.d(TAG, "Packet larger than WiFi Aware max; ignoring... TODO");
                //TODO segment and burst
            } else {
                if (discoverySession != null)
                    handler.post(() -> {
                        if (discoverySession != null) {
                            Log.d(TAG, "Sending Message " + (messageIds.get() + 1) + " (" + bytes.length + "b) to " + peerHandle.hashCode());
                            discoverySession.sendMessage(peerHandle, messageIds.incrementAndGet(), bytes);
                            ManetOps.addBytesToTransmittedTally(bytes.length);
                        }
                    });
                else
                    Log.w(TAG,"Cannot send burst as discovery session is null");
            }
        } else
            Log.d(TAG,"Cannot burst, WiFi Aware is not supported");
    }

    private void stopSocketConnections(boolean announce) {
        if (socketClient != null) {
            socketClient.close();
            Log.d(TAG,"socketClient closing...");
            socketClient = null;
        }
        if (socketServer != null) {
            socketServer.close(announce);
            Log.d(TAG,"socketServer closing...");
            socketServer = null;
        }
        setRole(Role.NONE);
    }

    private void setRole(Role role) {
        if (this.role != role) {
            this.role = role;
            switch (role) {
                case HUB:
                    Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.HUB);
                    break;
                case SPOKE:
                    Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.SPOKE);
                    break;
                default:
                    Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
            }
            if (listener != null)
                listener.onStatus(getStatus());
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
     * @param oob true == rely on out-of-band discovered devices; false = rely on Advertise/Discovery
     */
    private void findOrCreateHub(boolean oob) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Log.d(TAG, "findOrCreateHub("+oob+")");
            if ((awareSession != null) && (socketClient == null) && (socketServer == null)) {
                if (oob) {
                    ArrayList<SqAnDevice> devices = TeammateConnectionPlanner
                            .getDescendingPriorityWiFiConnections(SqAnDevice.getWiFiAwareDevices());
                    if ((devices == null) || devices.isEmpty()) {
                        Log.d(TAG,"No devices found; looking for devices through Advertise/Discovery...");
                        findOrCreateHub(false);
                        return;
                    }
                    Log.d(TAG,devices.size()+" Aware devices listed in OOB discovery");
                    if (handler != null) {
                        handler.toAndroid().postDelayed(() -> {
                            findOrCreateHub(false);
                        }, INTERVAL_BEFORE_FALLBACK_DISCOVERY);
                    }
                    boolean shouldHost = false;
                    for (SqAnDevice device:devices) {
                        if (shouldLocalDeviceHost(device)) {
                            shouldHost = true;
                            break;
                        }
                        if (device.isAwareServer()) {
                            startClient(device,true);
                            break;
                        }
                    }
                    if (shouldHost) {
                        Log.d(TAG,"This device should host the server, requesting server start...");
                        ArrayList<SqAnDevice> wifiDevices = TeammateConnectionPlanner
                                .getDescendingPriorityWiFiConnections(SqAnDevice.getWiFiAwareDevices());
                        if ((wifiDevices != null) && !wifiDevices.isEmpty() && startServer(true)) {
                            for (SqAnDevice wifiDevice:wifiDevices) { //try to connect to all prioritized devices
                                connectToPeer(wifiDevice);
                            }
                        }
                        return;
                    }
                    Log.d(TAG,"No suitable host device found during OOB discovery and this device does not seem like the best host");
                } else {
                    if ((connections != null) && !connections.isEmpty()) {
                        for (Connection connection:connections) {
                            connectToPeer(connection);
                        }
                    } else if (status == Status.OFF) { //TODO this may be a problem
                        startDiscovery();
                        if (handler != null) {
                            handler.toAndroid().postDelayed(() -> {
                                if (getActiveConnectionCount() == 0) {
                                    Log.d(TAG, "no existing network found");
                                    setStatus(Status.CHANGING_MEMBERSHIP);
                                    assumeHubRole();
                                } else
                                    Log.d(TAG, "No need to change roles (currently " + role.name() + ") as it appears discovery was successful");
                            }, INTERVAL_LISTEN_BEFORE_PUBLISH);
                        }
                    }
                }
            }
        }
    }

    private int getActiveConnectionCount() {
        if ((connections == null) || connections.isEmpty())
            return 0;
        return connections.size();
    }

    /**
     * Take over the role as the Hub for this mesh
     */
    private void assumeHubRole() {
        Log.d(TAG,"Assuming the hub role");
        startAdvertising();
    }

    @Override
    public void disconnect() throws ManetException {
        super.disconnect();
        stopSocketConnections(true);
        if ((connections != null) && !connections.isEmpty() && (connectivityManager != null)) {
            synchronized (connections) {
                for (Connection connection:connections) {
                    if (connection.getCallback() != null) {
                        try {
                            connectivityManager.unregisterNetworkCallback(connection.getCallback());
                        } catch (Exception e) {
                            Log.e(TAG,"Cannot unregister NertworkCallback for "+connection.toString());
                        }
                    }
                }
            }
        }
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver);
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
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
        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
    }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        //TODO
    }

    @Override
    public void executePeriodicTasks() {
        if (!isRunning()) {
            try {
                Log.d(TAG,"Attempting to restart WiFi Aware manager");
                init();
            } catch (ManetException e) {
                Log.e(TAG, "Unable to initialize WiFi Aware: " + e.getMessage());
            }
        }

        removeUnresponsiveConnections();
    }

    private void removeUnresponsiveConnections() {
        if ((connections != null) && !connections.isEmpty()) {
            synchronized (connections) {
                int i=0;
                final long timeToConsiderStale = System.currentTimeMillis() - TIME_TO_CONSIDER_STALE_DEVICE;
                while (i<connections.size()) {
                    if (connections.get(i) == null) {
                        connections.remove(i);
                        continue;
                    }
                    if (timeToConsiderStale > connections.get(i).getLastConnection()) {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing stale WiFi Aware connection: "+((connections.get(i).getDevice()==null)?"device unknown":connections.get(i).getDevice().getLabel()));
                        connections.remove(i);
                    } else
                        i++;
                }
            }
        }
    }

    /**
     * Entry point when a change in the availability of WiFiAware is detected
     */
    private void onWiFiAwareStatusChanged() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WifiAwareManager mgr = (WifiAwareManager) context.toAndroid().getSystemService(Context.WIFI_AWARE_SERVICE);
            if (mgr != null) {
                Log.d(TAG, "WiFi Aware state changed: " + (mgr.isAvailable() ? "available" : "not available"));
                //TODO
            }
        }
    }

    /**
     * Called based on the frequently (30min or less) randomization of MACs assigned for WiFiAware
     * @param mac the new MAC assigned to this device for WiFiAware use
     */
    public void onMacChanged (byte[] mac) {
        Config.getThisDevice().setAwareMac(new MacAddress(mac));
        //TODO
    }

    private class AwareManetConnectionCallback extends ConnectivityManager.NetworkCallback {
        private final static long CALLBACK_TIMEOUT = 1000l * 10l;
        private final Connection connection;
        private final long timeToStale;
        private boolean success = false;

        public AwareManetConnectionCallback(Connection connection) {
            super();
            timeToStale = System.currentTimeMillis() + CALLBACK_TIMEOUT;
            this.connection = connection;
        }

        public boolean isStale() {
            return !success && (System.currentTimeMillis() > timeToStale);
        }

        @Override
        public void onAvailable(Network network) {
            success = true;
            Log.d(TAG,"NetworkCallback onAvailable() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            if (ipv6 == null) {
                ipv6 = NetUtil.getAwareAddress(context.toAndroid(), network);
                if (ipv6 != null) {
                    Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
                    handleNetworkChange(network,connection,ipv6);
                }
            }
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            Log.d(TAG,"NetworkCallback onLinkPropertiesChanged() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            ipv6 = NetUtil.getAwareAddress(linkProperties);
            SqAnDevice thisDevice = Config.getThisDevice();
            if (thisDevice == null)
                return;
            if (thisDevice.getAwareServerIp() == null)
                Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
            else if (!ipv6.equals(thisDevice.getAwareServerIp())) {
                Log.d(TAG, "Aware IP address changed to " + ipv6.getHostAddress());
                stopSocketConnections(false);
            }

            if (ipv6 != null)
                handleNetworkChange(network,connection,ipv6);
            else
                Log.d(TAG,"Could not do anything with the link property change as the ipv6 address was null");
        }

        @Override
        public void onLost(Network network) {
            success = false;
            Log.d(TAG,"Aware onLost() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            if (connection != null)  {
                try {
                    connectivityManager.unregisterNetworkCallback(this);
                    connection.setCallback(null);
                    Log.d(TAG,"unregistered NetworkCallback for "+connection.toString());
                } catch (Exception e) {
                    Log.w(TAG,"Unable to unregister this NetworkCallback: "+e.getMessage());
                }
            }
            if (Config.getThisDevice() != null)
                handleNetworkChange(null,connection,Config.getThisDevice().getAwareServerIp());
        }

        @Override
        public void onUnavailable() {
            success = false;
            Log.d(TAG, "NetworkCallback onUnavailable()");
        }

        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
            Log.d(TAG, "NetworkCallback onCapabilitiesChanged()");
        }

        @Override
        public void onLosing(Network network, int maxMsToLive) {
            Log.d(TAG, "NetworkCallback onLosing()");
        }
    }

    private void handleNetworkChange(Network network, Connection connection, Inet6Address ipv6) {
        Log.d(TAG,"handleNetworkChange()");
        if ((ipv6 != null) && (socketServer != null) && socketServer.isRunning())
            Config.getThisDevice().setAwareServerIp(ipv6);
        if (connection == null) {
            Log.d(TAG,"handleNetworkChange ignored as connection is null");
            return;
        }
        connection.setNetwork(network);
        if (ipv6 != null) {
            SqAnDevice other = connection.getDevice();
            if (other == null)
                return;
            if (shouldLocalDeviceHost(other)) {
                Log.d(TAG,"Aware server connection with "+other.getLabel()+" should be hosted by this device");
                startServer(true);
            } else {
                Log.d(TAG,"Aware server connection with "+other.getLabel()+" should be hosted by the other device; waiting a short time to allow the server to set-up");
                handler.toAndroid().postDelayed(() -> {
                    startClient(other,false);
                    },DELAY_BEFORE_CONNECTING_TO_SERVER);
            }
        } else
            Config.getThisDevice().clearAwareServerIp();
    }

    /**
     * Starts the server
     * @param onlyIfNotAlreadyRunning true == start only if not already running
     * @return server started
     */
    private boolean startServer(boolean onlyIfNotAlreadyRunning) {
        //stopDiscovery();
        if (Config.getThisDevice() == null) {
            Log.d(TAG, "Cannot start server as Config.getThisDevice() returns null");
            return false;
        }
        if (ipv6 == null) {
            if ((connections != null) && !connections.isEmpty()) {
                Log.d(TAG,"startServer called, but IPV6 is not known; trying to fix...");
                Network network = null;
                for (Connection conn:connections) {
                    if ((conn != null) && (conn.getNetwork() != null))
                        network = conn.getNetwork();
                }
                ipv6 = NetUtil.getAwareAddress(context.toAndroid(),network);
            }
        }
        /*if (ipv6 == null) {
            Log.d(TAG,"Cannot start server as IPV6 is not known");
            return false;
        } else
            Config.getThisDevice().setAwareServerIp(ipv6); */
        Log.d(TAG,"startServer("+onlyIfNotAlreadyRunning+")");
        if (socketServer != null) {
            if (onlyIfNotAlreadyRunning && socketServer.isRunning()) {
                Log.d(TAG,"ignoring request to start socketServer as it is already running");
                return false;
            }
            Log.d(TAG,"Stopping previous socketServer to establish new socketServer connection");
            socketServer.close(false);
        }

        if (isRunning.get()) {
            socketServer = new Server(new SocketChannelConfig((String) null, SQAN_PORT), parser, WiFiAwareManet.this);
            socketServer.setIdleTimeout(SERVER_MAX_IDLE_TIME);
            socketServer.start();
            setRole(Role.HUB);
            if (listener != null)
                listener.onStatus(getStatus());
        }
        return true;
    }

    private void startClient(SqAnDevice other, boolean onlyIfNotAlreadyRunning) {
        //FIXME - the client isnt working as the IPV6 address isnt being accepted as a legal arguement for the SocketChannel
        //stopDiscovery();
        if (other == null)
            return;
        Log.d(TAG,"startClient("+other.getLabel()+","+onlyIfNotAlreadyRunning+")");
        if (other.getAwareServerIp() == null) {
            Log.d(TAG,"Unable to start client as "+other.getLabel()+" does not have an Aware server IP address");
            return;
        }
        if (socketClient != null) {
            if (onlyIfNotAlreadyRunning && socketClient.isAlive()) {
                Log.d(TAG,"ignoring request to start socketClient as it is already alive");
                return;
            }
            Log.d(TAG,"Stopping previous socketClient to establish new socketClient connection");
            socketClient.close(true);
        }

        if (isRunning.get()) {
            setRole(Role.SPOKE);
            Log.d(TAG,"Aware starting client connection");
            socketClient = new Client(new SocketChannelConfig(other.getAwareServerIp(), SQAN_PORT), parser);
            socketClient.start();
        }
        if (listener != null)
            listener.onStatus(getStatus());
        Config.getThisDevice().clearAwareServerIp();
    }

    /**
     * Decides if this device or the remote other device should be the server
     * @param other
     * @return true == this device should be the server; false == other device is assumed to be the server
     */
    private boolean shouldLocalDeviceHost(SqAnDevice other) {
        if ((socketServer != null) || (other == null)) {
            Log.d(TAG,"The local device should host as "+((socketServer!=null)?" socketServer != null":"the other device is null"));
            return true;
        }
        if (other.isAwareServer()) {
            Log.d(TAG,"The local device should not host as the other device is an Aware Server");
            return false;
        }
        SqAnDevice thisDevice = Config.getThisDevice();
        if (thisDevice == null) {
            Log.d(TAG,"The local device should not host as this device is null");
            return false;
        }
        boolean favorThisDevice;
        if ((thisDevice.getRelayConnections() != null) && (other.getRelayConnections() != null)) {
            //favor fewer connections
            if ((thisDevice.getRelayConnections().size() != other.getRelayConnections().size()) && (thisDevice.getRelayConnections().size() > 1) && (thisDevice.getRelayConnections().size() > 1)) {
                favorThisDevice = thisDevice.getRelayConnections().size() < other.getRelayConnections().size();
                Log.d(TAG,"The "+(favorThisDevice?"local":"other")+" device should host as it has fewer connections");
                return favorThisDevice;
            }
        }
        favorThisDevice = thisDevice.getUUID() > other.getUUID();
        Log.d(TAG,"The "+(favorThisDevice?"local":"other")+" device should host as it has a higher UUID");
        return favorThisDevice;
    }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        //ignore
    }
}
