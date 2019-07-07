package org.sofwerx.sqan.manet.common.packet;

import org.sofwerx.sqan.manet.common.pnt.NetworkTime;

import java.nio.ByteBuffer;

/**
 * This packet serves to notify other nodes that the device is leaving the network
 */
public class DisconnectingPacket extends AbstractPacket {
    public DisconnectingPacket(int uuid) {
        super(new PacketHeader(uuid));
        packetHeader.setType(getType());
        packetHeader.setTime(NetworkTime.getNetworkTimeNow());
    }

    public DisconnectingPacket(PacketHeader packetHeader) {
        super(packetHeader);
    }

    @Override
    public void parse(byte[] bytes) {
        //ignore
    }

    public byte[] toByteArray() {
        return super.toByteArray();
    }

    /**
     * Gets the UUID of the device leaving the mesh
     * @return
     */
    public int getUuidOfDeviceLeaving() {
        return packetHeader.getOriginUUID();
    }

    @Override
    protected byte getType() {
        return PacketHeader.PACKET_TYPE_DISCONNECTING;
    }

    @Override
    public boolean isAdminPacket() { return true; }

    @Override
    protected byte getChecksum() {
        return PacketHeader.calcChecksum(null);
    }
}
