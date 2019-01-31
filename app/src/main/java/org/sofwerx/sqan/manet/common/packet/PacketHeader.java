package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.sockets.AddressUtil;

import java.net.Inet4Address;
import java.nio.ByteBuffer;

/**
 * Contains the header information needed for all packets
 */
public class PacketHeader {
    public final static int BROADCAST_ADDRESS = Integer.MIN_VALUE;
    protected final static int PACKET_TYPE_HEARTBEAT = 0;
    protected final static int PACKET_TYPE_PING = 1;
    protected final static int PACKET_TYPE_RAW_BYTES = 2;
    protected final static int PACKET_TYPE_CHANNEL_BYTES = 3;
    public final static int PACKET_TYPE_DISCONNECTING = 4;
    private long time; //timestamps are used as a message index as well
    private int packetType;
    private int originUUID;
    private int destination = BROADCAST_ADDRESS;

    public PacketHeader(int originUUID) {
        this();
        this.originUUID = originUUID;
    }

    private PacketHeader() {
    }

    /**
     * Gets the size of the header in bytes
     * @return
     */
    public final static int getSize() { return 4 + 4 + 4 + 8; }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public int getOriginUUID() { return originUUID; }
    public void setOriginUUID(int uuid) { this.originUUID = uuid; }
    public int getType() { return packetType; }
    public void setType(int packetType) { this.packetType = packetType; }
    public int getDestination() { return destination; }

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
     * @param address IPV4 address of the intended destination
     */
    private void setDestination(Inet4Address address) {
        if (address != null) {
            destination = AddressUtil.getSqAnAddress(address);
        }
    }

    public byte[] toByteArray() {
        ByteBuffer out = ByteBuffer.allocate(getSize());
        out.putInt(packetType);
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
        packetHeader.originUUID = in.getInt();
        packetHeader.destination = in.getInt();
        packetHeader.time = in.getLong();
        return packetHeader;
    }
}
