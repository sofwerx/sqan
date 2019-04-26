package org.sofwerx.sqantest.tests.support;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

public class TestPacket {
    private int origin;
    private long timeSent;
    private long rcvTimeReceived;
    private byte[] data;

    public TestPacket() {}

    public TestPacket(int origin, byte[] data) {
        super();
        this.origin = origin;
        this.data = data;
        timeSent = System.currentTimeMillis();
        rcvTimeReceived = Long.MIN_VALUE;
    }

    public static TestPacket newTestPacket(byte[] bytes) throws PacketException {
        TestPacket packet = new TestPacket();
        packet.parse(bytes);
        return packet;
    }

    private final static int HEADER_BYTE_SIZE = 4 + 8 + 4;

    public int getOrigin() {
        return origin;
    }

    public void setOrigin(int origin) {
        this.origin = origin;
    }

    /**
     * Set by the sending device based on the sending device's clock
     * @return
     */
    public long getTimeSent() {
        return timeSent;
    }

    /**
     * Set by the sending device based on the sending device's clock
     * @param timeSent
     */
    public void setTimeSent(long timeSent) {
        this.timeSent = timeSent;
    }

    /**
     * Set by the receiving device when the packet is received
     * @return
     */
    public long getRcvTimeReceived() {
        return rcvTimeReceived;
    }

    /**
     * Set by the receiving device when the packet is received
     * @param rcvTimeReceived
     */
    public void setRcvTimeReceived(long rcvTimeReceived) {
        this.rcvTimeReceived = rcvTimeReceived;
    }
    public void setRcvTimeReceived() { setRcvTimeReceived(System.currentTimeMillis());}

    public byte[] getData() {
        return data;
    }

    public void setData(byte[] data) {
        this.data = data;
    }

    public void parse(byte[] bytes) throws PacketException {
        if ((bytes == null) || (bytes.length < HEADER_BYTE_SIZE))
            throw new PacketException("Incomplete packet data");
        ByteBuffer in  = ByteBuffer.wrap(bytes);
        origin = in.getInt();
        timeSent = in.getLong();
        int len = in.getInt();
        try {
            if (len < 0)
                throw new PacketException("Illegal data length: "+len);
            else if (len == 0)
                data = null;
            else {
                data = new byte[len];
                in.get(data);
            }
        } catch (BufferUnderflowException e) {
            throw new PacketException("Unable to parse, buffer underflow");
        }
    }

    public byte[] toByteArray() {
        ByteBuffer out;
        if (data == null)
            out = ByteBuffer.allocate(HEADER_BYTE_SIZE);
        else
            out = ByteBuffer.allocate(HEADER_BYTE_SIZE+data.length);
        out.putInt(origin);
        out.putLong(timeSent);
        if (data == null)
            out.putInt(0);
        else {
            out.putInt(data.length);
            out.put(data);
        }
        return out.array();
    }
}
