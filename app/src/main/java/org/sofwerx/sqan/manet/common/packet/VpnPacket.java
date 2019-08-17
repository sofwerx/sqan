package org.sofwerx.sqan.manet.common.packet;

import org.sofwerx.sqan.manet.common.VpnForwardValue;

import java.nio.ByteBuffer;

/**
 * VPN intercepted data
 */
public class VpnPacket extends AbstractPacket {
    private byte[] data;
    private VpnForwardValue forwardValue;

    public VpnPacket(PacketHeader packetHeader) {
        super(packetHeader);
        data = null;
        forwardValue = null;
    }

    /**
     * Is this packet being forwarded to/from an IP address behind the connected SqAN device
     * @return true == packet is being forwarded
     */
    public boolean isForwarded() { return forwardValue != null; }

    @Override
    public void parse(byte[] bytes) {
        if (bytes == null)
            return;
        ByteBuffer in = ByteBuffer.wrap(bytes);
        forwardValue = new VpnForwardValue(in.get());
        if (!forwardValue.isForwarded())
            forwardValue = null;
        if (in.remaining() > 0) {
            data = new byte[in.remaining()];
            in.get(data);
        }
    }

    @Override
    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();
        int len;
        if (data == null)
            len = 1;
        else
            len = data.length + 1;
        ByteBuffer out = ByteBuffer.allocate(superBytes.length + len);
        out.put(superBytes);
        if ((forwardValue == null) || !forwardValue.isForwarded())
            out.put(VpnForwardValue.NOT_FORWARDED);
        else
            out.put(forwardValue.getForwardIndex());
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

    public void setForwardValue(VpnForwardValue forwardValue) { this.forwardValue = forwardValue; }
}
