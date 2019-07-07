package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.nio.ByteBuffer;

/**
 * Contains the header information needed for all packets
 */
public class PacketHeader {
    public final static int BROADCAST_ADDRESS = Integer.MIN_VALUE;
    protected final static byte PACKET_TYPE_HEARTBEAT =     0b00000000;
    public    final static byte PACKET_TYPE_PING =          0b00000001;
    protected final static byte PACKET_TYPE_RAW_BYTES =     0b00000010;
    protected final static byte PACKET_TYPE_CHANNEL_BYTES = 0b00000011;
    protected final static byte PACKET_TYPE_VPN_BYTES =     0b00000100;
    public final static byte PACKET_TYPE_DISCONNECTING =    0b00000101;
    private final static byte FLAG_LOSSY_OK =         (byte)0b00001000;
    final static byte MASK_CHECKSUM =                 (byte)0b11110000;
    private final static byte MASK_TYPE =             (byte)0b00000111;
    private long time; //timestamps are used as a message index as well //FIXME switch this to an int and then also add a checksum byte
    private byte packetType;
    private byte hopCount = 0;
    private int originUUID;
    private int destination = BROADCAST_ADDRESS;
    protected boolean lossyOk = true;
    private byte checksum = 0;

    private PacketHeader() {}

    public PacketHeader(int originUUID) {
        this();
        this.originUUID = originUUID;
        time = System.currentTimeMillis();
    }

    void setChecksum(byte checksum) { this.checksum = checksum; }
    public byte getChecksum() { return checksum; }
    public static byte calcChecksum(byte[] data) {
        byte calcCheck = 0;
        if (data != null) {
            for (int i = 0; i < data.length; i++) {
                calcCheck = (byte) (calcCheck ^ data[i]);
                calcCheck = (byte) (calcCheck ^ (data[i] << 4));
            }
        }
        return calcCheck;
    }

    /**
     * Sets if this packet ok to drop when the network gets congested (i.e. is it ok for
     * this packet to be lossy)
     * @param lossyOk
     */
    public void setIsLossyOk(boolean lossyOk) { this.lossyOk = lossyOk; }

    /**
     * Is this packet ok to drop when the network gets congested (i.e. is it ok for
     * this packet to be lossy)
     * @return
     */
    public boolean isLossyOk() { return lossyOk; }

    /**
     * Gets the size of the header in bytes
     * @return
     */
    public final static int getSize() { return 1 + 1 + 4 + 4 + 8; }

    /*private final static int MASK_LOSSY_OK = 0b0000000000000001;
    private int getFlags() {
        int result = 0;
        if (lossyOk)
            result = result | MASK_LOSSY_OK;
        return result;
    }

    private void parseFlags(int flags) {
        lossyOk = (flags & MASK_LOSSY_OK) == MASK_LOSSY_OK;
    }*/

    /**
     * Helper method to overwrite just the hop count and an existing byte[] version for a packet
     * in order to reduce the computation and time needed to handle the packet when its being relayed
     * @param data
     */
    public static void setHopCount(int newHopCount,byte[] data) {
        if ((data != null) && (data.length >= getSize())) {
            data[1] = (byte)newHopCount;

            /*data[4] = (byte) (newHopCount >> 24);
            data[5] = (byte) (newHopCount >> 16);
            data[6] = (byte) (newHopCount >> 8);
            data[7] = (byte) (newHopCount);*/
        }
    }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public int getOriginUUID() { return originUUID; }
    public void setOriginUUID(int uuid) { this.originUUID = uuid; }
    public byte getType() { return packetType; }
    public void setType(byte packetType) { this.packetType = packetType; }
    public int getDestination() { return destination; }

    /**
     * Gets the number of hops this packet has taken
     * @return 0 == direct from origin
     */
    public int getHopCount() { return (int)hopCount; }

    /**
     * Sets the number of hops this packet has taken
     * @param hopCount (0 == direct from origin)
     */
    public void setHopCount(int hopCount) { this.hopCount = (byte)hopCount; }

    /**
     * Did this packet come directly from the original source (i.e. no hops)
     * @return
     */
    public boolean isDirectFromOrigin() { return hopCount == (byte)0; }

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
        byte flags = (byte)(packetType | (checksum & MASK_CHECKSUM));
        if (lossyOk)
            flags = (byte)(flags | FLAG_LOSSY_OK);
        out.put(flags);
        out.put(hopCount);
        //out.putInt(getFlags());
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
        byte typeAndChecksum = in.get();
        packetHeader.packetType = (byte)(typeAndChecksum & MASK_TYPE);
        packetHeader.checksum = (byte)(typeAndChecksum & MASK_CHECKSUM);
        packetHeader.lossyOk = (typeAndChecksum & FLAG_LOSSY_OK) == FLAG_LOSSY_OK;
        packetHeader.hopCount = in.get();
        //packetHeader.parseFlags(in.getInt());
        packetHeader.originUUID = in.getInt();
        packetHeader.destination = in.getInt();
        packetHeader.time = in.getLong();
        return packetHeader;
    }
}
