package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.nio.ByteBuffer;

/**
 * Contains the header information needed for all packets
 */
public class PacketHeader {
    public final static int BROADCAST_ADDRESS = Integer.MIN_VALUE;
    protected final static int PACKET_TYPE_HEARTBEAT = 0;
    public final static int PACKET_TYPE_PING = 1;
    protected final static int PACKET_TYPE_RAW_BYTES = 2;
    protected final static int PACKET_TYPE_CHANNEL_BYTES = 3;
    public final static int PACKET_TYPE_DISCONNECTING = 4;
    private long time; //timestamps are used as a message index as well
    private int packetType; //TODO eventually combine packetType and hopCount into one int with some other flags
    private int hopCount = 0;
    private int originUUID;
    private int destination = BROADCAST_ADDRESS;

    private PacketHeader() {}

    public PacketHeader(int originUUID) {
        this();
        this.originUUID = originUUID;
    }

    /**
     * Gets the size of the header in bytes
     * @return
     */
    public final static int getSize() { return 4 + 4 + 4 + 4 + 8; }

    /**
     * Helper method to overwrite just the hop count and an existing byte[] version for a packet
     * in order to reduce the computation and time needed to handle the packet when its being relayed
     * @param data
     */
    public void incrementHopCount(byte[] data) {
        if ((data != null) && (data.length >= getSize())) {
            hopCount++;
            data[4] = (byte) (hopCount >> 24);
            data[5] = (byte) (hopCount >> 16);
            data[6] = (byte) (hopCount >> 8);
            data[7] = (byte) (hopCount);
        }
    }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public int getOriginUUID() { return originUUID; }
    public void setOriginUUID(int uuid) { this.originUUID = uuid; }
    public int getType() { return packetType; }
    public void setType(int packetType) { this.packetType = packetType; }
    public int getDestination() { return destination; }

    /**
     * Gets the number of hops this packet has taken
     * @return 0 == direct from origin
     */
    public int getHopCount() { return hopCount; }

    /**
     * Sets the number of hops this packet has taken
     * @param hopCount (0 == direct from origin)
     */
    public void setHopCount(int hopCount) { this.hopCount = hopCount; }

    /**
     * Did this packet come directly from the original source (i.e. no hops)
     * @return
     */
    public boolean isDirectFromOrigin() { return hopCount == 0; }

    /**
     * Changes this packet to reflect an additional hop
     */
    public void incrementHopCount() { hopCount++; }

    /**
     * Specifies a specific node to receive this traffic
     * @param destination
     */
    public void setDestination(int destination) { this.destination = destination; }

    /**
     * Specifies that this traffic is for all nodes
     */
    public void setDestinationToBroadcast() { this.destination = BROADCAST_ADDRESS; }

    /**
     * Specifies a specific node to receive this traffic
     */
    /*private void setDestination(Inet4Address address) {
        if (address != null) {
            destination = AddressUtil.getSqAnAddress(address);
        }
    }*/

    public byte[] toByteArray() {
        ByteBuffer out = ByteBuffer.allocate(getSize());
        out.putInt(packetType);
        out.putInt(hopCount);
        out.putInt(originUUID);
        out.putInt(destination);
        out.putLong(time);
        return out.array();
    }

    public static PacketHeader newFromBytes(byte[] bytes) {
        if (bytes == null) {
            Log.e(Config.TAG,"Cannot generate a packet header from a null byte array");
            return null;
        }
        if (bytes.length != getSize()) {
            Log.e(Config.TAG,"Cannot generate a packet header from a "+bytes.length+" byte array ("+getSize()+" bytes expected)");
            return null;
        }
        PacketHeader packetHeader = new PacketHeader();
        ByteBuffer in = ByteBuffer.wrap(bytes);
        packetHeader.packetType = in.getInt();
        packetHeader.hopCount = in.getInt();
        packetHeader.originUUID = in.getInt();
        packetHeader.destination = in.getInt();
        packetHeader.time = in.getLong();
        return packetHeader;
    }
}
