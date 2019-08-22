package org.sofwerx.sqan.listeners;

import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;

import java.util.ArrayList;

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

    /**
     * Called when a pre-requisite check completed
     * @param isReady true == the system is ready for the selected MANET
     */
    void onSystemReady(boolean isReady);

    /**
     * Called when a conflict is found in IDs with another device
     * @param conflictingDevice
     */
    void onConflict(SqAnDevice conflictingDevice);

    void onHighNoise(float snr);
}
