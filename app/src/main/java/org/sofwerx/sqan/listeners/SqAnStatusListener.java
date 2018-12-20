package org.sofwerx.sqan.listeners;

import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;

public interface SqAnStatusListener {
    /**
     * Called when the service itself experiences some status (usually a change it received from the
     * MANET itself). Note that this status may not actually be a change but may be another trigger
     * of the current status.
     * @param status
     */
    void onStatus(Status status);

    /**
     * Called when a node on the MANET changes
     * @param device the node that changed (null ==  there was some change that may effect all nodes)
     */
    void onNodesChanged(SqAnDevice device);

    /**
     * Called when data has been transmitted. Primarily used to update GUI to show transmission activity
     */
    void onDataTransmitted();
}
