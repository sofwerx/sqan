package org.sofwerx.sqan.listeners;

import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

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
     * Called when a byte array is transmitted over the MANET
     * @param payload
     */
    void onTx(byte[] payload);

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

    /**
     * Updates any display for this device
     * @param device
     */
    void updateDeviceUi(SqAnDevice device);

    /**
     * Called once this device has been authenticated on a new net
     */
    void onAuthenticatedOnNet();

    void onPacketDropped();

    void onHighNoise(float snr);
}
