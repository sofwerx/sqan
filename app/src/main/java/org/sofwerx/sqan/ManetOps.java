package org.sofwerx.sqan;

import android.util.Log;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps implements ManetListener {
    private final SqAnService sqAnService;
    private AbstractManet manet;

    public ManetOps(SqAnService sqAnService) {
        this.sqAnService = sqAnService;
        manet = new NearbyConnectionsManet(sqAnService,this); //TODO temporary for testing
    }

    /**
     * Shutdown the MANET
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
    }

    public void start() {
        if ((manet != null) && !manet.isRunning()) {
            try {
                sqAnService.onStatusChange(Status.OFF,null);
                manet.init();
            } catch (ManetException e) {
                sqAnService.onStatusChange(Status.ERROR,e.getMessage());
            }
        }
    }

    @Override
    public void onStatus(Status status) {
        sqAnService.onStatusChange(status,null);
    }

    @Override
    public void onRx(AbstractPacket packet) {

    }

    @Override
    public void onDevicesChanged(SqAnDevice device) {
        if (sqAnService.listener != null)
            sqAnService.listener.onNodesChanged(device);
    }

    public Status getStatus() {
        if (manet == null)
            return Status.OFF;
        return manet.getStatus();
    }
}
