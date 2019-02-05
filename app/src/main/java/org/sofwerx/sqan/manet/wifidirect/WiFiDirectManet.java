package org.sofwerx.sqan.manet.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.common.sockets.server.Server;
import org.sofwerx.sqan.manet.common.sockets.server.ServerStatusListener;
import org.sofwerx.sqan.manet.wifi.Util;
import org.sofwerx.sqan.util.CommsLog;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet implements WifiP2pManager.PeerListListener, WiFiDirectDiscoveryListener, ServerStatusListener {
    private final static int SQAN_PORT = 1716; //using the America's Army port to avoid likely conflicts
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
    private final PacketParser parser;
    private boolean priorWiFiStateEnabled = true;

    public WiFiDirectManet(Handler handler, Context context, ManetListener listener) {
        super(handler,context,listener);
        channel = null;
        manager = null;
        parser = new PacketParser(this);
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
                Log.d(Config.TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION");
                if (manager == null)
                    return;
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    manager.requestConnectionInfo(channel, info -> {
                        Log.d(Config.TAG,"Group formed "+info.groupFormed+", Is owner "+info.isGroupOwner);
                        if (info.isGroupOwner) {
                            manager.requestGroupInfo(channel, groupInfo -> {
                                if (groupInfo == null) {
                                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Group is formed, but groupInfo is null");
                                } else {
                                    CommsLog.log(CommsLog.Entry.Category.STATUS,"Group "+groupInfo.getNetworkName()+" formed");
                                    //WiFiGroup group = new WiFiGroup(groupInfo.getNetworkName(), groupInfo.getPassphrase());
                                }
                            });
                            startServer();
                        } else {
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
                            }
                        }
                    });
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                Log.d(Config.TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
                onDeviceChanged(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));
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
    public void onNodeLost(SqAnDevice node) {
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
        nsd.startAdvertising(manager, channel);
        manager.requestPeers(channel, WiFiDirectManet.this);
        setStatus(Status.DISCOVERING);
    }

    /**
     * Starts this device as a server on this channel
     */
    private void startServer() {
        if (socketServer == null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Starting server...");
            socketServer = new Server(new SocketChannelConfig(null, SQAN_PORT), parser, this);
            socketServer.start();
        }
    }

    /**
     * Starts this device as a client on this channel
     */
    private void startClient(String serverIp) {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Connecting as Client to "+serverIp+"...");
        socketClient  = new Client(new SocketChannelConfig(serverIp,SQAN_PORT),parser);
        socketClient.start();
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

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
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
                Log.d(Config.TAG,"Trying to burst over manet but packet was null");
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
        if (socketClient != null) {
            socketClient.close();
            Log.d(Config.TAG,"socketClient closing...");
            socketClient = null;
        }
        if (socketServer != null) {
            socketServer.close();
            Log.d(Config.TAG,"socketServer closing...");
            socketServer = null;
        }
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

    private void onHardwareChanged(boolean enabled) {
        Log.d(Config.TAG,"WiFi Direct status changed to "+(enabled?"Enabled":"disabled"));
        //TODO
    }

    private void onDeviceChanged(WifiP2pDevice device) {
        if (device != null) {
            if ((teammates != null) && teammates.contains(device)) {
                Log.d(Config.TAG, device.deviceName + " changed to status " + Util.getDeviceStatusString(device.status) + (device.isGroupOwner() ? " (group owner" : ""));
            }
        }
    }

    private void onDisconnected() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Channel disconnected");
        //TODO
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
                                Config.SavedTeammate teammate = Config.getTeammate(peer.deviceAddress);
                                if (peer.status == WifiP2pDevice.AVAILABLE) {
                                    if ((teammate != null) && (teammate.getSqAnAddress() != PacketHeader.BROADCAST_ADDRESS)) {
                                        SqAnDevice device = SqAnDevice.findByUUID(teammate.getSqAnAddress());
                                        if ((socketClient == null) && (socketServer == null) && (serverDevice == null) && peer.isGroupOwner()) {
                                            serverDevice = peer;
                                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Hub found at " + serverDevice.deviceAddress + ", starting client...");
                                            connectToDevice(peer);
                                            startClient(serverDevice.deviceAddress);
                                        } else
                                            connectToDevice(peer);
                                        if (device == null) {
                                            device = new SqAnDevice(teammate.getSqAnAddress());
                                            if (teammate.getCallsign() != null)
                                                device.setCallsign(teammate.getCallsign());
                                            if (listener != null) {
                                                listener.onDevicesChanged(device);
                                                listener.updateDeviceUi(device);
                                            }
                                        }
                                        CommsLog.log(CommsLog.Entry.Category.COMMS, "Device matching saved teammate " + teammate.getCallsign() + " found");
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
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        String callsign = null;
        try {
            int sqAnAddress = Integer.parseInt(device.deviceName);
            SqAnDevice sqAnDevice = SqAnDevice.findByUUID(sqAnAddress);
            if (sqAnDevice == null)
                sqAnDevice = new SqAnDevice(sqAnAddress);
            Config.SavedTeammate saved = Config.getTeammate(sqAnAddress);
            if (saved != null) {
                callsign = saved.getCallsign();
                if (callsign != null)
                    sqAnDevice.setCallsign(callsign);
            }
        } catch (NumberFormatException ignore) {
        }
        CommsLog.log(CommsLog.Entry.Category.COMMS,"Attempting to connect to "+((callsign==null)?device.deviceName:callsign));
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                CommsLog.log(CommsLog.Entry.Category.COMMS, "Successfully connected to "+config.deviceAddress);
                if (socketClient == null) {
                    if ((serverDevice != null) && (socketServer == null))
                        startClient(serverDevice.deviceAddress);
                    else {
                        CommsLog.log(CommsLog.Entry.Category.COMMS, "Connected to " + config.deviceAddress + ", but socketClient not started as no server identified yet");
                        manager.requestPeers(channel,WiFiDirectManet.this);
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

    /*@Override
    public void onGroupDiscovered(WiFiGroup group) {
        if (group != null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Attempting to join "+group.getSsid()+"...");
            WifiConfiguration wifiConfig = new WifiConfiguration();
            wifiConfig.SSID = String.format("\"%s\"", group.getSsid()); //needs quotes to work
            wifiConfig.preSharedKey = String.format("\"%s\"", group.getPassword()); //needs quotes to work

            int netId = wifiManager.addNetwork(wifiConfig);
            wifiManager.enableNetwork(netId, false);
            wifiManager.reconnect();
        }
    }

    private void formWiFiGroup() {
        if ((socketClient == null) && (socketServer == null) && (manager != null) &&(channel != null)) {
            if (!hasPausedForTeammates) { //if there are other teammates around, wait a bit to see if they already have a network
                hasPausedForTeammates = true;
                if ((teammates != null) && !teammates.isEmpty()) {
                    Log.d(Config.TAG,"Teammates are present, so waiting a bit longer before starting a group.");
                    if (handler != null)
                        handler.postDelayed(() -> formWiFiGroup(),DELAY_BEFORE_FORM_GROUP_WHEN_TEAMMATES_PRESENT);
                    return;
                }
            }
            CommsLog.log(CommsLog.Entry.Category.STATUS, "No existing network found, so starting one...");
            //nsd.stopDiscovery(manager,channel,null);
            manager.createGroup(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    //ignored as this is handled in the BroadcastReceiver
                }

                @Override
                public void onFailure(int reason) {
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Could not form new group: "+Util.getFailureStatusString(reason));
                    nsd.startDiscovery(manager,channel);
                    if (handler != null)
                        handler.postDelayed(() -> formWiFiGroup(),DELAY_BEFORE_FORM_GROUP);
                }
            });
        }
    }*/

    @Override
    public void onDiscoveryStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Discovery Started");
        if (status == Status.ADVERTISING)
            setStatus(Status.ADVERTISING_AND_DISCOVERING);
        else
            setStatus(Status.DISCOVERING);
        /*if (handler != null) {
            final long delay;
            if ((teammates == null) || teammates.isEmpty())
                delay = DELAY_BEFORE_FORM_GROUP;
            else
                delay = DELAY_BEFORE_FORM_GROUP_WHEN_TEAMMATES_PRESENT;
            handler.postDelayed(() -> formWiFiGroup(), delay);
        }*/
    }

    @Override
    public void onAdvertisingStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Advertising Started");
        if (status == Status.DISCOVERING)
            setStatus(Status.ADVERTISING_AND_DISCOVERING);
        else
            setStatus(Status.ADVERTISING);
    }

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
    public void onServerClientDisconnected(InetAddress address) {
        if (address != null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, address.toString()+" disconnected");
            //ignore this for now; maybe revisited later to help address mesh changes
        }
    }
}
