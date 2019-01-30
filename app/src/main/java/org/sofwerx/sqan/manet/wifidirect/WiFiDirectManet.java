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
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet implements WifiP2pManager.PeerListListener, WiFiDirectDiscoveryListener {
    private WifiP2pManager.Channel channel;
    private WifiP2pManager manager;
    private BroadcastReceiver hardwareStatusReceiver;
    private List<WifiP2pDevice> peers = new ArrayList<WifiP2pDevice>();
    private List<WifiP2pDevice> teammates = new ArrayList<WifiP2pDevice>();
    private WiFiDirectNSD nsd;

    public WiFiDirectManet(Handler handler, Context context, ManetListener listener) {
        super(handler,context,listener);
        hardwareStatusReceiver = null;
        channel = null;
        manager = null;
    }

    @Override
    public ManetType getType() { return ManetType.WIFI_DIRECT; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        if (NetUtil.isWiFiConnected(context))
            SqAnService.onIssueDetected(new WiFiIssue(false,"WiFi is connected to another network"));
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

        if (hardwareStatusReceiver == null) {
            hardwareStatusReceiver = new BroadcastReceiver() {
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
                                //TODO
                                Log.d(Config.TAG,"Group formed "+info.groupFormed+", Is owner "+info.isGroupOwner);
                            });
                        }
                    } else if (WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
                        onDeviceChanged(intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE));
                    }
                }
            };

            context.registerReceiver(hardwareStatusReceiver, intentFilter);
            nsd = new WiFiDirectNSD(this);
            nsd.startDiscovery(manager,channel);
        }

        /*manager.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            @Override
            public void onSuccess() {
                if ((peers != null) && !peers.isEmpty()) {
                    connectToDevice(peers.get(0));
                }
            }

            @Override
            public void onFailure(int reasonCode) {
                Log.e(Config.TAG,"discoverPeers failed, code: "+reasonCode);
            }
        });*/
    }

    @Override
    public void burst(AbstractPacket packet) throws ManetException {
        //TODO
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        //TODO
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
        if (hardwareStatusReceiver != null) {
            try {
                context.unregisterReceiver(hardwareStatusReceiver);
                hardwareStatusReceiver = null;
            } catch (IllegalArgumentException e) {
                e.printStackTrace();
            }
        }
    }

    private void onHardwareChanged(boolean enabled) {
        Log.d(Config.TAG,"WiFi Direct status changed to "+(enabled?"Enabled":"disabled"));
        //TODO
    }

    private void onDeviceChanged(WifiP2pDevice device) {
        if (device != null)
            Log.d(Config.TAG,device.deviceName+" changed to status "+device.status+(device.isGroupOwner()?" (group owner":""));
        //TODO
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
                    for (WifiP2pDevice peer:peers) {
                        Log.d(Config.TAG,peer.deviceName+" status "+Util.getDeviceStatusString(peer.status)+(peer.isGroupOwner()?" (group owner":""));
                    }
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
                Log.d(Config.TAG,"connected to device "+config.deviceAddress);
                SqAnDevice device = SqAnDevice.findByNetworkID(config.deviceAddress);
                if (device != null) {
                    device.setStatus(SqAnDevice.Status.CONNECTED);
                    device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Disconnected"));
                    listener.onDevicesChanged(device);
                } else {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, config.deviceAddress+" connected so I added it");
                    device = new SqAnDevice();
                    device.setNetworkId(config.deviceAddress);
                }
            }

            @Override
            public void onFailure(int reason) {
                Log.e(Config.TAG,"Failed to connect to device, reason code: "+Util.getFailureStatusString(reason));
            }
        });
    }

    @Override
    public void onDeviceDiscovered(WifiP2pDevice wifiP2pDevice) {
        if (wifiP2pDevice != null) {
            if (!teammates.contains(wifiP2pDevice)) {
                teammates.add(wifiP2pDevice);
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
                Log.d(Config.TAG, "teammate "+wifiP2pDevice.deviceName + " discovered");
                connectToDevice(wifiP2pDevice);
            }
        }
    }

    @Override
    public void onError(String error) {
        //TODO
    }
}
