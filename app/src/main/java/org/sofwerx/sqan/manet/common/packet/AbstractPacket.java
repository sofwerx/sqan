package org.sofwerx.sqan.manet.common.packet;

//import org.sofwerx.sqan.manet.bt.helper.BTSocket;
import org.sofwerx.sqan.util.CommsLog;

import java.nio.ByteBuffer;

/**
 * Data bundled for transmission over the MANET
 */
public abstract class AbstractPacket {
    //public final static int LARGE_PACKET_SIZE = BTSocket.MAX_PACKET_SIZE;
    protected PacketHeader packetHeader;
    protected boolean highPerformanceNeeded = false;

    public AbstractPacket(PacketHeader packetHeader) {
        if (packetHeader != null) {
            packetHeader.setType(getType());
        }
        this.packetHeader = packetHeader;
    }

    public long getTime() {
        if (packetHeader == null)
            return Long.MIN_VALUE;
        return packetHeader.getTime();
    }

    /**
     * Checks if the packet contains valid information (ie do the reported and calculated checksums
     * match)
     * @return
     */
    public boolean isValid() {
        byte reportedChecksum = 0;
        if (packetHeader != null)
            reportedChecksum = packetHeader.getChecksum();
        return ((reportedChecksum == 0) || ((byte)(PacketHeader.MASK_CHECKSUM & getChecksum()) == reportedChecksum));
    }

    public void incrementHopCount() {
        if (packetHeader != null)
            packetHeader.incrementHopCount();
    }

    public boolean isLossy() {
        if (packetHeader != null)
            return packetHeader.lossyOk;
        return true;
    }

    /**
     * Sets if this packet needs to be expedited
     * @param highPerformanceNeeded true == push through the higher bandwidth connection
     */
    public void setHighPerformanceNeeded(boolean highPerformanceNeeded) { this.highPerformanceNeeded = highPerformanceNeeded; }

    /**
     * Does this packet need to be expedited?
     * @return true == push through the higher bandwidth connection
     */
    public boolean isHighPerformanceNeeded() { return highPerformanceNeeded; }

    public int getCurrentHopCount() {
        if (packetHeader == null)
            return 0;
        return packetHeader.getHopCount();
    }

    /**
     * Provides the device (or PacketHeader.BROADCAST_ADDRESS if broadcasting to all) that
     * is the intended recipient of this packet
     * @return
     */
    public int getSqAnDestination() {
        if (packetHeader == null)
            return PacketHeader.BROADCAST_ADDRESS;
        return packetHeader.getDestination();
    }

    //creates a new packet from the byte array
    public static AbstractPacket newFromBytes(byte[] bytes) {
        try {
            if ((bytes == null) || (bytes.length < PacketHeader.getSize())) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to generate a packet from the byte array; byte array was not big enough to hold a header");
            }
            AbstractPacket packet = null;
            ByteBuffer reader = ByteBuffer.wrap(bytes);

            byte[] headerBytes = new byte[PacketHeader.getSize()];
            reader.get(headerBytes);
            PacketHeader header = PacketHeader.newFromBytes(headerBytes);

            if (header == null) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to generate a packet header from the byte array");
                return null;
            }

            packet = AbstractPacket.newFromHeader(header);
            if (packet == null) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to generate a packet from the packet header");
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
        } catch (Exception e) {
            return null;
        }
    }

    protected abstract byte getChecksum();

    public byte[] toByteArray() {
        if (packetHeader == null)
            return null;
        packetHeader.setChecksum(getChecksum());
        return packetHeader.toByteArray();
    }

    public static AbstractPacket newFromHeader(PacketHeader packetHeader) {
        if (packetHeader == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Cannot generate a Packet from an empty packet header");
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

            case PacketHeader.PACKET_TYPE_DISCONNECTING:
                packet = new DisconnectingPacket(packetHeader);
                break;

            case PacketHeader.PACKET_TYPE_VPN_BYTES:
                packet = new VpnPacket(packetHeader);
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
    protected abstract byte getType();

    /**
     * Did this packet come directly from the origin (i.e. no hops in between)
     * @return
     */
    public boolean isDirectFromOrigin() {
        if ((packetHeader == null) || (packetHeader.getOriginUUID() == PacketHeader.BROADCAST_ADDRESS))
            return false;
        return packetHeader.isDirectFromOrigin();
    }

    public int getOrigin() {
        if (packetHeader == null)
            return PacketHeader.BROADCAST_ADDRESS;
        return packetHeader.getOriginUUID();
    }

    public void setOrigin(int uuid) {
        if (packetHeader != null) {
            packetHeader.setOriginUUID(uuid);
            packetHeader.setHopCount(0);
        }
    }

    public void setDestination(int uuid) {
        if (packetHeader != null) {
            packetHeader.setDestination(uuid);
        }
    }
}