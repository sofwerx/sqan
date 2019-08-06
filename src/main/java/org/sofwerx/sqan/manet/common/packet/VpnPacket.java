package org.sofwerx.sqan.manet.common.packet;

import java.nio.ByteBuffer;

/**
 * The most basic type of packet which just consists of raw data
 */
public class VpnPacket extends AbstractPacket {
    private byte[] data;

    public VpnPacket(PacketHeader packetHeader) {
        super(packetHeader);
        data = null;
    }

    @Override
    public void parse(byte[] bytes) {
        data = bytes;
    }

    @Override
    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();
        int len;
        if (data == null)
            len = 0;
        else
            len = data.length;
        ByteBuffer out = ByteBuffer.allocate(superBytes.length + len);
        out.put(superBytes);
        if (len > 0)
            out.put(data);
        return out.array();
    }

    @Override
    protected byte getType() {
        return PacketHeader.PACKET_TYPE_VPN_BYTES;
    }


    @Override
    protected byte getChecksum() {
        return PacketHeader.calcChecksum(null); // checksum cals for VPN packets are not calculated as this is usually handled within the packet traffic itself (like in an HTTP header) and would cost additional processor time
    }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    @Override
    public boolean isAdminPacket() { return false; }
}
