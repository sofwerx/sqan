package org.sofwerx.sqan.manet.common.packet;

import org.sofwerx.sqan.manet.common.pnt.NetworkTime;

import java.nio.ByteBuffer;

/**
 * Provides a ping mechanism. Functions as so:
 *  - Device A sets the departureTime to the current device local time and sends the packet
 *  - Device B receives the packet, sees that isAPingRequest is true then sets the
 *      midpointLocalTime to Device B's current device local time and then sends the packet back
 *  - Device A receives the packet, sees that isPingRequest is false and handles result
 */
public class PingPacket extends AbstractPacket {
    private long midpointLocalTime = -1l;
    private long latency = -1l; //this gets calculated

    public PingPacket(int originUUID, int destinationUUID) {
        super(new PacketHeader(originUUID));
        packetHeader.setDestination(destinationUUID);
        packetHeader.setType(getType());
        packetHeader.setTime(NetworkTime.getNetworkTimeNow());
    }

    public PingPacket(PacketHeader packetHeader) {
        super(packetHeader);
    }

    private final static int INTERNAL_LENGTH = 8;

    @Override
    public void parse(byte[] bytes) {
        if ((bytes == null) || (bytes.length != INTERNAL_LENGTH))
            midpointLocalTime = -1;
        else {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            midpointLocalTime = buf.getLong();
        }
    }

    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();

        if (superBytes == null)
            return null;
        ByteBuffer out = ByteBuffer.allocate(superBytes.length + INTERNAL_LENGTH);
        out.put(superBytes);
        out.putLong(midpointLocalTime);
        return out.array();
    }

    @Override
    protected byte getType() {
        return PacketHeader.PACKET_TYPE_PING;
    }

    /**
     * Gets the time when the packet leaves the origination station (in local time)
     * @return departure time (local device time)
     */
    public long getDepartureTime() {
        if (packetHeader == null)
            return -1l;
        else
            return packetHeader.getTime();
    }

    /**
     * Gets the time when the packet reaches the destination before being returned to the sender
     * @return time (local device time of the destination device)
     */
    public long getMidpointLocalTime() { return midpointLocalTime; }

    /**
     * Sets the time when the packet reaches the destination before being returned to the sender
     * @param midpointLocalTime local device time of the destination device
     */
    public void setMidpointLocalTime(long midpointLocalTime) { this.midpointLocalTime = midpointLocalTime; }

    /**
     * Is this ping halfway through its transit (i.e. does this ping packet need to be returned). Note
     * that if both a departureTime and a midpointLocalTime exist and latency has not been calculated
     * yet, then this call records the latency
     * @return true == ping needs to be returned
     */
    public boolean isAPingRequest() {
        if (midpointLocalTime < 0l)
            return true;
        else {
            if (latency < 0l)
                latency = System.currentTimeMillis() - getDepartureTime();
            return false;
        }
    }

    @Override
    protected byte getChecksum() {
        return PacketHeader.calcChecksum(null);
    }

    public long getLatency() {
        if ((getDepartureTime() < 0l) || (midpointLocalTime < 0l))
            return -1l;
        else
            return latency;
    }

    @Override
    public boolean isAdminPacket() { return true; }
}
