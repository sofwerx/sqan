package org.sofwerx.sqan.manet.common;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Describes this device's connection to other devices
 */
public class RelayConnection {
    public final static int SIZE = 4 + 4 + 8;
    private int sqAnID;
    private int hops;
    private long lastConnection;

    public RelayConnection() {}

    public RelayConnection(int sqAnID, int hops, long lastConnection) {
        this.sqAnID = sqAnID;
        this.hops = hops;
        this.lastConnection = lastConnection;
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

    public void setHops(int hops) {
        this.hops = hops;
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
        } catch (BufferUnderflowException e) {
            e.printStackTrace();
        }
    }

    public void update(RelayConnection other) {
        if (other.lastConnection > lastConnection) {
            hops = other.hops;
            lastConnection = other.lastConnection;
        }
    }
}
