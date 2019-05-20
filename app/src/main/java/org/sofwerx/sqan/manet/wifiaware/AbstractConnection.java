package org.sofwerx.sqan.manet.wifiaware;

import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

public abstract class AbstractConnection {
    protected final static long TIME_TO_STALE = 1000l * 60l;
    protected long lastPositiveComms = Long.MIN_VALUE;
    public abstract boolean isConnected();
    public long getLastComms() { return lastPositiveComms; };
    public void setLastPositiveComms(long time) { lastPositiveComms = time; }
    public void setPositiveComms() { setLastPositiveComms(System.currentTimeMillis()); }
    public abstract void close();
    public boolean isStale() {
        if (lastPositiveComms > 0l)
            return System.currentTimeMillis() > lastPositiveComms + TIME_TO_STALE;
        return false;
    }

    /**
     * Queues a packet for sending
     * @param packet
     * @return true == was successfully queued (false == cannot send the packet at this time)
     */
    public abstract boolean burst(AbstractPacket packet);
}
