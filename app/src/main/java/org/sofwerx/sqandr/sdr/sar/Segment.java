package org.sofwerx.sqandr.sdr.sar;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.util.SdrUtils;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Packet structure:
 *  2 bytes header marker
 *  1 byte len
 *  1 byte Flags
 *      (includes Flag indicating segment (SEGMENTED_FLAG),
 *      the ID of the whole packet (OVERALL_ID) and
 *      the index of this segment within that packet (INDEX)
 *  1 byte checksum
 *  < MAX_LENGTH_BEFORE__SEGMENTING bytes the payload
 */
public class Segment {
    private final static String TAG = Config.TAG+".Seg";
    private final static byte[] FINAL_SEGMENT_MARKER = {(byte)0b01010101};
    public final static int MAX_LENGTH_BEFORE_SEGMENTING = 240; //Not to exceed 250 //TODO tune this number
    public final static byte[] HEADER_MARKER = {(byte)0b01100110,(byte)0b10011001};
    private final static byte SEGMENTED_FLAG = (byte)0b10000000;
    private final static byte OVERALL_ID_MASK = (byte)0b01110000;
    private final static byte INDEX_IN_SEGMENT_MASK = (byte)0b00001111;
    private final static int MAX_VALID_INDEX = 127;

    private final static int HEADER_SIZE = 5;

    private int index;
    private byte packetId;
    private byte[] data;

    /**
     * See if this is probably a valid packet. If this fails, then you should keep
     * scanning the bytestream for the next occurance of HEADER_MARKER
     * @param data
     * @return
     */
    public static boolean isQuickValidCheck(byte[] data) {
        if ((data == null) || (data.length < 3))
            return false;
        return (data[0] == HEADER_MARKER[0])
                && (data[1] == HEADER_MARKER[1])
                && (getSize(data) <= MAX_LENGTH_BEFORE_SEGMENTING);
    }

    public byte getPacketId() { return packetId; }
    public boolean isStandAlone() { return index > MAX_VALID_INDEX; }
    public int getIndex() { return index; }
    public byte[] getData() { return data; }

    public static int getSize(byte[] data) {
        if ((data == null) || (data.length < 3))
            return 0;
        return data[2] & 0xFF; //needed to make the signed byte back into an unsigned int
    }

    public boolean isEqual(Segment other) {
        if (other == null)
            return false;
        return (packetId == other.packetId) && (index == other.index);
    }

    public Segment(byte packetId, int index, byte[] data) {
        this.packetId = packetId;
        this.index = index;
        this.data = data;
    }

    public Segment() {
        this((byte)0,129,null);
    }

    public void setData(byte[] data) { this.data = data; }

    public void setIndex(int index) { this.index = index; }

    public byte[] toBytes() {
        if (data == null)
            return null;
        ByteBuffer out = ByteBuffer.allocate(HEADER_SIZE+data.length);
        out.put(HEADER_MARKER);
        out.put((byte)data.length);
        //byte len = (byte)data.length;
        //out.put(len);
        out.put(getFlags());
        out.put(SdrUtils.getChecksum(data));
        out.put(data);
        return out.array();
    }

    private byte getFlags() {
        int out;
        if (index > MAX_VALID_INDEX)
            out = 0;
        else {
            out = index;
            out = out | packetId;
            out = out | SEGMENTED_FLAG;
        }
        return (byte)out;
    }

    private void parseFlags(byte flags) {
        if ((flags & SEGMENTED_FLAG) == SEGMENTED_FLAG) {
            packetId = (byte)(flags & OVERALL_ID_MASK);
            index = (flags & INDEX_IN_SEGMENT_MASK) & 0xFF;
        } else
            index = MAX_VALID_INDEX+1;
    }

    /**
     * Parse the data after the HEADER_MARKER and size int
     * @param raw (should be the same length as the size of the data payload + 2) (1 byte for flags, 1 byte for checksum)
     */
    public void parseRemainder(byte[] raw) {
        if ((raw == null) || (raw.length < 3)) {
            data = null;
            Log.w(TAG,"Unable to parseRemainder, null or raw data too small");
        }
        parseRemainder(raw.length-2,ByteBuffer.wrap(raw));
    }

    private void parseRemainder(int size,ByteBuffer buf) {
        if ((buf == null) || (size < 1)) {
            data = null;
            Log.w(TAG,"Unable to parseRemainder, null or raw data too small");
            return;
        }
        try {
            parseFlags(buf.get());
            data = new byte[size];
            byte checksum = buf.get();
            buf.get(data);
            if (checksum != SdrUtils.getChecksum(data)) {
                Log.w(TAG, "Parsing failed - bad checksum");
                data = null;
                return;
            }
        } catch (BufferUnderflowException e) {
            Log.w(TAG,"Parsing failed - "+e.getMessage());
        }
    }

    public void parse(byte[] raw) {
        data = null;
        try {
            if ((raw == null) || (raw.length < HEADER_SIZE))
                return;
            ByteBuffer in = ByteBuffer.wrap(raw);
            if (in.get() != HEADER_MARKER[0]) {
                Log.w(TAG,"Parsing failed - bad header marker[0]");
                return;
            }
            if (in.get() != HEADER_MARKER[1]) {
                Log.w(TAG,"Parsing failed - bad header marker[1]");
                return;
            }
            int size = in.get();
            if (size > MAX_LENGTH_BEFORE_SEGMENTING) {
                Log.w(TAG,"Parsing failed - length is "+size+"b which is not possible");
                return;
            }
            parseRemainder(size,in);
        } catch (BufferUnderflowException e) {
            Log.w(TAG,"Parsing failed - "+e.getMessage());
        }
    }

    public boolean isValid() { return (data != null); }


    public boolean isFinalSegment() {
        if ((data == null) || (data.length != 1))
            return false;
        return FINAL_SEGMENT_MARKER[0] == data[0];
    }

    public static Segment newFinalSegment(byte packetId, int index) {
        Segment segment = new Segment();
        segment.packetId = packetId;
        segment.index = index;
        segment.data = FINAL_SEGMENT_MARKER;
        return segment;
    }

    public static boolean isAbleToWrapInSingleSegment(byte[] data) {
        if (data == null)
            return true;
        return data.length <= MAX_LENGTH_BEFORE_SEGMENTING;
    }
}
