package org.sofwerx.sqan.ipc;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class BftBroadcast {
    public final static String BFT_CHANNEL = "SA";
    private ArrayList<BftDevice> devices = new ArrayList<>();

    public void add(BftDevice device) {
        devices.add(device);
    }

    public ArrayList<BftDevice> getDevices() {
        return devices;
    }

    public byte[] toBytes() {
        if (devices.isEmpty())
            return null;
        ArrayList<byte[]> deviceBytes = new ArrayList<>();
        int size = 4;
        for (BftDevice device:devices) {
            byte[] raw = device.toBytes();
            if (raw != null) {
                size += 4 + raw.length;
                deviceBytes.add(raw);
            }
        }
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(deviceBytes.size());
        for (byte[] raw:deviceBytes) {
            if (raw == null)
                out.putInt(0);
            else {
                out.putInt(raw.length);
                out.put(raw);
            }
        }
        return out.array();
    }

    public void parse(byte[] bytes) {
        devices = new ArrayList<>();
        if (bytes == null)
            return;
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            int num = in.getInt();
            int size;
            byte[] raw;
            for (int i=0;i<num;i++) {
                BftDevice device = new BftDevice();
                size = in.getInt();
                if (size > 0) {
                    raw = new byte[size];
                    in.get(raw);
                    device.parse(raw);
                    add(device);
                }
            }
        } catch (BufferUnderflowException e) {
            e.printStackTrace();
        }
    }

    public int getNumberOfDevices() {
        if (devices == null)
            return 0;
        return devices.size();
    }
}
