package org.sofwerx.sqan.listeners;

import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

public interface ManetListener {
    /**
     * Called when the MANET has a status trigger. Note that this status may not actually
     * be a change but may be another trigger of the current status.
     * @param status the new MANET status
     */
    void onStatus(Status status);

    /**
     * Called when a packet is received from the MANET
     * @param packet the received packet
     */
    void onRx(AbstractPacket packet);

    /**
     * Called when a packet is transmitted over the MANET
     * @param packet the packet that was transmitted
     */
    void onTx(AbstractPacket packet);

    /**
     * Called when this packet failed to transmit
     * @param packet
     */
    void onTxFailed(AbstractPacket packet);

    /**
     * Called when the devices currently on the MANET change
     * @param device the device that was changed (null == check all devices)
     */
    void onDevicesChanged(SqAnDevice device);
}
