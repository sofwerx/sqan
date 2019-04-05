package org.sofwerx.sqan;

import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.util.Log;

import org.sofwerx.sqan.ipc.IpcBroadcastTransceiver;
import org.sofwerx.sqan.ipc.IpcSaBroadcastTransmitter;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.bt.BtManetV2;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.ChannelBytesPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.RawBytesPacket;
import org.sofwerx.sqan.manet.common.packet.VpnPacket;
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.wifiaware.WiFiAwareManet;
import org.sofwerx.sqan.manet.wifidirect.WiFiDirectManet;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps implements ManetListener, IpcBroadcastTransceiver.IpcBroadcastListener {
    private final static long MIN_SA_IPC_BROADCAST_DELAY = 250l; //dont do SA IPC broadcasts with any interval shorter than this
    private final SqAnService sqAnService;
    private AbstractManet wifiManet;
    private BtManetV2 btManet;
    private static long transmittedByteTally = 0l;
    private HandlerThread manetThread; //the MANET itself runs on this thread where possible
    private Handler handler;
    private ManetOps manetOps;
    private boolean shouldBeActive = true;
    private long nextEligibleSaIpcBroadcast = Long.MIN_VALUE;

    //for capturing overall up time analytics
    private static SqAnDevice.FullMeshCapability meshStatus = SqAnDevice.FullMeshCapability.DOWN;
    private static long lastMeshStatusTime = System.currentTimeMillis();
    private static long totalMeshUpTime = 0l;
    private static long totalMeshDegradedTime = 0l;
    private static long totalMeshDownTime = 0l;

    public ManetOps(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        this.manetOps = this;
        manetThread = new HandlerThread("ManetOps") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(manetThread.getLooper());
                int manetType = 0;
                try {
                    manetType = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(sqAnService).getString(Config.PREFS_MANET_ENGINE,"4"));
                } catch (NumberFormatException e) {
                }
                switch (manetType) {
                    case 1:
                        wifiManet = new NearbyConnectionsManet(handler,sqAnService,manetOps);
                        btManet = null;
                        break;

                    case 2:
                        wifiManet = new WiFiAwareManet(handler,sqAnService,manetOps);
                        //FIXME temporarily ignoring btMane to just trouble shoot WiFi Aware; btManet = new BtManetV2(handler,sqAnService,manetOps);
                        btManet = null;
                        break;

                    case 3:
                        wifiManet = new WiFiDirectManet(handler,sqAnService,manetOps);
                        btManet = new BtManetV2(handler,sqAnService,manetOps);
                        break;

                    case 4:
                        wifiManet = null;
                        btManet = new BtManetV2(handler,sqAnService,manetOps);
                        break;

                    default:
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"No MANET engine selected so no MANET will be used.");
                        return;
                }
                if (SqAnService.checkSystemReadiness() && shouldBeActive)
                    manetOps.start();
                if (Config.isAllowIpcComms())
                    IpcBroadcastTransceiver.registerAsSqAn(sqAnService,manetOps);
            }
        };
        manetThread.start();
    }

    public static long getTotalUpTime() {
        if (meshStatus == SqAnDevice.FullMeshCapability.UP)
            return totalMeshUpTime + (System.currentTimeMillis() - lastMeshStatusTime);
        return totalMeshUpTime;
    }
    public static long getTotalDegradedTime() {
        if (meshStatus == SqAnDevice.FullMeshCapability.DEGRADED)
            return totalMeshDegradedTime + (System.currentTimeMillis() - lastMeshStatusTime);
        return totalMeshDegradedTime;
    }
    public static long getTotalDownTime() {
        if (meshStatus == SqAnDevice.FullMeshCapability.DOWN)
            return totalMeshDownTime + (System.currentTimeMillis() - lastMeshStatusTime);
        return totalMeshDownTime;
    }
    public static SqAnDevice.FullMeshCapability getOverallMeshStatus() { return meshStatus; }

    /**
     * Used to toggle this MANET on/off
     * @param active
     */
    public void setActive(boolean active) {
        if (active != shouldBeActive) {
            if (active)
                start();
            else
                pause();
        }
    }

    /**
     * Shutdown the MANET and frees all resources
     */
    public void shutdown() {
        Log.d(Config.TAG,"ManetOps disconnecting...");
        IpcBroadcastTransceiver.unregister(sqAnService);
        shouldBeActive = false;

        if (manetThread != null) {
            if (handler != null) {
                handler.removeCallbacksAndMessages(null);
                handler.post(() -> {
                    try {
                        if (wifiManet != null) {
                            wifiManet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                            Log.d(Config.TAG, "Sending hangup notification to WiFi MANET");
                        }
                    } catch (ManetException ignore) {
                    }
                    try {
                        if (btManet != null) {
                            btManet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                            Log.d(Config.TAG, "Sending hangup notification to Bluetooth MANET");
                        }
                    } catch (ManetException ignore) {
                    }
                });
                handler.postDelayed(() -> {
                    try {
                        if (wifiManet != null) {
                            wifiManet.disconnect();
                            wifiManet = null;
                            Log.d(Config.TAG, "Disconnected from WiFi MANET normally");
                        }
                    } catch (ManetException e) {
                        Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                    }
                    try {
                        if (btManet != null) {
                            btManet.disconnect();
                            btManet = null;
                            Log.d(Config.TAG, "Disconnected from Bluetooth MANET normally");
                        }
                    } catch (ManetException e) {
                        Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                    }
                }, 1000l); //give the link 5 seconds to announce the devices departure
            } else {
                try {
                    if (wifiManet != null) {
                        wifiManet.disconnect();
                        Log.d(Config.TAG, "Disconnected from WiFi MANET with handler already closed");
                        wifiManet = null;
                    }
                } catch (ManetException e) {
                    Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                }
                try {
                    if (btManet != null) {
                        btManet.disconnect();
                        Log.d(Config.TAG, "Disconnected from Bluetooth MANET with handler already closed");
                        btManet = null;
                    }
                } catch (ManetException e) {
                    Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                }
                if (manetThread != null) {
                    manetThread.quitSafely();
                    manetThread = null;
                    handler = null;
                }
            }
        } else {
            try {
                if (wifiManet != null) {
                    wifiManet.disconnect();
                    Log.d(Config.TAG,"Disconnected from WiFi MANET with manetThread already closed");
                    wifiManet = null;
                }
            } catch (ManetException e) {
                Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
            }
            try {
                if (btManet != null) {
                    btManet.disconnect();
                    Log.d(Config.TAG,"Disconnected from Bluetooth MANET with manetThread already closed");
                    btManet = null;
                }
            } catch (ManetException e) {
                Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
            }
        }
    }

    public void pause() {
        shouldBeActive = false;
        if (handler != null)
            handler.removeCallbacks(null);
        if (wifiManet != null) {
            try {
                wifiManet.pause();
            } catch (ManetException e) {
                Log.e(Config.TAG,"Unable to pause MANET: "+e.getMessage());
            }
        }
        if (btManet != null) {
            try {
                btManet.pause();
            } catch (ManetException e) {
                Log.e(Config.TAG,"Unable to pause MANET: "+e.getMessage());
            }
        }
    }

    public void start() {
        shouldBeActive = true;
        if (handler != null) {
            handler.post(() -> {
                if (wifiManet != null) {
                    try {
                        wifiManet.init();
                    } catch (ManetException e) {
                        sqAnService.onStatusChange(Status.ERROR, e.getMessage());
                    }
                }
                if (btManet != null) {
                    try {
                        btManet.init();
                    } catch (ManetException e) {
                        sqAnService.onStatusChange(Status.ERROR, e.getMessage());
                    }
                }
            });
        }
    }

    @Override
    public void onStatus(Status status) {
        sqAnService.onStatusChange(status,null);
    }

    @Override
    public void onRx(AbstractPacket packet) {
        if (packet != null) {
            if (Config.isAllowIpcComms() && !packet.isAdminPacket()) {
                byte[] data = null;
                String channel = null;
                if (packet instanceof ChannelBytesPacket) {
                    channel = ((ChannelBytesPacket)packet).getChannel();
                    data = ((ChannelBytesPacket)packet).getData();
                } else if (packet instanceof RawBytesPacket)
                    data = ((RawBytesPacket)packet).getData();
                if (data != null) {
                    IpcBroadcastTransceiver.broadcast(sqAnService, channel, packet.getOrigin(), data);
                    Log.d(Config.TAG, "Broadcasting " + StringUtil.toDataSize((long) data.length) + " over IPC");
                }
            }
            if (packet instanceof DisconnectingPacket) {
                SqAnDevice outgoing = SqAnDevice.findByUUID(((DisconnectingPacket)packet).getUuidOfDeviceLeaving());
                if (outgoing != null) {
                    if (outgoing.getCallsign() == null)
                        Log.d(Config.TAG,Integer.toString(outgoing.getUUID())+" reporting leaving mesh");
                    else
                        Log.d(Config.TAG,outgoing.getCallsign()+" reporting leaving mesh");
                    outgoing.setStatus(SqAnDevice.Status.OFFLINE);
                    onDevicesChanged(outgoing);
                } else
                    Log.d(Config.TAG,"Disconnect packet received, but unable to find corresponding device");
            } else if (packet instanceof HeartbeatPacket){
                ipcBroadcastIfNeeded();
            } else if (packet instanceof VpnPacket) {
                sqAnService.onRxVpnPacket((VpnPacket)packet);
            }
        }
    }

    @Override
    public void onTx(AbstractPacket packet) {
        if (sqAnService.listener != null)
            sqAnService.listener.onDataTransmitted();
        sqAnService.onPositiveComms();
    }

    private void ipcBroadcastIfNeeded() {
        if ((System.currentTimeMillis() > nextEligibleSaIpcBroadcast) && Config.isBroadcastSa()) {
            IpcSaBroadcastTransmitter.broadcast(sqAnService);
            nextEligibleSaIpcBroadcast = System.currentTimeMillis() + MIN_SA_IPC_BROADCAST_DELAY;
        }
    }

    @Override
    public void onTxFailed(AbstractPacket packet) {
        if (packet == null)
            return;
        //TODO decide if packet should be dropped or resent; maybe check network health as well
    }

    /**
     * Used to keep a running total of transmitted data
     * @param bytes
     */
    public static void addBytesToTransmittedTally(int bytes) { transmittedByteTally += bytes; }

    /**
     * Gets the total tally of bytes transmitted
     * @return
     */
    public static long getTransmittedByteTally() { return transmittedByteTally; }

    @Override
    public void onDevicesChanged(SqAnDevice device) {
        evalutateMeshStatus();
        if (sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
        //if ((device != null) && device.isActive())
        //    sqAnService.requestHeartbeat(false);
        sqAnService.notifyStatusChange(null);
    }

    @Override
    public void updateDeviceUi(SqAnDevice device) {
        if (sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
    }

    @Override
    public void onAuthenticatedOnNet() {
        sqAnService.requestHeartbeat(true);
    }

    public Status getStatus() {
        if ((wifiManet == null) && (btManet == null))
            return Status.OFF;
        if (wifiManet == null)
            return btManet.getStatus();
        if (btManet == null)
            return wifiManet.getStatus();
        int wifi = wifiManet.getStatus().ordinal();
        int bt = btManet.getStatus().ordinal();
        if (wifi > bt)
            return wifiManet.getStatus();
        return btManet.getStatus();
    }

    public AbstractManet getWifiManet() { return wifiManet; }
    public AbstractManet getBtManet() { return btManet; }

    public void burst(AbstractPacket packet) {
        burst(packet, TransportPreference.AGNOSTIC);
    }

    public void burst(final AbstractPacket packet, final TransportPreference preferredTransport) {
        if (packet == null) {
            Log.d(Config.TAG, "ManetOps cannot burst a null packet");
            return;
        }
        if ((btManet == null) && (wifiManet == null))
            Log.d(Config.TAG,"ManetOps cannot burst without an available MANET");
        else {
            if (handler != null) {
                handler.post(() -> {
                    try {
                        if ((preferredTransport == null) || (preferredTransport == TransportPreference.AGNOSTIC)) {
                            boolean btGood = (btManet != null) && (btManet.getStatus() == Status.CONNECTED);
                            boolean wifiGood = (wifiManet != null) && (wifiManet.getStatus() == Status.CONNECTED);
                            if (btGood && wifiGood) {
                                if (packet.isHighPerformanceNeeded())
                                    wifiManet.burst(packet);
                                else {
                                    if (PacketHeader.BROADCAST_ADDRESS == packet.getSqAnDestination()) {
                                        wifiManet.burst(packet);
                                        btManet.burst(packet);
                                    } else {
                                        SqAnDevice device = SqAnDevice.findByUUID(packet.getSqAnDestination());
                                        if (device == null) {
                                            wifiManet.burst(packet);
                                            btManet.burst(packet);
                                        } else {
                                            switch (device.getPreferredTransport()) {
                                                case WIFI:
                                                    wifiManet.burst(packet);
                                                    break;

                                                case BLUETOOTH:
                                                    btManet.burst(packet);
                                                    break;

                                                default:
                                                    wifiManet.burst(packet);
                                                    btManet.burst(packet);
                                            }
                                        }
                                    }
                                }
                            } else {
                                //at least one mesh isn't completely healthy so send over both
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                if (btManet != null)
                                    btManet.burst(packet);
                            }
                        } else {
                            if ((preferredTransport == null) || (preferredTransport == TransportPreference.BOTH)) {
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                if (btManet != null)
                                    btManet.burst(packet);
                            } else if (preferredTransport == TransportPreference.WIFI) {
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                else {
                                    if (btManet != null)
                                        btManet.burst(packet);
                                }
                            } else if (preferredTransport == TransportPreference.BLUETOOTH) {
                                if (btManet != null)
                                    btManet.burst(packet);
                                else {
                                    if (wifiManet != null)
                                        wifiManet.burst(packet);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(Config.TAG, "Unable to burst packet: " + e.getMessage());
                    }
                });
            }
        }
    }

    private void evalutateMeshStatus() {
        SqAnDevice.FullMeshCapability currentStatus = SqAnDevice.getFullMeshStatus();
        if (currentStatus != meshStatus) {
            switch (meshStatus) {
                case UP:
                    totalMeshUpTime += System.currentTimeMillis() - lastMeshStatusTime;
                    break;

                case DEGRADED:
                    totalMeshDegradedTime += System.currentTimeMillis() - lastMeshStatusTime;
                    break;

                case DOWN:
                    totalMeshDownTime += System.currentTimeMillis() - lastMeshStatusTime;
                    break;
            }
            lastMeshStatusTime = System.currentTimeMillis();
            meshStatus = currentStatus;
        }
    }

    /**
     * Conduct any periodic housekeeping tasks
     */
    public void executePeriodicTasks() {
        ipcBroadcastIfNeeded();
        evalutateMeshStatus();
        if (btManet != null)
            btManet.executePeriodicTasks();
        if (wifiManet != null)
            wifiManet.executePeriodicTasks();
        SqAnDevice.updateDeviceRoutePreferences();
    }

    /**
     * Request received over IPC to transmit data
     * @param packet
     */
    @Override
    public void onIpcPacketReceived(AbstractPacket packet) {
        Log.d(Config.TAG,"Received a request for a burst via IPC");
        if ((packet != null) && (!packet.isAdminPacket())) //other apps are not allowed to send Admin packets
            burst(packet);
    }

    /**
     * Does the current mesh strategy include a bluetooth manet
     * @return
     */
    public boolean isBtManetSelected() {
        return (btManet != null);
    }

    /**
     * Does the current mesh strategy include a WiFi Direct manet
     * @return
     */
    public boolean isWiFiDirectManetSelected() {
        return ((wifiManet != null) && (wifiManet instanceof WiFiDirectManet));
    }

    /**
     * Does the current mesh strategy include a WiFi Direct manet
     * @return
     */
    public boolean isWiFiAwareManetSelected() {
        return ((wifiManet != null) && (wifiManet instanceof WiFiAwareManet));
    }

    public boolean isBtManetAvailable() {
        if (!isBtManetSelected() || !shouldBeActive)
            return false;
        return btManet.isRunning();
    }

    public boolean isWiFiManetAvailable() {
        if ((wifiManet == null) || !shouldBeActive)
            return false;
        return wifiManet.isRunning();
    }
}
