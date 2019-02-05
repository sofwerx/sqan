package org.sofwerx.sqan;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import org.sofwerx.sqan.ipc.IpcBroadcastTransceiver;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.ChannelBytesPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.RawBytesPacket;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.wifiaware.WiFiAwareManet;
import org.sofwerx.sqan.manet.wifidirect.WiFiDirectManet;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.util.ArrayList;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps implements ManetListener, IpcBroadcastTransceiver.IpcBroadcastListener {
    private final SqAnService sqAnService;
    private AbstractManet manet;
    private static long transmittedByteTally = 0l;
    private HandlerThread manetThread; //the MANET itself runs on this thread where possible
    private Handler handler;
    private ManetOps manetOps;
    private boolean shouldBeActive = true;

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
                    manetType = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(sqAnService).getString(Config.PREFS_MANET_ENGINE,"2"));
                } catch (NumberFormatException e) {
                }
                switch (manetType) {
                    case 1:
                        manet = new NearbyConnectionsManet(handler,sqAnService,manetOps);
                        break;

                    case 2:
                        manet = new WiFiAwareManet(handler,sqAnService,manetOps);
                        break;

                    case 3:
                        manet = new WiFiDirectManet(handler,sqAnService,manetOps);
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
        IpcBroadcastTransceiver.unregister(sqAnService);
        shouldBeActive = false;
        if (manet != null) {
            if (manetThread != null) {
                if (handler != null) {
                    handler.removeCallbacksAndMessages(null);
                    handler.post(() -> {
                        try {
                            manet.burst(new DisconnectingPacket(Config.getThisDevice().getUUID()));
                            Log.d(Config.TAG,"Sending hangup notification to network");
                        } catch (ManetException ignore) {
                        }
                    });
                    handler.postDelayed(() -> {
                        try {
                            manet.disconnect();
                            manet = null;
                            Log.d(Config.TAG,"Disconnected from MANET normally");
                        } catch (ManetException e) {
                            Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                        }
                    }, 1000l); //give the link 5 seconds to announce the devices departure
                } else {
                    try {
                        manet.disconnect();
                        Log.d(Config.TAG,"Disconnected from MANET with handler already closed");
                        manet = null;
                        if (manetThread != null) {
                            manetThread.quitSafely();
                            manetThread = null;
                            handler = null;
                        }
                    } catch (ManetException e) {
                        Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                    }
                }
            } else {
                try {
                    if (manet != null) {
                        manet.disconnect();
                        Log.d(Config.TAG,"Disconnected from MANET with manetThread already closed");
                        manet = null;
                    }
                } catch (ManetException e) {
                    Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                }
            }
        }
    }

    public void pause() {
        shouldBeActive = false;
        if (handler != null)
            handler.removeCallbacks(null);
        if (manet != null) {
            try {
                manet.pause();
            } catch (ManetException e) {
                Log.e(Config.TAG,"Unable to pause MANET: "+e.getMessage());
            }
        }
    }

    public void start() {
        shouldBeActive = true;
        if (handler != null) {
            handler.post(() -> {
                //if ((manet != null) && !manet.isRunning()) {
                if (manet != null) {
                    try {
                        sqAnService.onStatusChange(Status.OFF, null);
                        manet.init();
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
            } else if (packet instanceof HeartbeatPacket) {
                SqAnDevice device = ((HeartbeatPacket)packet).getDevice();
                if (device != null) {
                    device.setConnected();
                    SqAnDevice.add(device);
                    onDevicesChanged(device);
                }
            }
            //TODO actually do something with the packet
        }
    }

    @Override
    public void onTx(AbstractPacket packet) {
        if (sqAnService.listener != null)
            sqAnService.listener.onDataTransmitted();
        sqAnService.onPositiveComms();
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
        if ((device != null) && device.isActive())
            sqAnService.requestHeartbeat();
        sqAnService.notifyStatusChange(null);
    }

    @Override
    public void updateDeviceUi(SqAnDevice device) {
        if (sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
    }

    public Status getStatus() {
        if (manet == null)
            return Status.OFF;
        return manet.getStatus();
    }

    public AbstractManet getManet() { return manet; }

    public void burst(AbstractPacket packet) {
        if ((packet == null) || (manet == null))
            Log.d(Config.TAG,"ManetOps cannot burst the packet "+((packet==null)?"null packet":"null manet"));
        else {
            try {
                manet.burst(packet);
            } catch (ManetException e) {
                Log.e(Config.TAG,"Unable to burst packet: "+e.getMessage());
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
        evalutateMeshStatus();
        if (manet != null)
            manet.executePeriodicTasks();
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
}
