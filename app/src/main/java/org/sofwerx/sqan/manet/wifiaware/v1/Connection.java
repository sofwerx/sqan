package org.sofwerx.sqan.manet.wifiaware.v1;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.aware.PeerHandle;

import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;

@Deprecated
public class Connection {
    private PeerHandle peerHandle;
    private SqAnDevice device;
    private ConnectivityManager.NetworkCallback callback;
    private Network network;
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

    public ConnectivityManager.NetworkCallback getCallback() {
        return callback;
    }

    public void setCallback(ConnectivityManager.NetworkCallback callback) {
        this.callback = callback;
    }

    public void setNetwork(Network network) { this.network = network; }
    public Network getNetwork() { return network; }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        out.append("PeerHandle: ");
        if (peerHandle == null)
            out.append("null");
        else
            out.append(Integer.toString(peerHandle.hashCode()));
        out.append("; device ");
        if (device == null)
            out.append("null");
        else
            out.append(device.getLabel());
        out.append("; callback ");
        if (callback == null)
            out.append("null");
        else
            out.append("present");
        out.append("; connected ");
        if (lastConnection <0l)
            out.append("never");
        else
            out.append(StringUtil.toDuration(System.currentTimeMillis()-lastConnection));
        return out.toString();
    }
}
