package org.sofwerx.sqan.manet.common;

import org.sofwerx.sqan.util.StringUtil;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Describes this device's connection to other devices
 */
public class RelayConnection {
    public final static int SIZE = 4 + 4 + 8 + 1;
    private int sqAnID;
    private int hops;
    private long lastConnection;
    private boolean directWiFi = false;
    private boolean directBt = false;
    private final static byte FLAG_BT = 0b00000001;
    private final static byte FLAG_WIFI = 0b00000010;

    public RelayConnection() {}

    public RelayConnection(int sqAnID, int hops, long lastConnection, boolean directBt, boolean directWiFi) {
        this.sqAnID = sqAnID;
        this.lastConnection = lastConnection;
        setHops(hops,directBt,directWiFi);
    }

    public RelayConnection(byte[] raw) {
        parse(raw);
    }

    public int getSqAnID() {
        return sqAnID;
    }

    public void setSqAnID(int sqAnID) {
        this.sqAnID = sqAnID;
    }

    public int getHops() {
        return hops;
    }

    public void setHops(int hops, boolean directBt, boolean directWiFi) {
        this.hops = hops;
        if (hops == 0) {
            this.directBt = directBt;
            this.directWiFi = directWiFi;
        }
    }

    public long getLastConnection() {
        return lastConnection;
    }

    public void setLastConnection(long lastConnection) {
        this.lastConnection = lastConnection;
    }

    public byte[] toBytes() {
        ByteBuffer out = ByteBuffer.allocate(SIZE);
        out.putInt(sqAnID);
        out.putInt(hops);
        out.putLong(lastConnection);
        byte flags = 0b00000000;
        if (directBt)
            flags = (byte)(flags | FLAG_BT);
        if (directWiFi)
            flags = (byte)(flags | FLAG_WIFI);
        out.put(flags);
        return out.array();
    }

    public void parse(byte[] bytes) {
        if (bytes == null)
            return;
        ByteBuffer in = ByteBuffer.wrap(bytes);
        try {
            sqAnID = in.getInt();
            hops = in.getInt();
            lastConnection = in.getLong();
            byte flags = in.get();
            directBt = (flags & FLAG_BT) == FLAG_BT;
            directWiFi = (flags & FLAG_WIFI) == FLAG_WIFI;
        } catch (BufferUnderflowException e) {
            e.printStackTrace();
        }
    }

    public void update(final RelayConnection other) {
        if (other.lastConnection > lastConnection) {
            hops = other.hops;
            lastConnection = other.lastConnection;
            directBt = other.directBt;
            directWiFi = other.directWiFi;
        }
    }

    /**
     * Is this device directly connected via WiFi
     * @return
     */
    public boolean isDirectWiFi() { return directWiFi; }

    /**
     * Sets if this device directly connected via WiFi
     * @param directWiFi
     */
    public void setDirectWiFi(boolean directWiFi) { this.directWiFi = directWiFi; }

    /**
     * Is this device directly connected via Bluetooth
     * @return
     */
    public boolean isDirectBt() { return directBt; }

    /**
     * Sets if this device directly connected via Bluetooth
     * @param directBt
     */
    public void setDirectBt(boolean directBt) { this.directBt = directBt; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("SqAN ID "+sqAnID);
        if (hops == 0) {
            if (directBt)
                sb.append(", direct BT");
            if (directWiFi)
                sb.append(", direct WiFi");
        } else
            sb.append(", "+hops+((hops==1)?"":"s")+" away");
        if (lastConnection > 0l) {
            sb.append(", last ");
            sb.append(StringUtil.toDuration(System.currentTimeMillis() - lastConnection));
        }

        return sb.toString();
    }
}
