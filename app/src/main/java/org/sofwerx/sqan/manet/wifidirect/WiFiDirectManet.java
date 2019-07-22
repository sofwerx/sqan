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
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.MacAddress;
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
import java.util.HashMap;
import java.util.List;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet implements WifiP2pManager.PeerListListener, WiFiDirectDiscoveryListener, WifiP2pManager.ConnectionInfoListener, ServerStatusListener {
    private final static String TAG = Config.TAG+".WiFiDirect";
    private final static String FALLBACK_GROUP_OWNER_IP = "192.168.49.1"; //the WiFi Direct Group owner always uses this address; hardcoding it is a little hackish, but is used when all other IP detection method attempts fail
    private final static long TIME_BETWEEN_REPAIR_ATTEMPTS = 1000l * 60l;
    private final static long SERVER_MAX_IDLE_TIME = 1000l * 60l * 2l;
    private final static long DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN = 1000l * 10l;
    private final static long DELAY_BEFORE_CONNECTING_CHECK = 1000l;
    private final static long DELAY_BEFORE_CONNECTING = DELAY_BEFORE_CONNECTING_CHECK * 5l; //delay to prevent two devices from trying to connect at the same time
    private HashMap<WifiP2pDevice,Long> waitingMap = new HashMap<>();
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
    private long nextRepairAttempt = Long.MIN_VALUE;
    private long nextConnectionInfoRequest = Long.MIN_VALUE;

    public WiFiDirectManet(android.os.Handler handler, android.content.Context context, ManetListener listener) {
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
                Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION");
                int state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1);
                onHardwareChanged(state == WifiP2pManager.WIFI_P2P_STATE_ENABLED);
            } else if (WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
                Log.d(TAG, "WIFI_P2P_PEERS_CHANGED_ACTION");
                if (manager != null)
                    manager.requestPeers(channel, WiFiDirectManet.this);
            } else if (WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION.equals(action)) {
                if (manager == null)
                    return;
                NetworkInfo networkInfo = intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO);
                if (networkInfo == null)
                    return;
                if (networkInfo.isConnected()) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"WIFI_P2P_CONNECTION_CHANGED_ACTION - network is now connected");
                    manager.requestConnectionInfo(channel, WiFiDirectManet.this);
                    manager.requestPeers(channel, WiFiDirectManet.this);
                } else {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "WIFI_P2P_CONNECTION_CHANGED_ACTION - network is disconnected"+((networkInfo.getExtraInfo()==null)?"":": "+networkInfo.getExtraInfo()));
                    if (nsd == null) {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"WiFi Direct NSD was null, this should never happen. Initializing again...");
                        nsd = new WiFiDirectNSD(WiFiDirectManet.this);
                    }
                    if ((socketClient != null) || (socketServer != null))
                        restart();
                }
            } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                WifiP2pDevice reportedDevice = intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE);
                if (reportedDevice != null) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION: this device is "+reportedDevice.deviceName+(reportedDevice.isGroupOwner()?" (group owner)":""));
                    thisDevice = reportedDevice;
                    if ((socketServer != null) && reportedDevice.isGroupOwner()) {
                        CommsLog.log(CommsLog.Entry.Category.STATUS,"WIFI_P2P_THIS_DEVICE_CHANGED_ACTION - device is group owner, starting Server");
                        startServer();
                    }
                } else
                    Log.d(TAG, "WIFI_P2P_THIS_DEVICE_CHANGED_ACTION");
            }
        }
    };

    private long cancelConnectTimeout = Long.MIN_VALUE;
    private final static long TIMEOUT_CANCEL_CONNECT = 1000l * 15l;

    private void cancelConnect() {
        //FIXME this forces any wifi network to close, which would not support other WiFi attached devices (like a camera)
        //WifiManager wiFiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        //wiFiManager.disconnect();

        if (manager != null) {
            manager.cancelConnect(channel, new WifiP2pManager.ActionListener() {
                @Override
                public void onSuccess() {
                    Log.d(TAG, "WiFiDirectManager cancelled connection");
                    try {
                        if (Build.VERSION.SDK_INT >= 27)
                            channel.close();
                    } catch (Exception e) {
                        Log.w(TAG, "Unable to close WiFi Channel: " + e.getMessage());
                    }
                    channel = null;
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFI Direct channel successfully closed");
                    startChannel();
                }

                @Override
                public void onFailure(int reason) {
                    //if (System.currentTimeMillis() < cancelConnectTimeout) {
                    //    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "WiFiDirectManager failed to cancel connection: " + Util.getFailureStatusString(reason) + " - trying to cancel again");
                    //    cancelConnect();
                    //} else {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "WiFiDirectManager failed to cancel connection: " + Util.getFailureStatusString(reason) + ", but trying new to restart connection anyway");
                        setStatus(Status.ERROR);
                        startChannel();
                    //}
                }
            });
        }
    }

    private void restart() {
        Log.d(TAG,"Restarting WiFi Direct connection...");
        stopSocketConnections(false);
        nsd.stopAdvertising(manager, channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFI NSD Advertising stopped successfully");
                nsd.stopDiscovery(manager, channel, new WifiP2pManager.ActionListener() {
                    @Override
                    public void onSuccess() {
                        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFI NSD Discovery stopped successfully");
                        cancelConnectTimeout = System.currentTimeMillis() + TIMEOUT_CANCEL_CONNECT;
                        cancelConnect();
                    }

                    @Override
                    public void onFailure(int reason) {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "restart() nsd.stopADiscovery() failed");
                        setStatus(Status.ERROR);
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "restart() nsd.stopAdvertising() failed");
                setStatus(Status.ERROR);
            }
        });
    }

    private void startChannel() {
        if (channel == null) {
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Starting WiFi Channel");
            channel = manager.initialize(context.toAndroid(), (handler != null) ? handler.toAndroid().getLooper() : null, () -> onDisconnected());
        } else
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Tried to start WiFi Channel, but one already exists so using that channel instead");
        if (nsd == null)
            nsd = new WiFiDirectNSD(this);
        nsd.startDiscovery(manager,channel);
        nsd.startAdvertising(manager, channel,false);
        manager.requestPeers(channel, WiFiDirectManet.this);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
    }



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
    public boolean isCongested() {
        //FIXME
        return false;
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public void init() throws ManetException {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct MANET is initializing...");
        isRunning.set(true);
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        manager = (WifiP2pManager) context.toAndroid().getSystemService(Context.WIFI_P2P_SERVICE);
        wifiManager = (WifiManager) context.toAndroid().getSystemService(Context.WIFI_SERVICE);
        priorWiFiStateEnabled = wifiManager.isWifiEnabled();
        wifiManager.setWifiEnabled(true);
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"sqan");
        wifiLock.acquire();
        channel = manager.initialize(context.toAndroid(), (handler!=null)?handler.toAndroid().getLooper():null, () -> onDisconnected());
        context.registerReceiver(hardwareStatusReceiver, intentFilter);
        startChannel();
    }

    /**
     * Starts this device as a server on this channel
     */
    private void startServer() {
        if (!isRunning.get())
            return;
        if (socketServer == null) {
            setStatus(Status.CONNECTED);
            if (Config.getThisDevice() != null)
                Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.HUB);
            socketServer = new Server(new SocketChannelConfig((String)null, SQAN_PORT), parser, this);
            socketServer.setIdleTimeout(SERVER_MAX_IDLE_TIME);
            if ((manager != null) && (channel != null)) {
                if (nsd == null)
                    nsd = new WiFiDirectNSD(this);
                else
                    nsd.stopDiscovery(manager,channel,null);
                //nsd.startAdvertising(manager,channel,true); //FIXME testing: trying to test forcing advertising again after connection
            }
            if (socketClient != null) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct switching from Client mode to Server mode...");
                socketClient.close(true);
            } else
                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct starting server...");
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
            setStatus(Status.CONNECTED);
            CommsLog.log(CommsLog.Entry.Category.STATUS, "WiFi Direct connecting as Client to " + serverIp + "...");
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
                Log.d(TAG,"burst(packet) sent as a Client");
                burst(packet, null);
            } else {
                if (socketServer != null) {
                    Log.d(TAG,"burst(packet) sent as a Server");
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
            Log.d(TAG,"Cannot send null packet");
    }

    private void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        Log.d(TAG,"Packet burst...");
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
                Log.d(TAG,"No Server or Client available for packet burst");
        } else
            Log.d(TAG,"Trying to burst over manet but packet was null");
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
        Log.d(TAG,"Disconnecting WiFiManet...");
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver);
                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Broadcast Receiver unregistered");
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
            hardwareStatusReceiver = null;
        }
        if (nsd != null) {
            nsd.stopDiscovery(manager, channel, null);
            nsd.stopAdvertising(manager, channel, null);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Net Service Discovery closing...");
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
                public void onSuccess() {
                    Log.d(TAG, "WiFiDirectManager cancelled connection");
                    try {
                        if (Build.VERSION.SDK_INT >= 27)
                            channel.close();
                    } catch (Exception e) {
                        Log.w(TAG,"Unable to close WiFi Channel: "+e.getMessage());
                    }
                    channel = null;
                }

                @Override
                public void onFailure(int reason) { Log.d(TAG, "WiFiDirectManager failed to cancel connection: " + Util.getFailureStatusString(reason)); }
            });
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
            CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct socketClient closing...");
            socketClient = null;
        }
        if (socketServer != null) {
            socketServer.close(announce);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct socketServer closing...");
            socketServer = null;
            ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
            if (devices != null) {
                synchronized (devices) {
                    for (SqAnDevice device:devices) {
                        if (device.isDirectWiFi() && (device.getRoleWiFi() == SqAnDevice.NodeRole.SPOKE)) {
                            if (!device.isDirectBt() && (device.getRoleBT() == SqAnDevice.NodeRole.OFF) && (device.getRoleSDR() == SqAnDevice.NodeRole.OFF)) {
                                device.setRoleWiFi(SqAnDevice.NodeRole.OFF);
                                CommsLog.log(CommsLog.Entry.Category.CONNECTION,device.getLabel()+" assumed to have lost WiFi connectivity since this device was acting as the WiFi group owner");
                            }
                        }
                    }
                }
            }
        }
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
    }

    private void onHardwareChanged(boolean enabled) {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct status changed to "+(enabled?"Enabled":"disabled"));
        if (enabled) {
            if (isRunning.get() && (manager != null) && (channel != null))
                manager.requestConnectionInfo(channel, WiFiDirectManet.this);
        } else
            stopSocketConnections(false);
    }

    private void onDisconnected() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Channel disconnected");
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
        if (status == Status.ERROR) {
            if (System.currentTimeMillis() > nextRepairAttempt)
                repairBrokenManet();
        } else if ((status != Status.CONNECTED) && (status != Status.OFF)) {
            if ((socketClient == null) && (socketServer == null) && (manager != null) && (channel != null)) {
                manager.requestConnectionInfo(channel, WiFiDirectManet.this);
                manager.requestPeers(channel, WiFiDirectManet.this);
                nextConnectionInfoRequest = System.currentTimeMillis() + DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN;
            } else
                Log.d(TAG,"Looks like the WiFi group information was resolved, so taking no additional action.");
        }
    }

    private void repairBrokenManet() {
        nextRepairAttempt = System.currentTimeMillis() + TIME_BETWEEN_REPAIR_ATTEMPTS;
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Attempting to repair WiFi Direct MANET");
        //TODO work to repair the MANET
        restart();
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
                        MacAddress mac;
                        for (WifiP2pDevice peer:peers) {
                            if (peer.status == WifiP2pDevice.AVAILABLE) {
                                mac = MacAddress.build(peer.deviceAddress);
                                SqAnDevice device = SqAnDevice.findByWiFiDirectMac(mac);
                                if (device == null) {
                                    SavedTeammate teammate = Config.getTeammateByWiFiMac(MacAddress.build(peer.deviceAddress));
                                    if ((teammate != null) && teammate.isUseful() && teammate.isEnabled()) {
                                        device = new SqAnDevice(teammate.getSqAnAddress());
                                        device.setCallsign(teammate.getCallsign());
                                        device.setWiFiDirectMac(mac);
                                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,device.getLabel()+" found based on saved Teammate info");
                                        if (listener != null) {
                                            listener.onDevicesChanged(device);
                                            listener.updateDeviceUi(device);
                                        }
                                    }
                                }
                                if (device != null) {
                                    CommsLog.log(CommsLog.Entry.Category.COMMS, "Device matching teammate " + device.getLabel() + " found");
                                    if ((socketClient == null) && (socketServer == null))
                                        connectToDeviceIfAppropriate(peer);
                                    break;
                                }

                                if (teammates.contains(peer)) {
                                    if ((socketClient == null) && (socketServer == null)) {
                                        CommsLog.log(CommsLog.Entry.Category.COMMS, "Device matching previously paired device " + peer.deviceName + "(" + peer.deviceAddress + ") found");
                                        connectToDeviceIfAppropriate(peer);
                                    }
                                } else
                                    CommsLog.log(CommsLog.Entry.Category.CONNECTION, peer.deviceName + "(" + peer.deviceAddress + ") not recognized as a saved teammate");
                            }
                        }
                    }
                    CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WiFi Direct found "+peers.size()+" peers");
                }
            }
        } else
            peers.clear();

        if (peers.size() == 0) {
            Log.d(TAG, "No devices found");
            return;
        }
    }

    private void connectToDeviceIfAppropriate(WifiP2pDevice device) {
        if ((device == null) || (device.status != WifiP2pDevice.AVAILABLE))
            return;
        if (device.isGroupOwner())
            connectToDevice(device);
        else {
            if (thisDevice != null) {
                if (Util.isHigherPriorityMac(thisDevice.deviceAddress,device.deviceAddress)) {
                    //if (!thisDevice.isGroupOwner()) //FIXME? commented out for testing
                        connectToDevice(device);
                } else {
                    if (waitingMap.containsKey(device) && (waitingMap.get(device) > System.currentTimeMillis())) {
                        waitingMap.remove(device);
                        if (device.status == WifiP2pDevice.AVAILABLE) {
                            if ((socketClient != null) && (socketServer != null))
                                connectToDevice(device);
                            else
                                Log.d(TAG, device.deviceName + " (" + device.deviceAddress + ") already connected, ignoring scheduled connection request");
                        } else if (device.status == WifiP2pDevice.INVITED)
                            Log.d(TAG, device.deviceName + " (" + device.deviceAddress + ") already invited, ignoring scheduled connection request");
                        else {
                            Log.d(TAG, device.deviceName + " (" + device.deviceAddress + ") is "+Util.getDeviceStatusString(device.status)+" scheduling a connection attempt");
                            waitingMap.put(device,System.currentTimeMillis() + DELAY_BEFORE_CONNECTING);
                            if (handler != null) {
                                handler.toAndroid().postDelayed(() -> {
                                    if ((socketClient != null) && (socketServer != null) && !thisDevice.isGroupOwner())
                                        connectToDeviceIfAppropriate(device);
                                }, DELAY_BEFORE_CONNECTING_CHECK);
                            }
                        }
                    } else {
                        if (!waitingMap.containsKey(device))
                            waitingMap.put(device,System.currentTimeMillis() + DELAY_BEFORE_CONNECTING);
                        Log.d(TAG, "The other peer teammate has a higher priority MAC so waiting a bit before attempting connection");
                        if (handler != null) {
                            handler.toAndroid().postDelayed(() -> {
                                if ((socketClient != null) && (socketServer != null) && !thisDevice.isGroupOwner())
                                    connectToDeviceIfAppropriate(device);
                            }, DELAY_BEFORE_CONNECTING_CHECK);
                        }
                    }
                }
            }
        }
    }

    private void connectToDevice(WifiP2pDevice device) {
        if (device == null) {
            Log.e(TAG,"cannot connect to a null device");
            return;
        }
        if (device.status != WifiP2pDevice.AVAILABLE) {
            Log.d(TAG, "Cannot connect to a device that is "+Util.getDeviceStatusString(device.status));
            return;
        }
        if ((manager == null) || (channel == null)) {
            Log.e(TAG,"connectToDevice called, but manager or channel are null");
            return;
        }
        if ((thisDevice != null) && thisDevice.isGroupOwner()) {
            Log.d(TAG, "Group owners do not connect to other devices, they wait for connections. Ignoring connectToDevice call");
            return;
        }
        if (System.currentTimeMillis() < nextAllowablePeerConnectionAttempt) {
            Log.d(TAG, "Recent peer connection attempt underway, skipping this peer for now.");
            return;
        }
        nextAllowablePeerConnectionAttempt = System.currentTimeMillis() + MIN_TIME_BETWEEN_PEER_CONNECTIONS;
        WifiP2pConfig config = new WifiP2pConfig();
        config.deviceAddress = device.deviceAddress;
        config.wps.setup = WpsInfo.PBC;
        if (thisDevice != null) {
            if (Util.isHigherPriorityMac(thisDevice.deviceAddress, device.deviceAddress)) {
                config.groupOwnerIntent = 15; //try to be group owner
                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Trying to connect to "+device.deviceName+" ("+device.deviceAddress+") while requesting group ownership");
            } else {
                config.groupOwnerIntent = 0; //defer group owner to other device
                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Trying to connect to "+device.deviceName+" ("+device.deviceAddress+") while deferring group ownership");
            }
        }

        String callsign = null;
        MacAddress mac = MacAddress.build(device.deviceAddress);
        SqAnDevice sqAnDevice = SqAnDevice.findByWiFiDirectMac(mac);
        if (sqAnDevice == null) {
            SavedTeammate saved = Config.getTeammateByWiFiMac(mac);
            if ((saved != null) && saved.isUseful() && saved.isEnabled()) {
                callsign = saved.getCallsign();
                sqAnDevice = new SqAnDevice(saved.getSqAnAddress());
                if (callsign != null)
                    sqAnDevice.setCallsign(callsign);
                sqAnDevice.setWiFiDirectMac(mac);
            }
            //sqAnDevice.setNetworkId(device.deviceAddress);
        }
        /*try {
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
        }*/
        CommsLog.log(CommsLog.Entry.Category.COMMS,"WiFi Direct attempting to connect to "+((callsign==null)?device.deviceName:callsign));
        manager.connect(channel, config, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                //FIXME testing this
                /*manager.requestGroupInfo(channel, group -> {
                    if (group == null)
                        Log.d(TAG,"Connected to null group");
                    else {
                        Log.d(TAG, group.toString());
                        Log.d(TAG, "Group " + group.getNetworkName() + "; password: " + group.getPassphrase());
                    }
                });*/
                //FIXME testing this

                CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Direct successfully connected to "+config.deviceAddress);
                if ((socketClient == null) && (socketServer == null)) {
                    if (serverDevice != null)
                        startClient(serverDevice.deviceAddress);
                    else {
                        CommsLog.log(CommsLog.Entry.Category.COMMS, "WiFi Direct connected to " + config.deviceAddress + ", but socketClient not started as no server identified yet");
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
            CommsLog.log(CommsLog.Entry.Category.COMMS,"New teammate "+wifiP2pDevice.deviceName+" discovered");
            connectToDeviceIfAppropriate(wifiP2pDevice);
        } else {
            if (wifiP2pDevice.status == WifiP2pDevice.AVAILABLE) {
                CommsLog.log(CommsLog.Entry.Category.COMMS,"Teammate "+wifiP2pDevice.deviceName+" discovered and trying to connect...");
                connectToDeviceIfAppropriate(wifiP2pDevice);
            }
        }
    }
    @Override
    public void onDiscoveryStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Discovery Started");
        if (status != Status.CONNECTED) {
            if (status == Status.ADVERTISING)
                setStatus(Status.ADVERTISING_AND_DISCOVERING);
            else
                setStatus(Status.DISCOVERING);
        }
    }

    @Override
    public void onAdvertisingStarted() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct Advertising Started");
        if (status != Status.CONNECTED) {
            if (status == Status.DISCOVERING)
                setStatus(Status.ADVERTISING_AND_DISCOVERING);
            else
                setStatus(Status.ADVERTISING);
        }
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
                CommsLog.log(CommsLog.Entry.Category.STATUS, "Teammate "+wifiP2pDevice.deviceName + "(" + wifiP2pDevice.deviceAddress + ") discovered and added to current list of teammates ("+((teammates.size()>1)?(teammates.size()+" total teammates)"):"only teammate at this time)"));
            }
        }
        return added;
    }

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
        CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct fatal server error");
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
            if (info.groupFormed)
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "WiFi direct group formed " + info.groupFormed + ", Is owner " + info.isGroupOwner);
            else {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi Direct group formation not yet complete. Waiting a bit before checking again...");
                nextConnectionInfoRequest = System.currentTimeMillis() + DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN;
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
                    handler.toAndroid().postDelayed(() -> {
                        if ((socketClient != null) && (socketServer != null) && (manager != null) && (channel != null)) {
                            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"WIFi Direct group ownership still not resolved, requesting details again");
                            manager.requestConnectionInfo(channel,WiFiDirectManet.this);
                        }
                    }, DELAY_BEFORE_REQUESTING_CONNECTION_INFO_AGAIN);
                }
            }
        } else
            Log.e(TAG, "manager.requestConnectionInfo cannot get info - this should not happen");
    }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        //ignore
    }
}
