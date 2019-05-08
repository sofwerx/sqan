package org.sofwerx.sqan.manet.wifiaware;

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
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 */
public class WiFiAwareManet extends AbstractManet implements ServerStatusListener {
    private static final String SERVICE_ID = "sqan";
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
    private Network awareNetwork;
    private Client socketClient = null;
    private Server socketServer = null;

    private enum Role {HUB, SPOKE, NONE}

    public WiFiAwareManet(Handler handler, Context context, ManetListener listener) {
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
                            Log.d(Config.TAG, "onAttached(session)");
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
                            Log.e(Config.TAG, "unable to attach to WiFiAware manager");
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
                WifiAwareManager mngr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                if (!mngr.isAvailable()) {
                    SqAnService.onIssueDetected(new WiFiIssue(true, "WiFi Aware is supported but the system is not making it available"));
                    passed = false;
                }
            }
        }
        if (NetUtil.isWiFiConnected(context))
            SqAnService.onIssueDetected(new WiFiInUseIssue(false,"WiFi is connected to another network"));
        return passed;
    }

    @Override
    public int getMaximumPacketSize() { return 64000; /*TODO temp maximum */ }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {/*TODO*/ }

    @Override
    public String getName() { return "WiFi Aware™"; }

    @Override
    public void init() throws ManetException {
        if (!isRunning.get()) {
            Log.d(Config.TAG,"WiFiAwareManet init()");
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
                    context.registerReceiver(hardwareStatusReceiver, filter, null, handler);
                }
                if (wifiAwareManager == null) {
                    NetUtil.turnOnWiFiIfNeeded(context);
                    NetUtil.forceLeaveWiFiNetworks(context); //TODO include a check to protect an active connection if its used for data backhaul
                    wifiAwareManager = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
                    if ((wifiAwareManager != null) && wifiAwareManager.isAvailable())
                        wifiAwareManager.attach(attachCallback, identityChangedListener, handler);
                    else {
                        Log.e(Config.TAG, "WiFi Aware Manager is not available");
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
                        Log.d(Config.TAG,"findPeer found Aware "+id);
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
                        Log.d(Config.TAG,"findConnectionWithTransientID() found a match for Aware ID "+id+" in "+connection.getDevice().getLabel());
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
                    Log.d(Config.TAG, "WiFi Aware ID " + netId + " does not match an existing device, creating a new device");
                } else
                    Log.d(Config.TAG, "WiFi Aware ID " + netId + " matches existing device: " + device.getLabel());
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "New WiFi Aware connection (" + peerHandle.hashCode() + ") for " + device.getLabel());
                device.setConnected(0, false, true);
                old = new Connection(peerHandle, device);
                old.setLastConnection();
                connections.add(old);
            }
            SqAnService.burstVia(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),TransportPreference.WIFI);
        } else {
            Log.d(Config.TAG,"updatePeer() found existing connection to Aware "+peerHandle.hashCode());
            old.setLastConnection();
        }
    }

    private void startAdvertising() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware - startAdvertising()");
            if (discoverySession != null) {
                Log.d(Config.TAG,"Aware closing discovery session");
                discoverySession.close();
                discoverySession = null;
                setStatus(Status.CHANGING_MEMBERSHIP);
            }
            if (awareSession != null) {
                setStatus(Status.ADVERTISING);
                awareSession.publish(configPub, new DiscoverySessionCallback() {
                    @Override
                    public void onPublishStarted(PublishDiscoverySession session) {
                        Log.d(Config.TAG, "onPublishStarted()");
                        discoverySession = session;
                        if (listener != null)
                            listener.onStatus(status);
                    }

                    @Override
                    public void onMessageReceived(final PeerHandle peerHandle, final byte[] message) {
                        if (role == Role.NONE) {
                            setStatus(Status.CONNECTED);
                            role = Role.HUB;
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onMessageReceived() - changing role to "+role.name());
                            Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.HUB);
                        } else
                            Log.d(Config.TAG, "onMessageReceived()");
                        if (handler != null)
                            handler.post(() -> {
                                updatePeer(peerHandle);
                                handleMessage(findConnection(peerHandle),message);
                            });
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onSubscribeStarted(final SubscribeDiscoverySession session) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onSubscribeStarted()");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                    }

                    @Override
                    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onServiceDiscovered()");
                        setStatus(Status.CONNECTED);
                        if (role == Role.NONE) {
                            role = Role.SPOKE;
                            Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.SPOKE);
                        }
                        if (handler != null)
                            handler.post(() -> updatePeer(peerHandle));
                        if (listener != null)
                            listener.onStatus(status);
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onMessageSendSucceeded(int messageId) {
                        super.onMessageSendSucceeded(messageId);
                        Log.d(Config.TAG,"Aware message was successfully sent"); //TODO
                    }

                    @Override
                    public void onSessionConfigFailed() {
                        role = Role.NONE;
                        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session configuration failed");
                    }

                    @Override
                    public void onSessionTerminated() {
                        role = Role.NONE;
                        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session terminated");
                    }

                    @Override
                    public void onSessionConfigUpdated() {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION, "Aware session configuration upated");
                    }

                    @Override
                    public void onMessageSendFailed(int messageId) {
                        Log.w(Config.TAG,"Aware message "+messageId+" failed");
                    }
                }, handler);
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
            Log.d(Config.TAG,"WiFi Aware received a message from "+device.getLabel()+" (Aware "+((connection.getPeerHandle()==null)?"unk peer handle":connection.getPeerHandle().hashCode())+")");
            device.setHopsAway(packet.getCurrentHopCount(), false, true);
            device.setLastConnect();
            device.addToDataTally(message.length);
            if (packet.isDirectFromOrigin())
                connection.setDevice(device);
        }
        relayPacketIfNeeded(connection,message,packet.getSqAnDestination(),packet.getOrigin(),packet.getCurrentHopCount());
        super.onReceived(packet);
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
                                Log.e(Config.TAG,"Exception when trying to send packet to "+device.getLabel()+" over Aware: "+e.getMessage());
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
                Log.d(Config.TAG,"Stopping previous Aware discoverySession to start new discoverySession");
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
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onSubscribeStarted()");
                        discoverySession = session;
                        setStatus(Status.CONNECTED);
                    }

                    @Override
                    public void onServiceDiscovered(PeerHandle peerHandle, byte[] serviceSpecificInfo, List<byte[]> matchFilter) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Aware onServiceDiscovered()");
                        if (role == Role.NONE) {
                            role = Role.SPOKE;
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware onServiceDiscovered() - changing role to "+role.name());
                            Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.SPOKE);
                            setStatus(Status.CONNECTED);
                        }
                        if (handler != null)
                            handler.post(() -> updatePeer(peerHandle));
                        if (listener != null)
                            listener.onStatus(status);
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onMessageReceived(final PeerHandle peerHandle, final byte[] message) {
                        if (role == Role.NONE) {
                            role = Role.SPOKE;
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Aware onMessageReceived() - changing role to "+role.name());
                            Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.SPOKE);
                            setStatus(Status.CONNECTED);
                        } else
                            Log.d(Config.TAG, "Aware onMessageReceived()");
                        if (handler != null)
                            handler.post(() -> {
                                updatePeer(peerHandle);
                                handleMessage(findConnection(peerHandle),message);
                            });
                        connectToPeer(peerHandle);
                    }

                    @Override
                    public void onMessageSendSucceeded(int messageId) {
                        super.onMessageSendSucceeded(messageId);
                        Log.d(Config.TAG,"Aware message "+messageId+" was successfully sent");
                    }

                    @Override
                    public void onSessionConfigFailed() {
                        role = Role.NONE;
                        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session configuration failed");
                    }

                    @Override
                    public void onSessionTerminated() {
                        role = Role.NONE;
                        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Aware session terminated");
                    }

                    @Override
                    public void onSessionConfigUpdated() {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION, "Aware session configuration upated");
                    }

                    @Override
                    public void onMessageSendFailed(int messageId) {
                        Log.w(Config.TAG,"Aware message "+messageId+" failed");
                    }
                }, handler);
            }
        }
    }

    private void connectToPeer(PeerHandle peerHandle) {
        Connection connection = findPeer(peerHandle);
        if (connection == null) {
            burst(new HeartbeatPacket(Config.getThisDevice(), HeartbeatPacket.DetailLevel.MEDIUM),peerHandle);
            updatePeer(peerHandle);
            connection = findPeer(peerHandle);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if ((peerHandle == null) || (discoverySession == null) || (connection == null))
                return;
            if (connection.getCallback() != null) //this connection is already being handled
                return;

            Log.d(Config.TAG,"Establishing Aware network conection to "+peerHandle.hashCode());
            connection.setCallback(new AwareManetConnectionCallback(connection));
            NetworkSpecifier networkSpecifier = discoverySession.createNetworkSpecifierPassphrase(peerHandle,conformPasscodeToWiFiAwareRequirements(Config.getPasscode()));
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(networkSpecifier)
                    .build();

            connectivityManager.requestNetwork(networkRequest,connection.getCallback());
        }
    }


    private final static int MIN_PASSPHRASE_LENGTH = 8; //FIXME unable to find these definitively, so they are guesses
    private final static int MAX_PASSPHRASE_LENGTH = 63; //FIXME unable to find these definitively, so they are guesses
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
        Log.d(Config.TAG,"Passphrase \""+passcode+"\" adjusted to \""+output+"\" to conform with WiFi Aware requirements");
        return out.toString();
    }

    //FIXME use the identity change listener to get the Aware MAC and know when it changes
    //FIXME https://android.googlesource.com/platform/frameworks/base/+/master/wifi/java/android/net/wifi/aware/IdentityChangedListener.java

    /*******************/

    @Override
    public void onServerBacklistClient(InetAddress address) {
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
        if (isRunning.get()) {
            if (socketClient == null) {
                //TODO
            }
        }
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
            if (socketClient != null) {//packets from clients always get sent to the server
                Log.d(Config.TAG,"burst(packet) sent as a Client");
                burst(packet, (SqAnDevice)null);
            } else if (socketServer != null) {
                Log.d(Config.TAG,"burst(packet) sent as a Server");
                SqAnDevice device = SqAnDevice.findByUUID(packet.getSqAnDestination());
                if (device == null) {
                    boolean multiHopNeeded = false;
                    if (packet.getSqAnDestination() == PacketHeader.BROADCAST_ADDRESS) {
                        if (connections != null)
                            burst(packet, (SqAnDevice)null);
                    } else {
                        boolean found = false;
                        //TODO
                        multiHopNeeded = found;
                    }
                    if (multiHopNeeded) {
                        //TODO try to find a node that can reach this device
                        //TODO for multi-hop
                    }
                } else //the destination device is in this manet
                    burst(packet, device);
            }
        } else
            Log.d(Config.TAG,"Cannot send null packet");
    }

    private void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        Log.d(Config.TAG,"Packet burst...");
        if (packet != null) {
            boolean sent = false;
            if ((socketClient != null) && socketClient.isReady())
                sent = socketClient.burst(packet); //clients only send packets to the server
            if (socketServer != null) { //but servers can send packets to clients that are destined for different devices
                if (device == null)
                    socketServer.burst(packet, packet.getSqAnDestination());
                else
                    socketServer.burst(packet, device.getUUID());
                sent = true;
            }
            if (!sent) {
                if ((connections != null) && !connections.isEmpty()) {
                    synchronized (connections) {
                        for (Connection connection:connections) {
                            if ((connection.getDevice() != null) && (connection.getPeerHandle() != null)) {
                                if (AddressUtil.isApplicableAddress(connection.getDevice().getUUID(), packet.getSqAnDestination())) {
                                    Log.d(Config.TAG,"Failing over to send packet to "+connection.getDevice().getLabel()+" via Aware message (socket connection not available)");
                                    burst(packet, connection.getPeerHandle());
                                    sent = true;
                                }
                            }
                        }
                    }
                } else
                    Log.d(Config.TAG, "Aware tried to burst but no nodes available to receive");
            }
            if (sent) {
                if (listener != null)
                    listener.onTx(packet);
            }
        } else
            Log.d(Config.TAG,"Trying to burst over manet but packet was null");
    }

    /*******************/

    private void burst(AbstractPacket packet, PeerHandle peerHandle) {
        if (packet == null) {
            Log.d(Config.TAG,"Aware Cannot send empty packet");
            return;
        }
        burst(packet.toByteArray(),peerHandle);
    }

    private void burst(final byte[] bytes, final PeerHandle peerHandle) {
        if (bytes == null) {
            Log.d(Config.TAG,"Aware cannot send empty byte array");
            return;
        }
        if (peerHandle == null) {
            Log.d(Config.TAG,"Aware cannot send packet to an empty PeerHandle");
            return;
        }
        if (discoverySession == null) {
            Log.d(Config.TAG,"Aware cannot send packet as no discoverySession exists");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (bytes.length > getMaximumPacketSize()) {
                Log.d(Config.TAG, "Packet larger than WiFi Aware max; segmenting and sending");
                //TODO segment and burst
            } else {
                if (discoverySession != null)
                    handler.post(() -> {
                        Log.d(Config.TAG,"Aware sending a "+bytes.length+"b message "+(messageIds.get()+1)+" to "+peerHandle.hashCode());
                        discoverySession.sendMessage(peerHandle, messageIds.incrementAndGet(), bytes);
                        ManetOps.addBytesToTransmittedTally(bytes.length);
                    });
                else
                    Log.w(Config.TAG,"Cannot send burst as discovery session is null");
            }
        } else
            Log.d(Config.TAG,"Cannot burst, WiFi Aware is not supported");
    }

    private void stopSocketConnections(boolean announce) {
        if (socketClient != null) {
            socketClient.close();
            Log.d(Config.TAG,"socketClient closing...");
            socketClient = null;
        }
        if (socketServer != null) {
            socketServer.close(announce);
            Log.d(Config.TAG,"socketServer closing...");
            socketServer = null;
        }
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
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
            Log.d(Config.TAG, "findOrCreateHub()");
            if ((awareSession != null) && (role == Role.NONE)) {
                if (oob && false) { //TODO skipping OOB discovery for now
                    ArrayList<SqAnDevice> devices = TeammateConnectionPlanner
                            .getDescendingPriorityWiFIConnections(SqAnDevice.getWiFiAwareDevices());
                    if ((devices == null) || devices.isEmpty()) {
                        findOrCreateHub(false);
                        return;
                    }
                    if (handler != null) {
                        handler.postDelayed(() -> {
                            findOrCreateHub(false);
                        }, INTERVAL_BEFORE_FALLBACK_DISCOVERY);
                    }
                } else {
                    startDiscovery();
                    if (handler != null) {
                        handler.postDelayed(() -> {
                            if (role == Role.NONE) {
                                Log.d(Config.TAG, "no existing network found");
                                setStatus(Status.CHANGING_MEMBERSHIP);
                                assumeHubRole();
                            } else
                                Log.d(Config.TAG, "No need to change roles (currently " + role.name() + ") as it appears discovery was successful");
                        }, INTERVAL_LISTEN_BEFORE_PUBLISH);
                    }
                }
            }
        }
    }

    /**
     * Take over the role as the Hub for this mesh
     */
    private void assumeHubRole() {
        Log.d(Config.TAG,"Assuming the hub role");
        startAdvertising();
    }

    @Override
    public void disconnect() throws ManetException {
        super.disconnect();
        stopSocketConnections(true);
        if ((connections != null) && !connections.isEmpty() && (connectivityManager != null)) {
            synchronized (connections) {
                for (Connection connection:connections) {
                    if (connection.getCallback() != null)
                        connectivityManager.unregisterNetworkCallback(connection.getCallback());
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
                Log.d(Config.TAG,"Attempting to restart WiFi Aware manager");
                init();
            } catch (ManetException e) {
                Log.e(Config.TAG, "Unable to initialize WiFi Aware: " + e.getMessage());
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
            WifiAwareManager mgr = (WifiAwareManager) context.getSystemService(Context.WIFI_AWARE_SERVICE);
            if (mgr != null) {
                Log.d(Config.TAG, "WiFi Aware state changed: " + (mgr.isAvailable() ? "available" : "not available"));
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
        private final Connection connection;
        public AwareManetConnectionCallback(Connection connection) {
            super();
            this.connection = connection;
        }

        @Override
        public void onAvailable(Network network) {
            Log.d(Config.TAG,"Aware onAvailable() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            //TODO
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
            Log.d(Config.TAG,"Aware onLinkPropertiesChanged() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            try {
                NetworkInterface awareNi = NetworkInterface.getByName(linkProperties.getInterfaceName());
                Enumeration inetAddresses = awareNi.getInetAddresses();
                Inet6Address ipv6 = null;
                while (inetAddresses.hasMoreElements()) {
                    InetAddress addr = (InetAddress) inetAddresses.nextElement();
                    if (addr instanceof Inet6Address) {
                        if (addr.isLinkLocalAddress()) {
                            ipv6 = (Inet6Address)addr;
                            if (!ipv6.equals(Config.getThisDevice().getAwareServerIp())) {
                                Log.d(Config.TAG,"Aware IP address changed to "+ipv6.getHostAddress());
                                stopSocketConnections(false);
                            }
                            break;
                        }
                    }
                }
                if (connection.getDevice() == null)
                    return;
                if (ipv6 != null) {
                    SqAnDevice other = connection.getDevice();
                    if (shouldLocalDeviceHost(other)) {
                        Log.d(Config.TAG,"Aware server connection with "+other.getLabel()+" should be hosted by this device");
                        socketServer = new Server(new SocketChannelConfig(null, SQAN_PORT), parser, WiFiAwareManet.this);
                        socketServer.start();
                        Config.getThisDevice().setAwareServerIp(ipv6);
                    } else {
                        Log.d(Config.TAG,"Aware server connection with "+other.getLabel()+" should be hosted by the other device; waiting a short time to allow the server to set-up");
                        socketClient = new Client(new SocketChannelConfig(other.getAwareServerIp().getHostAddress(), SQAN_PORT), parser);
                        handler.postDelayed((Runnable) () -> {
                            if ((socketClient != null) && isRunning.get()) {
                                Log.d(Config.TAG,"Aware starting client connection");
                                socketClient.start();
                            }
                        },DELAY_BEFORE_CONNECTING_TO_SERVER);
                        Config.getThisDevice().clearAwareServerIp();
                    }
                } else
                    Config.getThisDevice().clearAwareServerIp();
            } catch (SocketException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onLost(Network network) {
            Log.d(Config.TAG,"Aware onLost() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
            //TODO
        }
    }

    /**
     * Decides if this device or the remote other device should be the server
     * @param other
     * @return true == this device should be the server; false == other device is assumed to be the server
     */
    private boolean shouldLocalDeviceHost(SqAnDevice other) {
        if ((socketServer != null) || (other == null))
            return true;
        if (other.isAwareServer())
            return false;
        SqAnDevice thisDevice = Config.getThisDevice();
        if (thisDevice == null)
            return false;
        if ((thisDevice.getRelayConnections() != null) && (other.getRelayConnections() != null)) //favor fewer connections
            return thisDevice.getRelayConnections().size() < other.getRelayConnections().size();
        return thisDevice.getUUID() > other.getUUID();
    }
}
