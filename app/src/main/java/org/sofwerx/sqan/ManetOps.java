package org.sofwerx.sqan;

import android.util.Log;

import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;

/**
 * This class handles all of the SqAnService's interaction with the MANET itself. It primarily exists
 * to bring the MANET code out of the SqAnService to improve readability.
 */
public class ManetOps {
    private final SqAnService sqAnService;
    public ManetOps(SqAnService sqAnService) { this.sqAnService = sqAnService; }
    private AbstractManet manet;

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
}
