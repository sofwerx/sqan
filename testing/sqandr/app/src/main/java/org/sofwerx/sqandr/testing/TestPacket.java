package org.sofwerx.sqandr.testing;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.NetUtil;

import java.nio.ByteBuffer;

public class TestPacket {
    private final static String TAG = Config.TAG+".pkt";
    private final static byte[] PATTERN = {(byte)0b00000001,(byte)0b00000010,(byte)0b00000100,(byte)0b00001000,(byte)0b00010000,(byte)0b00100000,(byte)0b01000000,(byte)0b10000000,
                                            (byte)0b10000001,(byte)0b10000010,(byte)0b10000100,(byte)0b10001000,(byte)0b10010000,(byte)0b10100000,(byte)0b11000000,(byte)0b00000000};
    private long device;
    private int index;
    private byte[] data;
    private static byte[] STD_DATA;
    private static byte STD_DATA_CHECKSUM;

    private final static int HEADER_SIZE = 16;

    private static int size = 0;

    public TestPacket(long device, int index) {
        //Log.d(TAG,"Packet "+index+" created for device "+device);
        this.device = device;
        this.index = index;
    }

    public TestPacket(byte[] raw) {
        parse(raw);
    }

    public static void setPacketSize(int totalSize) {
        size = totalSize - HEADER_SIZE;
        STD_DATA = new byte[size];
        int index = 0;
        for (int i=0;i<size;i++) {
            if (index == PATTERN.length)
                index = 0;
            STD_DATA[i] = PATTERN[index];
            index++;
        }
        STD_DATA_CHECKSUM = NetUtil.getChecksum(STD_DATA);
    }

    public int getIndex() {
        return index;
    }

    public long getDevice() {
        return device;
    }

    public byte[] toBytes() {
        ByteBuffer out = ByteBuffer.allocate(size+HEADER_SIZE);
        //write header
        out.putLong(device);
        out.putInt(index);
        out.put(updateChecksum());
        out.put(STD_DATA);

        return out.array();
    }

    private byte updateChecksum() {
        byte check = STD_DATA_CHECKSUM;
        byte[] bytes = NetUtil.longToByteArray(device);
        for (int i=0;i<bytes.length;i++) {
            check = NetUtil.updateChecksum(check,bytes[i]);
        }
        bytes = NetUtil.intToByteArray(index);
        for (int i=0;i<bytes.length;i++) {
            check = NetUtil.updateChecksum(check,bytes[i]);
        }
        return check;
    }

    public void parse(byte[] raw) {
        if (raw == null) {
            device = Long.MIN_VALUE;
            index = Integer.MIN_VALUE;
            STD_DATA = null;
        }
        ByteBuffer buf = ByteBuffer.wrap(raw);
        try {
            device = buf.getLong();
            index = buf.getInt();
            byte checksum = buf.get();
            Log.d(TAG,"Parser checking "+index+" from "+device+", checksum "+checksum);
            data = new byte[size];
            buf.get(data);
            for (int i=0;i<data.length;i++) {
                if (data[i] != STD_DATA[i]) {
                    data = null;
                    Log.d(TAG,"Packet "+index+" from "+device+" parsed failed: data not the same");
                    return;
                }
            }
            if (updateChecksum() != checksum) {
                Log.d(TAG,"Packet "+index+" from "+device+"checksum failed");
                data = null;
                return;
            }
        } catch (Exception ignore) {
        }
        Log.d(TAG,"Packet "+index+" from "+device+" parsed successfully");
    }

    public boolean isDeviceKnown() {
        return device > 0l;
    }

    public boolean isIndexKnown() {
        return index > 0;
    }

    public boolean isValid() {
        return (isDeviceKnown() && isIndexKnown() && (data != null));
    }
}
