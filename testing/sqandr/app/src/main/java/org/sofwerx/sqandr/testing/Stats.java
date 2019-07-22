package org.sofwerx.sqandr.testing;

public class Stats {
    public OneStats statsMe = new OneStats();
    public OneStats statsOther = new OneStats();
    public int segments = 0;
    public int partialPackets = 0;

    public void clear() {
        statsMe = new OneStats();
        statsOther = new OneStats();
        segments = 0;
        partialPackets = 0;
    }
}
