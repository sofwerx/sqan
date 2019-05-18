package org.sofwerx.sqan.manet.wifiaware;

public abstract class AbstractConnection {
    protected long lastPositiveComms = Long.MIN_VALUE;
    public abstract boolean isConnected();
    public long getLastComms() { return lastPositiveComms; };
    public void setLastPositiveComms(long time) { lastPositiveComms = time; }
    public void setPositiveComms() { setLastPositiveComms(System.currentTimeMillis()); }
    public abstract void close();
}
