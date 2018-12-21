package org.sofwerx.sqan.manet.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.nio.ByteBuffer;

/**
 * Contains the header information needed for all packets
 */
public class PacketHeader {
    protected final static int PACKET_TYPE_HEARTBEAT = 0;
    protected final static int PACKET_TYPE_PING = 1;
    protected final static int PACKET_TYPE_RAW_BYTES = 2;
    private long time; //timestamps are used as a message index as well
    private int packetType;

    /**
     * Gets the size of the header in bytes
     * @return
     */
    public final static int getSize() { return 4 + 8; }

    public long getTime() { return time; }
    public void setTime(long time) { this.time = time; }
    public int getType() { return packetType; }
    public void setType(int packetType) { this.packetType = packetType; }

    public byte[] toByteArray() {
        ByteBuffer out = ByteBuffer.allocate(getSize());
        out.putInt(packetType);
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
        packetHeader.time = in.getLong();
        return packetHeader;
    }
}
