package org.sofwerx.sqan.manet.wifiaware;

import android.net.wifi.aware.PeerHandle;

import org.sofwerx.sqan.manet.common.SqAnDevice;

public class Connection {
    private PeerHandle peerHandle;
    private SqAnDevice device;
    private long lastConnection = Long.MIN_VALUE;

    public Connection(PeerHandle peerHandle, SqAnDevice device) {
        this.peerHandle = peerHandle;
        this.device = device;
        if ((peerHandle != null) && (device != null))
            device.setTransientAwareId(peerHandle.hashCode());
    }

    public PeerHandle getPeerHandle() { return peerHandle; }
    public void setPeerHandle(PeerHandle peerHandle) { this.peerHandle = peerHandle; }
    public SqAnDevice getDevice() { return device; }
    public void setDevice(SqAnDevice device) {
        if (this.device == device)
            return;
        if ((device == null) || (this.device == null)) {
            this.device = device;
            return;
        }
        device.consume(this.device);
        this.device = device;
    }
    public long getLastConnection() { return lastConnection; }
    public void setLastConnection(long lastConnection) { this.lastConnection = lastConnection; }
    public void setLastConnection() { setLastConnection(System.currentTimeMillis()); }
}
