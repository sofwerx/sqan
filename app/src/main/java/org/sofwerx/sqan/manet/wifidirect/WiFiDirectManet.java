package org.sofwerx.sqan.manet.wifidirect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
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
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
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
public class WiFiDirectManet extends AbstractManet implements WifiP2pManager.PeerListListener, WiFiDirectDiscoveryListener, ServerStatusListener {
    private final static int SQAN_PORT = 1716; //using the America's Army port to avoid likely conflicts
    private WifiP2pManager.Channel channel;
    private WifiP2pManager manager;
    private List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private List<WifiP2pDevice> teammates = new ArrayList<WifiP2pDevice>();
    private WiFiDirectNSD nsd;
    private Client socketClient = null;
    private Server socketServer = null;
    private WifiP2pDevice serverDevice = null;
    private final PacketParser parser;

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
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                onHardwareChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                Log.d(Config.TAG, "P2P peers changed");
                if (manager != null)
                    manager.requestPeers(channel, WiFiDirectManet.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager == null)
                    return;
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);

                if (networkInfo.isConnected()) {
                    manager.requestConnectionInfo(channel, info -> {
                        Log.d(Config.TAG,"Group formed "+info.groupFormed+", Is owner "+info.isGroupOwner);
                        if (info.isGroupOwner)
                            startServer();
                        else {
                            String hostAddress = null;
                            if ((info != null) && (info.groupOwnerAddress != null))
                                hostAddress = info.groupOwnerAddress.getHostAddress();
                            if (hostAddress != null) {
                                if ((socketServer == null) && (socketClient == null))
                                    startClient(hostAddress);
                                if ((peers != null) && !peers.isEmpty()) {
                                    for (WifiP2pDevice peer : peers) {
                                        if (hostAddress.equalsIgnoreCase(peer.deviceAddress)) {
                                            serverDevice = peer;
                                            if (addTeammateIfNeeded(serverDevice)) {
                                                //connectToDevice(serverDevice);
                                                //break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        if ((serverDevice != null) && (socketServer == null) && (socketClient == null))
                            startClient(serverDevice.deviceAddress);
                    });
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                onDeviceChanged(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));
            }
        }
    };

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        //if (NetUtil.isWiFiConnected(context))
        //    SqAnService.onIssueDetected(new WiFiIssue(false,"WiFi is connected to another network"));
        //TODO
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
        channel = manager.initialize(context, (handler!=null)?handler.getLooper():null, () -> onDisconnected());

        context.registerReceiver(hardwareStatusReceiver, intentFilter);
        nsd = new WiFiDirectNSD(this);
        nsd.startDiscovery(manager,channel);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
    }

    /**
     * Starts this device as a server on this channel
     */
    private void startServer() {
        socketServer = new Server(new SocketChannelConfig(null,SQAN_PORT),parser,this);
        socketServer.start();
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
                    SqAnDevice device = SqAnDevice.findBySqAnAddress(packet.getSqAnDestination());
                    if (device == null) {
                        boolean multiHopNeeded = false;
                        if (packet.getSqAnDestination() == PacketHeader.BROADCAST_ADDRESS) {
                            if (teammates != null) {
                                burst(packet, null); //FIXME workaround since the Server isn't building a good list of SqAnDevices yet
                                /*for (WifiP2pDevice wifiDev : teammates) {
                                    device = SqAnDevice.findByNetworkID(wifiDev.deviceAddress);
                                    if (device != null)
                                        burst(packet, device);
                                }*/
                            }
                        } else {
                            boolean found = false;
                            if (teammates != null) {
                                burst(packet, null); //FIXME workaround since the Server isn't building a good list of SqAnDevices yet
                            }
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
                    socketServer.burst(packet, device.getSqanAddress());
                sent = true;
            }
            if (!sent)
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
        Log.d(Config.TAG,"Disconnecting WiFiDirectManet...");
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver); //FIXME, this is being treated as a different receiver for some reason (context maybe?) and not unregistering but causing the original receiver to leak
                Log.d(Config.TAG,"WiFi Direct Broadcast Receiver unregistered");
                hardwareStatusReceiver = null;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
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
        }
        if (nsd != null) {
            nsd.stopDiscovery(manager, channel, null);
            Log.d(Config.TAG,"Net Service Discovery closing...");
            nsd = null;
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
        if ((manager == null) || (channel == null)) {
            Log.e(Config.TAG,"connectToDevice called, but manager or channel are null");
            return;
        }
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;

        Log.e(Config.TAG,"Attempting to connect to "+device.deviceName);
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                SqAnDevice device = SqAnDevice.findByNetworkID(config.deviceAddress);
                if (device != null) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"connected to device "+config.deviceAddress);
                    device.setStatus(SqAnDevice.Status.CONNECTED);
                    device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Disconnected"));
                    listener.onDevicesChanged(device);
                    //if (socketServer == null) //if this device isnt already a server, then it should act like a client
                    //    startClient(config.);
                } else {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, config.deviceAddress+" connected so I added it");
                    device = new SqAnDevice();
                    device.setNetworkId(config.deviceAddress);
                }
                if ((serverDevice != null) && (socketServer == null) && (socketClient == null))
                    startClient(serverDevice.deviceAddress);
            }

            @Override
            public void onFailure(int reason) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Failed to connect to device: "+Util.getFailureStatusString(reason));
            }
        });
    }

    @Override
    public void onDeviceDiscovered(WifiP2pDevice wifiP2pDevice) {
        if (addTeammateIfNeeded(wifiP2pDevice))
            connectToDevice(wifiP2pDevice);
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
                SqAnDevice device = SqAnDevice.findByNetworkID(wifiP2pDevice.deviceAddress);
                if (device != null) {
                    device.setStatus(SqAnDevice.Status.ONLINE);
                    device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Disconnected"));
                    listener.onDevicesChanged(device);
                } else {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, wifiP2pDevice.deviceName+"("+wifiP2pDevice.deviceAddress+")"+"found so I added it");
                    device = new SqAnDevice();
                    device.setNetworkId(wifiP2pDevice.deviceAddress);
                }
                CommsLog.log(CommsLog.Entry.Category.STATUS, "teammate "+wifiP2pDevice.deviceName + " discovered ("+teammates.size()+" total teammates)");
            }
        }
        return added;
    }

    @Override
    public void onDiscoveryError(String error) {
        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Discovery error: "+error);
        //TODO
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
        //TODO
    }

    @Override
    public void onServerNumberOfConnections(int currentConnections) {
        //TODO
    }

    @Override
    public void onServerClientDisconnected(InetAddress address) {
        if (address != null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS, address.toString()+" disconnected");
            //SqAnDevice device = SqAnDevice.findBySqAnAddress(AddressUtil.getSqAnAddress(address));
        }
    }

    @Override
    public void onServerClientConnected(int sQAnAddress) {
        setStatus(Status.CONNECTED);
        SqAnDevice device = SqAnDevice.findBySqAnAddress(sQAnAddress);
        if (device != null)
            device.setConnected();
    }
}
