package org.sofwerx.sqan.manet.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.common.sockets.server.Server;
import org.sofwerx.sqan.manet.common.sockets.server.ServerStatusListener;
import org.sofwerx.sqan.util.CommsLog;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet implements WifiP2pManager.PeerListListener, WiFiDirectDiscoveryListener, WifiP2pManager.ConnectionInfoListener, ServerStatusListener {
    private final static int SQAN_PORT = 1716; //using the America's Army port to avoid likely conflicts
    private final static String FALLBACK_GROUP_OWNER_IP = "192.168.49.1"; //the WiFi Direct Group owner always uses this address; hardcoding it is a little hackish, but is used when all other IP detection method attempts fail
    private final static long DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN = 1000l * 60l;
    private final static long DELAY_BEFORE_CONNECTING = 1000l * 15l; //delay to prevent two devices from trying to connect at the same time
    private WifiP2pManager.Channel channel;
    private WifiP2pManager manager;
    private WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;
    private List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private List<WifiP2pDevice> teammates = new ArrayList<WifiP2pDevice>();
    private WiFiDirectNSD nsd;
    private Client socketClient = null;
    private Server socketServer = null;
    private WifiP2pDevice serverDevice = null;
    private boolean priorWiFiStateEnabled = true;
    private long nextAllowablePeerConnectionAttempt = Long.MIN_VALUE;
    private final static long MIN_TIME_BETWEEN_PEER_CONNECTIONS = 1000l * 10l;
    private WifiP2pDevice thisDevice = null; //this device as reported by WiFiP2P methods

    public WiFiDirectManet(Handler handler, Context context, ManetListener listener) {
        super(handler,context,listener);
        channel = null;
        manager = null;
    }

    @Override
    public ManetType getType() { return ManetType.WIFI_DIRECT; }

    private BroadcastReceiver hardwareStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                Log.d(Config.TAG, "WIFI_P2P_STATE_CHANGED_ACTION");
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                onHardwareChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                Log.d(Config.TAG, "WIFI_P2P_PEERS_CHANGED_ACTION");
                if (manager != null)
                    manager.requestPeers(channel, WiFiDirectManet.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager == null)
                    return;
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo == null)
                    return;
                if (networkInfo.isConnected()) {
                    Log.d(Config.TAG,"WIFI_P2P_CONNECTION_CHANGED_ACTION - network is now connected");
                    manager.requestConnectionInfo(channel, WiFiDirectManet.this);
                } else {
                    Log.d(Config.TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION - network is disconnected"+((networkInfo.getExtraInfo()==null)?"":": "+networkInfo.getExtraInfo()));
                    stopSocketConnections(false);
                    if (nsd == null) {
                        Log.e(Config.TAG,"NSD was null, this should never happen. Initializing again...");
                        nsd = new WiFiDirectNSD(WiFiDirectManet.this);
                    }
                    nsd.startDiscovery(manager,channel);
                    nsd.startAdvertising(manager, channel,false);
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice reportedDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (reportedDevice != null) {
                    Log.d(Config.TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: this device is "+reportedDevice.deviceName+(reportedDevice.isGroupOwner()?" (group owner)":" (group member)"));
                    thisDevice = reportedDevice;
                    if ((socketServer != null) && reportedDevice.isGroupOwner()) {
                        Log.d(Config.TAG,"WIFI_P2P_THIS_DEVICE_CHANGED_ACTION - device is group owner, starting Server");
                        startServer();
                    }
                } else
                    Log.d(Config.TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
            }
        }
    };

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        return passed;
    }

    @Override
    public String getName() { return "WiFi Direct®"; }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public void init() throws ManetException {
        isRunning.set(true);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        priorWiFiStateEnabled = wifiManager.isWifiEnabled();
        wifiManager.setWifiEnabled(true);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"sqan");
        wifiLock.acquire();
        channel = manager.initialize(context, (handler!=null)?handler.getLooper():null, () -> onDisconnected());
        context.registerReceiver(hardwareStatusReceiver, intentFilter);
        nsd = new WiFiDirectNSD(this);
        nsd.startDiscovery(manager,channel);
        nsd.startAdvertising(manager, channel,false);
        manager.requestPeers(channel, WiFiDirectManet.this);
        setStatus(Status.DISCOVERING);
    }

    /**
     * Starts this device as a server on this channel
     */
    private void startServer() {
        if (!isRunning.get())
            return;
        if (socketServer == null) {
            socketServer = new Server(new SocketChannelConfig(null, SQAN_PORT), parser, this);
            if ((manager != null) && (channel != null)) {
                if (nsd == null)
                    nsd = new WiFiDirectNSD(this);
                else
                    nsd.stopDiscovery(manager,channel,null);
                nsd.startAdvertising(manager,channel,true); //TODO trying to test forcing advertising again after connection
            }
            if (socketClient != null) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Switching from Client mode to Server mode...");
                socketClient.close(true);
            } else
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Starting server...");
            socketServer.start();
        }
    }

    /**
     * Starts this device as a client on this channel
     */
    private void startClient(String serverIp) {
        if (!isRunning.get())
            return;
        if (socketClient == null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, "Connecting as Client to " + serverIp + "...");
            if ((manager != null) && (channel != null)) {
                nsd.stopDiscovery(manager, channel, null);
            }
            socketClient = new Client(new SocketChannelConfig(serverIp, SQAN_PORT), parser);
            socketClient.start();
        }
    }

    @Override
    public void burst(AbstractPacket packet) throws ManetException {
        if (packet != null) {
            if (socketClient != null) {//packets from clients always get sent to the server
                Log.d(Config.TAG,"burst(packet) sent as a Client");
                burst(packet, null);
            } else {
                if (socketServer != null) {
                    Log.d(Config.TAG,"burst(packet) sent as a Server");
                    SqAnDevice device = SqAnDevice.findByUUID(packet.getSqAnDestination());
                    if (device == null) {
                        boolean multiHopNeeded = false;
                        if (packet.getSqAnDestination() == PacketHeader.BROADCAST_ADDRESS) {
                            if (teammates != null)
                                burst(packet, null);
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
            if (sent) {
                if (listener != null)
                    listener.onTx(packet);
            } else
                Log.d(Config.TAG,"No Server or Client available for packet burst");
        } else
            Log.d(Config.TAG,"Trying to burst over manet but packet was null");
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

    @Override
    public void disconnect() throws ManetException {
        super.disconnect();
        Log.d(Config.TAG,"Disconnecting WiFiManet...");
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver); //FIXME, this is being treated as a different receiver for some reason (context maybe?) and not unregistering but causing the original receiver to leak
                Log.d(Config.TAG,"WiFi Direct Broadcast Receiver unregistered");
                hardwareStatusReceiver = null;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
        if (nsd != null) {
            nsd.stopDiscovery(manager, channel, null);
            nsd.stopAdvertising(manager, channel, null);
            Log.d(Config.TAG,"Net Service Discovery closing...");
            nsd = null;
        }
        if (wifiLock != null) {
            try {
                wifiLock.release();
            } catch (Exception ignore) {
            }
            wifiLock = null;
        }
        if (wifiManager != null) {
            try {
                wifiManager.setWifiEnabled(priorWiFiStateEnabled);
                wifiManager.disconnect();
            } catch (Exception ignore) {
            }
            wifiManager = null;
        }
        stopSocketConnections(true);
        if ((manager != null) && (channel != null)) {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() { Log.d(Config.TAG, "WiFiDirectManager cancelled connection"); }
                @Override
                public void onFailure(int reason) { Log.d(Config.TAG, "WiFiDirectManager failed to cancel connection: " + Util.getFailureStatusString(reason)); }
            });
            try {
                if (Build.VERSION.SDK_INT >= 27)
                    channel.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        if (directConnection && ((socketClient != null) || (Config.getThisDevice().getRoleWiFi() == SqAnDevice.NodeRole.SPOKE))) {
            stopSocketConnections(true);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Hub lost");
        }
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

    private void onHardwareChanged(boolean enabled) {
        Log.d(Config.TAG,"WiFi Direct status changed to "+(enabled?"Enabled":"disabled"));
        if (enabled) {
            if (isRunning.get() && (manager != null) && (channel != null))
                manager.requestConnectionInfo(channel, WiFiDirectManet.this);
        } else
            stopSocketConnections(false);
    }

    private void onDisconnected() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Channel disconnected");
        stopSocketConnections(false);
        if (isRunning.get()) {
            if (nsd == null)
                nsd = new WiFiDirectNSD(WiFiDirectManet.this);
            nsd.startDiscovery(manager,channel);
            nsd.startAdvertising(manager, channel,false);
        }
    }

    @Override
    public void executePeriodicTasks() {
        //TODO
    }

    @Override
    public void onPeersAvailable(WifiP2pDeviceList peerList) {
        Collection<WifiP2pDevice> refreshedPeers = peerList.getDeviceList();
        if (refreshedPeers != null) {
            if (!refreshedPeers.equals(peers)) {
                peers.clear();
                peers.addAll(refreshedPeers);
                if (!peers.isEmpty()) {
                    if ((socketClient == null) && (socketServer == null)) { //connect to any peer that was a saved teammate
                        for (WifiP2pDevice peer:peers) {
                            Log.d(Config.TAG,"Peer: "+peer.deviceName+" status "+Util.getDeviceStatusString(peer.status));
                            try {
                                SavedTeammate teammate = Config.getTeammate(peer.deviceAddress);
                                if (peer.status == WifiP2pDevice.AVAILABLE) {
                                    if ((teammate != null) && (teammate.getSqAnAddress() != PacketHeader.BROADCAST_ADDRESS)) {
                                        SqAnDevice device = SqAnDevice.findByUUID(teammate.getSqAnAddress());
                                        if (device == null) {
                                            device = new SqAnDevice(teammate.getSqAnAddress());
                                            if (teammate.getCallsign() != null)
                                                device.setCallsign(teammate.getCallsign());
                                            if (listener != null) {
                                                listener.onDevicesChanged(device);
                                                listener.updateDeviceUi(device);
                                            }
                                        }
                                        String callsign = teammate.getCallsign();
                                        if (callsign == null)
                                            callsign = device.getCallsign();
                                        if (callsign == null)
                                            CommsLog.log(CommsLog.Entry.Category.COMMS, "Device matching saved teammate " + teammate.getSqAnAddress() + " found");
                                        else
                                            CommsLog.log(CommsLog.Entry.Category.COMMS, "Device matching saved teammate " + callsign + " found");

                                        if ((socketClient == null) && (socketServer == null)) {
                                            if (peer.isGroupOwner())
                                                connectToDevice(peer);
                                            else {
                                                if (thisDevice != null) {
                                                    if (Util.isHigherPriorityMac(thisDevice.deviceAddress,peer.deviceAddress)) {
                                                        if (!thisDevice.isGroupOwner())
                                                            connectToDevice(peer);
                                                    } else {
                                                        Log.d(Config.TAG,"The other peer teammate has a higher priority MAC so waiting a bit before attempting connection");
                                                        if (handler != null) {
                                                            handler.postDelayed(() -> {
                                                                if ((socketClient != null) && (socketServer != null) && !thisDevice.isGroupOwner())
                                                                    connectToDevice(peer);
                                                            }, DELAY_BEFORE_CONNECTING);
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        break;
                                    }
                                }
                            } catch (NumberFormatException ignore) {
                            }
                        }
                    }
                    Log.d(Config.TAG,peers.size()+" peers found");
                }
            }
        } else
            peers.clear();

        if (peers.size() == 0) {
            Log.d(Config.TAG, "No devices found");
            return;
        }
    }

    private void connectToDevice(WifiP2pDevice device) {
        if (device == null) {
            Log.e(Config.TAG,"cannot connect to a null device");
            return;
        }
        if (device.status != WifiP2pDevice.AVAILABLE) {
            Log.d(Config.TAG, "Cannot connect to a device that is "+Util.getDeviceStatusString(device.status));
            return;
        }
        if ((manager == null) || (channel == null)) {
            Log.e(Config.TAG,"connectToDevice called, but manager or channel are null");
            return;
        }
        if ((thisDevice != null) && thisDevice.isGroupOwner()) {
            Log.d(Config.TAG, "Group owners do not connect to other devices, they wait for connections. Ignoring connectToDevice call");
            return;
        }
        if (System.currentTimeMillis() < nextAllowablePeerConnectionAttempt) {
            Log.d(Config.TAG, "Recent peer connection attempt underway, skipping this peer for now.");
            return;
        }
        nextAllowablePeerConnectionAttempt = System.currentTimeMillis() + MIN_TIME_BETWEEN_PEER_CONNECTIONS;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        String callsign = null;
        try {
            int sqAnAddress = Integer.parseInt(device.deviceName);
            SqAnDevice sqAnDevice = SqAnDevice.findByUUID(sqAnAddress);
            if (sqAnDevice == null)
                sqAnDevice = new SqAnDevice(sqAnAddress);
            SavedTeammate saved = Config.getTeammate(sqAnAddress);
            if (saved != null) {
                callsign = saved.getCallsign();
                if (callsign != null)
                    sqAnDevice.setCallsign(callsign);
            }
            sqAnDevice.setNetworkId(device.deviceAddress);
        } catch (NumberFormatException ignore) {
        }
        CommsLog.log(CommsLog.Entry.Category.COMMS,"Attempting to connect to "+((callsign==null)?device.deviceName:callsign));
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                //FIXME testing this
                /*manager.requestGroupInfo(channel, group -> {
                    if (group == null)
                        Log.d(Config.TAG,"Connected to null group");
                    else {
                        Log.d(Config.TAG, group.toString());
                        Log.d(Config.TAG, "Group " + group.getNetworkName() + "; password: " + group.getPassphrase());
                    }
                });*/
                //FIXME testing this

                CommsLog.log(CommsLog.Entry.Category.COMMS, "Successfully connected to "+config.deviceAddress);
                if ((socketClient == null) && (socketServer == null)) {
                    if (serverDevice != null)
                        startClient(serverDevice.deviceAddress);
                    else {
                        CommsLog.log(CommsLog.Entry.Category.COMMS, "Connected to " + config.deviceAddress + ", but socketClient not started as no server identified yet");
                        manager.requestConnectionInfo(channel, WiFiDirectManet.this);
                    }
                }
            }

            @Override
            public void onFailure(int reason) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Failed to connect to device: "+Util.getFailureStatusString(reason));
            }
        });
    }

    @Override
    public void onDeviceDiscovered(WifiP2pDevice wifiP2pDevice) {
        if (addTeammateIfNeeded(wifiP2pDevice)) {
            CommsLog.log(CommsLog.Entry.Category.COMMS,"Teammate "+wifiP2pDevice.deviceName+" discovered");
            connectToDevice(wifiP2pDevice);
        }
    }
    @Override
    public void onDiscoveryStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Discovery Started");
        if (status == Status.ADVERTISING)
            setStatus(Status.ADVERTISING_AND_DISCOVERING);
        else
            setStatus(Status.DISCOVERING);
    }

    @Override
    public void onAdvertisingStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Advertising Started");
        if (status == Status.DISCOVERING)
            setStatus(Status.ADVERTISING_AND_DISCOVERING);
        else
            setStatus(Status.ADVERTISING);
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return true; }

    /**
     * Adds this device to the list of teammates
     * @param wifiP2pDevice
     * @return true == new device; false == already a teammate
     */
    private boolean addTeammateIfNeeded(WifiP2pDevice wifiP2pDevice) {
        boolean added = false;
        if (wifiP2pDevice != null) {
            if (teammates == null)
                teammates = new ArrayList<>();
            if (!teammates.contains(wifiP2pDevice)) {
                teammates.add(wifiP2pDevice);
                added = true;
                CommsLog.log(CommsLog.Entry.Category.STATUS, "teammate "+wifiP2pDevice.deviceName + " discovered ("+((teammates.size()>1)?(teammates.size()+" total teammates)"):" (only teammate at this time)"));
            }
        }
        return added;
    }

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
            if ((socketClient == null) && (manager != null) && (channel != null))
                manager.requestConnectionInfo(channel,WiFiDirectManet.this);
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
    public void onConnectionInfoAvailable(WifiP2pInfo info) {
        if (info != null) {
            Log.d(Config.TAG, "Group formed " + info.groupFormed + ", Is owner " + info.isGroupOwner);
            if (info.groupFormed == false) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Group formation not yet complete. Waiting a bit before checking again...");
                handler.postDelayed(() -> {
                    if ((socketClient == null) && (socketServer == null) && (manager != null) && (channel != null))
                        manager.requestConnectionInfo(channel,WiFiDirectManet.this);
                    else
                        Log.d(Config.TAG,"Looks like the WiFi group information was resolved, so taking no additional action.");
                }, DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN);
            }
            if (info.isGroupOwner)
                startServer();
            else {
                String hostAddress = null;
                if ((info != null) && (info.groupOwnerAddress != null))
                    hostAddress = info.groupOwnerAddress.getHostAddress();
                if (hostAddress != null) {
                    if ((peers != null) && !peers.isEmpty()) {
                        for (WifiP2pDevice peer : peers) {
                            if (hostAddress.equalsIgnoreCase(peer.deviceAddress)) {
                                serverDevice = peer;
                                addTeammateIfNeeded(serverDevice);
                            }
                        }
                    }
                    if ((socketServer == null) && (socketClient == null))
                        startClient(hostAddress);
                } else {
                    handler.postDelayed(() -> {
                        if ((socketClient != null) && (socketServer != null) && (manager != null) && (channel != null)) {
                            Log.d(Config.TAG,"Group ownership still not resolved, requesting details again");
                            manager.requestConnectionInfo(channel,WiFiDirectManet.this);
                        }
                    }, DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN);
                }
            }
        } else
            Log.e(Config.TAG, "manager.requestConnectionInfo cannot get info - this should not happen");
    }
}
