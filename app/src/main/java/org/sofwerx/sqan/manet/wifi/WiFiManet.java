package org.sofwerx.sqan.manet.wifi;

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
import org.sofwerx.sqan.util.CommsLog;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MANET built over Android's WiFi framework seperate from WiFi Direct™
 */
public class WiFiManet extends AbstractManet implements ServerStatusListener {
    private final static int SQAN_PORT = 1716; //using the America's Army port to avoid likely conflicts
    private final static long DELAY_BEFORE_FORM_GROUP_WHEN_TEAMMATES_PRESENT = 1000l*120l;
    private final static long DELAY_BEFORE_FORM_GROUP = 1000l*15l;
    private boolean hasPausedForTeammates = false;
    private WifiP2pManager.Channel channel;
    private WifiP2pManager manager;
    private WifiManager wifiManager;
    private WifiManager.WifiLock wifiLock;
    private List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private List<WifiP2pDevice> teammates = new ArrayList<WifiP2pDevice>();
    private Client socketClient = null;
    private Server socketServer = null;
    private WifiP2pDevice serverDevice = null;
    private final PacketParser parser;
    private boolean priorWiFiStateEnabled = true;

    public WiFiManet(Handler handler, Context context, ManetListener listener) {
        super(handler,context,listener);
        channel = null;
        manager = null;
        parser = new PacketParser(this);
    }

    @Override
    public ManetType getType() { return ManetType.WIFI; }

    /*private BroadcastReceiver hardwareStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION.equals(action)) {
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                onHardwareChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                Log.d(Config.TAG, "P2P peers changed");
                if (manager != null)
                    manager.requestPeers(channel, WiFiManet.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
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
                                    //adjusting this group to our presents
                                    hey dummy! stopped here - try changing the network name/password


                                    CommsLog.log(CommsLog.Entry.Category.STATUS,"Group "+groupInfo.getNetworkName()+" formed");
                                    WiFiGroup group = new WiFiGroup(groupInfo.getNetworkName(), groupInfo.getPassphrase());
                                    nsd.startAdvertising(manager, channel, group);
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
                onDeviceChanged(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));
            }
        }
    };*/

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        //if (NetUtil.isWiFiConnected(context))
        //    SqAnService.onIssueDetected(new WiFiIssue(false,"WiFi is connected to another network"));
        //TODO
        return passed;
    }

    @Override
    public String getName() { return "WiFi"; }

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
        /*isRunning.set(true);
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
        manager.requestPeers(channel, WiFiManet.this);
        //nsd.startAdvertising(manager,channel,null);
        setStatus(Status.DISCOVERING);*/
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
        /*if (packet != null) {
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
            Log.d(Config.TAG,"Cannot send null packet");*/
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        /*Log.d(Config.TAG,"Packet burst...");
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
            Log.d(Config.TAG,"Trying to burst over manet but packet was null");*/
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
        /*Log.d(Config.TAG,"Disconnecting WiFiManet...");
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
        }*/
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
    }*/

    private void formWiFiGroup() {
        /*if ((socketClient == null) && (socketServer == null) && (manager != null) &&(channel != null)) {
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
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Could not form new group: "+ Util.getFailureStatusString(reason));
                    nsd.startDiscovery(manager,channel);
                    if (handler != null)
                        handler.postDelayed(() -> formWiFiGroup(),DELAY_BEFORE_FORM_GROUP);
                }
            });
        }*/
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
