package org.sofwerx.sqan;

import android.os.Handler;
import android.os.HandlerThread;
import android.preference.PreferenceManager;
import android.util.Log;

import org.sofwerx.sqan.ipc.IpcBroadcastTransceiver;
import org.sofwerx.sqan.ipc.IpcSaBroadcastTransmitter;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.bt.BtManetV2;
import org.sofwerx.sqan.manet.bt.helper.BTSocket;
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
import org.sofwerx.sqan.manet.sdr.SdrManet;
import org.sofwerx.sqan.manet.wifiaware.WiFiAwareManetV2;
import org.sofwerx.sqan.manet.wifidirect.WiFiDirectManet;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import static androidx.constraintlayout.widget.Constraints.TAG;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps implements ManetListener, IpcBroadcastTransceiver.IpcBroadcastListener {
    private final static long MIN_SA_IPC_BROADCAST_DELAY = 250l; //dont do SA IPC broadcasts with any interval shorter than this
    private SqAnService sqAnService;
    private AbstractManet wifiManet;
    private BtManetV2 btManet;
    private SdrManet sdrManet;
    private static long transmittedByteTally = 0l;
    private static long nextLoggerTransmittedBytes = 0l;
    private final static long BYTES_TO_TX_BETWEEN_LOGGING = 1024l * 1024l;
    private HandlerThread manetThread; //the MANET itself runs on this thread where possible
    private Handler handler;
    private boolean shouldBeActive = true;
    private long nextEligibleSaIpcBroadcast = Long.MIN_VALUE;

    //for capturing overall up time analytics
    private static SqAnDevice.FullMeshCapability meshStatus = SqAnDevice.FullMeshCapability.DOWN;
    private static long lastMeshStatusTime = System.currentTimeMillis();
    private static long totalMeshUpTime = 0l;
    private static long totalMeshDegradedTime = 0l;
    private static long totalMeshDownTime = 0l;

    private long noiseReported = Long.MIN_VALUE;
    private int droppedPackets = 0;
    private long dropStartTime = Long.MIN_VALUE;
    private final static long INTERVAL_TO_MEASURE_NOISE = 1000l * 1l;
    private final static int DROP_THRESHOLD = 10;

    public ManetOps(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        //this.manetOps = this;
        manetThread = new HandlerThread("ManetOps") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(manetThread.getLooper());
                int manetType = 0;
                try {
                    manetType = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(sqAnService).getString(Config.PREFS_MANET_ENGINE,"4"));
                } catch (NumberFormatException ignore) {
                }
                wifiManet = null;
                btManet = null;
                sdrManet = null;
                switch (manetType) {
                    case 1:
                        wifiManet = new NearbyConnectionsManet(handler,sqAnService,ManetOps.this);
                        //sdrManet = new SdrManet(handler,sqAnService,ManetOps.this);  //TODO uncomment this to enable SDR for Nearby Connections
                        break;

                    case 2:
                        wifiManet = new WiFiAwareManetV2(handler,sqAnService,ManetOps.this);
                        //btManet = new BtManetV2(handler,sqAnService,manetOps); //TODO uncomment this to enable bluetooth for WiFi Aware
                        //sdrManet = new SdrManet(handler,sqAnService,ManetOps.this); //TODO uncomment this to enable SDR for WiFi Aware
                        break;

                    case 3:
                        wifiManet = new WiFiDirectManet(handler,sqAnService,ManetOps.this);
                        //btManet = new BtManetV2(handler,sqAnService,ManetOps.this); //TODO uncomment this to enable bluetooth for WiFi Direct
                        //sdrManet = new SdrManet(handler,sqAnService,ManetOps.this); //TODO uncomment this to enable SDR for WiFi Direct
                        break;

                    case 4:
                        btManet = new BtManetV2(handler,sqAnService,ManetOps.this);
                        //sdrManet = new SdrManet(handler,sqAnService,ManetOps.this); //TODO uncomment this to enable SDR for Bluetooth
                        break;

                    case 5:
                        sdrManet = new SdrManet(handler,sqAnService,ManetOps.this);
                        break;

                    default:
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"No MANET engine selected so no MANET will be used.");
                        return;
                }
                if (SqAnService.checkSystemReadiness() && shouldBeActive)
                    ManetOps.this.start();
                if (Config.isAllowIpcComms())
                    IpcBroadcastTransceiver.registerAsSqAn(sqAnService,ManetOps.this);
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
                            Log.d(Config.TAG, "Sending hangup notification to WiFi MANET");
                            wifiManet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                        }
                    } catch (ManetException ignore) {
                    }
                    try {
                        if (btManet != null) {
                            Log.d(Config.TAG, "Sending hangup notification to Bluetooth MANET");
                            btManet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                        }
                    } catch (ManetException ignore) {
                    }
                    try {
                        if (sdrManet != null) {
                            Log.d(Config.TAG, "Sending hangup notification to SDR MANET");
                            sdrManet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                        }
                    } catch (ManetException ignore) {
                    }
                    if (handler != null)
                        handler.postDelayed(() -> {
                            Log.d(Config.TAG,"Preparing to disconnect MANETs...");
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
                            try {
                                if (sdrManet != null) {
                                    sdrManet.disconnect();
                                    sdrManet = null;
                                    Log.d(Config.TAG, "Disconnected from SDR MANET normally");
                                }
                            } catch (ManetException e) {
                                Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                            }
                        }, 1000l); //give the link 1 second to announce the device's departure
                    else {
                        try {
                            if (wifiManet != null) {
                                wifiManet.disconnect();
                                wifiManet = null;
                                Log.d(Config.TAG, "Disconnected from WiFi MANET without handler");
                            }
                        } catch (ManetException e) {
                            Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                        }
                        try {
                            if (btManet != null) {
                                btManet.disconnect();
                                btManet = null;
                                Log.d(Config.TAG, "Disconnected from Bluetooth MANET without handler");
                            }
                        } catch (ManetException e) {
                            Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                        }
                        try {
                            if (sdrManet != null) {
                                sdrManet.disconnect();
                                sdrManet = null;
                                Log.d(Config.TAG, "Disconnected from SDR MANET without handler");
                            }
                        } catch (ManetException e) {
                            Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                        }
                    }
                });
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
                try {
                    if (sdrManet != null) {
                        sdrManet.disconnect();
                        Log.d(Config.TAG, "Disconnected from SDR MANET with handler already closed");
                        sdrManet = null;
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
            try {
                if (sdrManet != null) {
                    sdrManet.disconnect();
                    Log.d(Config.TAG,"Disconnected from SDR MANET with manetThread already closed");
                    sdrManet = null;
                }
            } catch (ManetException e) {
                Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
            }
        }
        sqAnService = null;
        manetThread = null;
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
        if (sdrManet != null) {
            try {
                sdrManet.pause();
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
                if (sdrManet != null) {
                    try {
                        sdrManet.init();
                    } catch (ManetException e) {
                        sqAnService.onStatusChange(Status.ERROR, e.getMessage());
                    }
                }
            });
        }
    }

    @Override
    public void onStatus(Status status) {
        if (sqAnService != null)
            sqAnService.onStatusChange(status,null);
    }

    @Override
    public void onRx(final AbstractPacket packet) {
        if (packet != null) {
            if (handler != null)
                handler.post(() -> {
                    if (Config.isAllowIpcComms() && !packet.isAdminPacket()) {
                        byte[] data = null;
                        String channel = null;
                        if (packet instanceof ChannelBytesPacket) {
                            channel = ((ChannelBytesPacket) packet).getChannel();
                            data = ((ChannelBytesPacket) packet).getData();
                        } else if (packet instanceof RawBytesPacket)
                            data = ((RawBytesPacket) packet).getData();
                        if (data != null) {
                            IpcBroadcastTransceiver.broadcast(sqAnService, channel, packet.getOrigin(), data);
                            Log.d(Config.TAG, "Broadcasting " + StringUtil.toDataSize((long) data.length) + " over IPC");
                        }
                    }
                    if (packet instanceof DisconnectingPacket) {
                        SqAnDevice outgoing = SqAnDevice.findByUUID(((DisconnectingPacket) packet).getUuidOfDeviceLeaving());
                        if (outgoing != null) {
                            if (outgoing.getCallsign() == null)
                                Log.d(Config.TAG, Integer.toString(outgoing.getUUID()) + " reporting leaving mesh");
                            else
                                Log.d(Config.TAG, outgoing.getCallsign() + " reporting leaving mesh");
                            outgoing.setStatus(SqAnDevice.Status.OFFLINE);
                            onDevicesChanged(outgoing);
                        } else
                            Log.d(Config.TAG, "Disconnect packet received, but unable to find corresponding device");
                    } else if (packet instanceof HeartbeatPacket) {
                        ipcBroadcastIfNeeded();
                    } else if (packet instanceof VpnPacket) {
                        sqAnService.onRxVpnPacket((VpnPacket) packet);
                    }
                });
        }
    }

    @Override
    public void onTx(final AbstractPacket packet) {
        if (packet == null)
            return;
        if (handler != null)
            handler.post(() -> {
                if (sqAnService == null)
                    return;
                if (sqAnService.listener != null)
                    sqAnService.listener.onDataTransmitted();
                sqAnService.onPositiveComms();
            });
    }

    @Override
    public void onTx(final byte[] payload) {
        if (handler != null)
            handler.post(() -> {
                if (sqAnService != null) {
                    if (sqAnService.listener != null)
                        sqAnService.listener.onDataTransmitted();
                    sqAnService.onPositiveComms();
                }
            });
    }

    private void ipcBroadcastIfNeeded() {
        if ((System.currentTimeMillis() > nextEligibleSaIpcBroadcast) && Config.isBroadcastSa()) {
            if (handler != null)
                handler.post(() -> IpcSaBroadcastTransmitter.broadcast(sqAnService));
            nextEligibleSaIpcBroadcast = System.currentTimeMillis() + MIN_SA_IPC_BROADCAST_DELAY;
        }
    }

    @Override
    public void onTxFailed(final AbstractPacket packet) {
        if (packet == null)
            return;
        if (handler != null)
            handler.post(() -> {
                //TODO decide if packet should be dropped or resent; maybe check network health as well
            });
    }

    /**
     * Used to keep a running total of transmitted data
     * @param bytes
     */
    public static void addBytesToTransmittedTally(int bytes) {
        transmittedByteTally += bytes;
        if (transmittedByteTally > nextLoggerTransmittedBytes) {
            nextLoggerTransmittedBytes = transmittedByteTally + BYTES_TO_TX_BETWEEN_LOGGING;
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,StringUtil.toDataSize(transmittedByteTally)+" transmitted");
        }
    }

    /**
     * Gets the total tally of bytes transmitted
     * @return
     */
    public static long getTransmittedByteTally() { return transmittedByteTally; }

    @Override
    public void onDevicesChanged(final SqAnDevice device) {
        if (handler != null)
            handler.post(() -> {
                evaluateMeshStatus();
                if (sqAnService != null) {
                    if (sqAnService.listener != null)
                        sqAnService.listener.onNodesChanged(device);
                    sqAnService.notifyStatusChange(null);
                }
            });
    }

    @Override
    public void updateDeviceUi(SqAnDevice device) {
        if ((sqAnService != null) && sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
    }

    @Override
    public void onAuthenticatedOnNet() {
        if (handler != null)
            handler.post(() -> {if (sqAnService != null) sqAnService.requestHeartbeat(true);});
    }

    @Override
    public void onPacketDropped() {
        //ignore
    }

    @Override
    public void onHighNoise(final float snr) {
        Log.d(TAG,"onHighNoise");
        if (handler != null) {
            handler.post(() -> sqAnService.handleHighNoise(snr));
        }
    }

    public Status getStatus() {
        int wifi;
        if (wifiManet == null)
            wifi = Status.OFF.ordinal();
        else
            wifi = wifiManet.getStatus().ordinal();
        int bt;
        if (btManet == null)
            bt = Status.OFF.ordinal();
        else
            bt = btManet.getStatus().ordinal();
        int sdr;
        if (sdrManet == null)
            sdr = Status.OFF.ordinal();
        else
            sdr = sdrManet.getStatus().ordinal();
        int max = Math.max(wifi,bt);
        max = Math.max(max,sdr);
        return Status.values()[max];
    }

    public AbstractManet getWifiManet() { return wifiManet; }
    public AbstractManet getBtManet() { return btManet; }
    public SdrManet getSdrManet() { return sdrManet; }

    public void burst(AbstractPacket packet) {
        burst(packet, TransportPreference.AGNOSTIC);
    }

    public void burst(final AbstractPacket packet, final TransportPreference preferredTransport) {
        if (packet == null) {
            Log.d(Config.TAG, "ManetOps cannot burst a null packet");
            return;
        }
        if ((btManet == null) && (wifiManet == null) && (sdrManet == null))
            Log.d(Config.TAG,"ManetOps cannot burst without an available MANET");
        else {
            if (handler != null) {
                handler.post(() -> {
                    try {
                        if ((preferredTransport == null) || (preferredTransport == TransportPreference.AGNOSTIC)) {
                            boolean btGood = (btManet != null) && (btManet.getStatus() == Status.CONNECTED);
                            boolean wifiGood = (wifiManet != null) && (wifiManet.getStatus() == Status.CONNECTED);
                            boolean sdrGood = (sdrManet != null) && (sdrManet.getStatus() == Status.CONNECTED);
                            if (btGood && wifiGood) {
                                if (packet.isHighPerformanceNeeded())
                                    wifiManet.burst(packet);
                                else {
                                    if (PacketHeader.BROADCAST_ADDRESS == packet.getSqAnDestination()) {
                                        wifiManet.burst(packet);
                                        if (!BTSocket.isCongested() && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                            btManet.burst(packet);
                                    } else {
                                        SqAnDevice device = SqAnDevice.findByUUID(packet.getSqAnDestination());
                                        if (device == null) {
                                            wifiManet.burst(packet);
                                            if (!BTSocket.isCongested())
                                                btManet.burst(packet);
                                        } else {
                                            if (BTSocket.isCongested())
                                                wifiManet.burst(packet);
                                            else {
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
                                }
                            } else {
                                //at least one mesh isn't completely healthy so send over both
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                if ((btManet != null) && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                    btManet.burst(packet);
                                if (sdrGood)
                                    sdrManet.burst(packet);
                            }
                        } else {
                            if ((preferredTransport == null) || (preferredTransport == TransportPreference.ALL)) {
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                if ((btManet != null) && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                    btManet.burst(packet);
                                if ((sdrManet != null))
                                    sdrManet.burst(packet);
                            } else if (preferredTransport == TransportPreference.WIFI) {
                                if (wifiManet != null)
                                    wifiManet.burst(packet);
                                else {
                                    if ((btManet != null) && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                        btManet.burst(packet);
                                    else if (sdrManet != null)
                                        sdrManet.burst(packet);
                                }
                            } else if (preferredTransport == TransportPreference.BLUETOOTH) {
                                if ((btManet != null) && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                    btManet.burst(packet);
                                else {
                                    if (wifiManet != null)
                                        wifiManet.burst(packet);
                                    else if (sdrManet != null)
                                        sdrManet.burst(packet);
                                }
                            } else if (preferredTransport == TransportPreference.SDR) {
                                if (sdrManet != null)
                                    sdrManet.burst(packet);
                                else {
                                    if (wifiManet != null)
                                        wifiManet.burst(packet);
                                    else if ((btManet != null) && (!Config.isLargeDataWiFiOnly() || !packet.isHighPerformanceNeeded()))
                                        btManet.burst(packet);
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

    private void evaluateMeshStatus() {
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
        if (handler != null)
            handler.post(() -> {
                ipcBroadcastIfNeeded();
                evaluateMeshStatus();
                if (btManet != null)
                    btManet.executePeriodicTasks();
                if (wifiManet != null)
                    wifiManet.executePeriodicTasks();
                if (sdrManet != null)
                    sdrManet.executePeriodicTasks();
                SqAnDevice.updateDeviceRoutePreferences();
            });
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
     * Does the current mesh strategy include an SDR manet
     * @return
     */
    public boolean isSdrManetSelected() {
        return (sdrManet != null);
    }

    /**
     * Does the current mesh strategy include a WiFi Direct manet
     * @return
     */
    public boolean isWiFiDirectManetSelected() {
        return ((wifiManet != null) && (wifiManet instanceof WiFiDirectManet));
    }

    /**
     * Does the current mesh strategy include a WiFi Aware manet
     * @return
     */
    public boolean isWiFiAwareManetSelected() {
        return ((wifiManet != null) && (wifiManet instanceof WiFiAwareManetV2));
    }

    public boolean isBtManetAvailable() {
        if (!isBtManetSelected() || !shouldBeActive || (btManet == null))
            return false;
        return btManet.isRunning();
    }

    public boolean isSdrManetAvailable() {
        if (!isSdrManetSelected() || !shouldBeActive || (sdrManet == null))
            return false;
        return sdrManet.isRunning();
    }


    public boolean isSdrManetActive() {
        if (sdrManet == null)
            return false;
        return !sdrManet.isStale();
    }

    public boolean isWiFiManetAvailable() {
        if ((wifiManet == null) || !shouldBeActive || (wifiManet == null))
            return false;
        return wifiManet.isRunning();
    }

    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        Log.d("SqAN.oPM","ManetOps.setPeripheralStatusListener("+((listener==null)?"null":"")+")");
        if (btManet != null)
            btManet.setPeripheralStatusListener(listener);
        if (wifiManet != null)
            wifiManet.setPeripheralStatusListener(listener);
        if (sdrManet != null)
            sdrManet.setPeripheralStatusListener(listener);
    }
}
