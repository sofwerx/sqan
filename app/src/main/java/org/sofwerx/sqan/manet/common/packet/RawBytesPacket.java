package org.sofwerx.sqan.manet.common.packet;

import java.nio.ByteBuffer;

/**
 * The most basic type of packet which just consists of raw data
 */
public class RawBytesPacket extends AbstractPacket {
    private byte[] data;

    public RawBytesPacket(PacketHeader packetHeader) {
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
        return PacketHeader.PACKET_TYPE_RAW_BYTES;
    }


    @Override
    protected byte getChecksum() { return PacketHeader.calcChecksum(data); }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    @Override
    public boolean isAdminPacket() { return false; }
}
