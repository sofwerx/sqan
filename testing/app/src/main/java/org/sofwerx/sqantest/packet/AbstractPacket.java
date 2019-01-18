package org.sofwerx.sqantest.packet;

import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Data bundled for transmission over the MANET
 */
public abstract class AbstractPacket {
    //TODO make a ChallengePacket for handling sign/countersign
    protected PacketHeader packetHeader;

    public AbstractPacket(PacketHeader packetHeader) {
        if (packetHeader != null)
            packetHeader.setType(getType());
        this.packetHeader = packetHeader;
    }

    public PacketHeader getPacketHeader() {
        return packetHeader;
    }

    //creates a new packet from the byte array
    public static AbstractPacket newFromBytes(byte[] bytes) {
        if ((bytes == null) || (bytes.length < PacketHeader.getSize())) {
            Log.d("SqAn","Unable to generate a packet from the byte array; byte array was not big enough to hold a header");
        }
        AbstractPacket packet = null;
        ByteBuffer reader = ByteBuffer.wrap(bytes);

        byte[] headerBytes = new byte[PacketHeader.getSize()];
        reader.get(headerBytes);
        PacketHeader header = PacketHeader.newFromBytes(headerBytes);

        if (header == null) {
            Log.d("SqAn","Unable to generate a packet header from the byte array");
            return null;
        }

        packet = AbstractPacket.newFromHeader(header);
        if (packet == null) {
            Log.d("SqAn","Unable to generate a packet from the packet header");
            return null;
        }

        int len = reader.remaining();
        if (len > 0) {
            byte[] packetBytes = new byte[len];
            reader.get(packetBytes);
            packet.parse(packetBytes);
        }
        reader.clear();
        return packet;
    }

    public byte[] toByteArray() {
        if (packetHeader == null)
            return null;
        return packetHeader.toByteArray();
    }

    public static AbstractPacket newFromHeader(PacketHeader packetHeader) {
        if (packetHeader == null) {
            Log.d("SqAn","Cannot generate a Packet from an empty packet header");
            return null;
        }

        AbstractPacket packet = null;

        switch (packetHeader.getType()) {
            case PacketHeader.PACKET_TYPE_RAW_BYTES:
                packet = new RawBytesPacket(packetHeader);
                break;

            case PacketHeader.PACKET_TYPE_HEARTBEAT:
                packet = new HeartbeatPacket(packetHeader);
                break;

            case PacketHeader.PACKET_TYPE_PING:
                packet = new PingPacket(packetHeader);
                break;

            case PacketHeader.PACKET_TYPE_CHANNEL_BYTES:
                packet = new ChannelBytesPacket(packetHeader);
                break;

            //TODO case PacketHeader.PACKET_TYPE_CHALLENGE:
        }

        return packet;
    }

    /**
     * Admin packets (such as a ping or heartbeat) are used to help maintain the network
     * and do not carry significant data (but can have some data)
     * @return true == this is an admin packet
     */
    public abstract boolean isAdminPacket();
    public abstract void parse(byte[] bytes);
    protected abstract int getType();
}