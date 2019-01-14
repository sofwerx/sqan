package org.sofwerx.sqan;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps implements ManetListener {
    private final SqAnService sqAnService;
    private AbstractManet manet;
    private static long transmittedByteTally = 0l;
    private HandlerThread manetThread; //the MANET itself runs on this thread where possible
    private Handler handler;
    private ManetOps manetOps;

    public ManetOps(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        this.manetOps = this;
        manetThread = new HandlerThread("Manet") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(manetThread.getLooper());
                manet = new NearbyConnectionsManet(handler,sqAnService,manetOps); //TODO temporary for testing
            }
        };
        manetThread.start();
    }

    /**
     * Shutdown the MANET and frees all resources
     */
    public void shutdown() {
        if (manet != null) {
            try {
                manet.disconnect();
                manet = null;
            } catch (ManetException e) {
                Log.e(Config.TAG,"ManetOps is unable to shutdown MANET: "+e.getMessage());
            }
        }
        if (manetThread != null) {
            manetThread.quitSafely();
            manetThread = null;
            handler = null;
        }
    }

    public void start() {
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
        //TODO actually do something with the packet
    }

    @Override
    public void onTx(AbstractPacket packet) {
        if (sqAnService.listener != null)
            sqAnService.listener.onDataTransmitted();
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
}
