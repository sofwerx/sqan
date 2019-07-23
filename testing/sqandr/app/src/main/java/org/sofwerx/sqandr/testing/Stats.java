package org.sofwerx.sqandr.testing;

public class Stats {
    public OneStats statsMe = new OneStats();
    public OneStats statsOther = new OneStats();
    public int segments = 0;
    public int partialPackets = 0;
    public int packetsSent = 0;
    public long bytesSent = 0l;

    public void clear() {
        statsMe.clear();
        statsOther.clear();
        segments = 0;
        partialPackets = 0;
        packetsSent = 0;
        bytesSent = 0l;
    }

    public void incrementBytesSent(int bytes) {
        bytesSent += (long)bytes;
    }

    public void incrementPacketsSent() {
        packetsSent++;
    }
}
