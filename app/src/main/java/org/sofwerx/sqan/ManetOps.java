package org.sofwerx.sqan;

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.sofwerx.sqan.ipc.IpcBroadcastTransceiver;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.wifiaware.WiFiAwareManet;
import org.sofwerx.sqan.util.StringUtil;

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

    public ManetOps(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        this.manetOps = this;
        manetThread = new HandlerThread("ManetOps") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(manetThread.getLooper());
                //manet = new WiFiAwareManet(handler,sqAnService,manetOps);
                manet = new NearbyConnectionsManet(handler,sqAnService,manetOps); //TODO temporary for testing
                if (SqAnService.checkSystemReadiness() && shouldBeActive)
                    manetOps.start();
                if (Config.isAllowIpcComms())
                    IpcBroadcastTransceiver.registerAsSqAn(sqAnService,manetOps);
            }
        };
        manetThread.start();
    }

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
                            Log.d(Config.TAG,"Disconnected from MANET");
                        } catch (ManetException e) {
                            Log.e(Config.TAG, "ManetOps is unable to shutdown MANET: " + e.getMessage());
                        }
                    }, 1000l); //give the link 5 seconds to announce the devices departure
                } else {
                    try {
                        manet.disconnect();
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
                if ((manet != null) && !manet.isRunning()) {
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
                byte[] data = packet.toByteArray();
                if (data != null) {
                    Log.d(Config.TAG,"Broadcasting "+ StringUtil.toDataSize((long)data.length)+" over IPC");
                    IpcBroadcastTransceiver.broadcast(sqAnService, data);
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
                device.setConnected();
                SqAnDevice.add(device);
                onDevicesChanged(device);
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
        if (sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
        sqAnService.requestHeartbeat();
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

    /**
     * Conduct any periodic housekeeping tasks
     */
    public void executePeriodicTasks() {
        if (manet != null)
            manet.executePeriodicTasks();
    }

    /**
     * Request received over IPC to transmit data
     * @param packet
     */
    @Override
    public void onIpcPacketReceived(byte[] packet) {
        Log.d(Config.TAG,"Received a request for a burst via IPC");
        AbstractPacket abstractPacket = AbstractPacket.newFromBytes(packet);
        if ((abstractPacket != null) && !abstractPacket.isAdminPacket()) //other apps are not allowed to send Admin packets
            burst(abstractPacket);
    }
}
