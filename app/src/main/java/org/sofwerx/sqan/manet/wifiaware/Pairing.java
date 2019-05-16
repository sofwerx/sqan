package org.sofwerx.sqan.manet.wifiaware;

import android.net.wifi.aware.PeerHandle;

import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.net.Inet6Address;
import java.util.ArrayList;

public class Pairing {
    private final static long WEAK_CONNECTION_STALE_AFTER = 1000l * 30l;
    public final static int CONNECTION_STRONG = 3; // full bandwidth support
    public final static int CONNECTION_WEAK = 2; // narrow bandwidth support only
    public final static int CONNECTION_STALE = 1; // a stale narrow bandwidth connection
    public final static int CONNECTION_NONE = 0; // no connection
    private SqAnDevice device;
    private PeerHandle peerHandle;
    private PeerHandleOrigin origin = PeerHandleOrigin.PUB;
    private AbstractConnection connection;
    private long lastWeakConnection = Long.MAX_VALUE;
    private static ArrayList<Pairing> pairings;
    private static Inet6Address ipv6 = null;

    public static enum PeerHandleOrigin {PUB,SUB,UNK};

    public boolean isPeerHandlePub() { return origin != PeerHandleOrigin.SUB; }
    public boolean isPeerHandleSub() { return origin != PeerHandleOrigin.PUB; }
    public void setPeerHandleOrigin(PeerHandleOrigin origin) { this.origin = origin; }

    public int isConnected() {
        boolean strongConnection;
        if (connection == null)
            strongConnection = false;
        else
            strongConnection = connection.isConnected();
        if (strongConnection)
            return CONNECTION_STRONG;
        if (System.currentTimeMillis() > lastWeakConnection + WEAK_CONNECTION_STALE_AFTER) {
            if (peerHandle == null)
                return CONNECTION_NONE;
            else
                return CONNECTION_STALE;
        }
        return CONNECTION_WEAK;
    }

    public String getLabel() {
        if (device != null)
            return device.getLabel();
        if (peerHandle != null)
            return Integer.toString(peerHandle.hashCode());
        return "unidentified";
    }

    public static Pairing find(PeerHandle peerHandle) {
        if ((peerHandle != null) && (pairings != null) && !pairings.isEmpty()) {
            final int hashCode = peerHandle.hashCode();
            synchronized (pairings) {
                for (Pairing pair : pairings) {
                    if ((pair != null) && (pair.peerHandle != null)) {
                        if (pair.peerHandle.hashCode() == hashCode) {
                            return pair;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Updates the pairing associated with this peer handle or creates a new pairing
     * @param peerHandle
     * @return the pairing updated or created
     */
    public static Pairing update(PeerHandle peerHandle) {
        Pairing pairing = null;
        if (peerHandle == null)
            return null;
        if (pairings == null)
            pairings = new ArrayList<>();
        else
            pairing = find(peerHandle);
        if (pairing == null) {
            pairing = new Pairing();
            pairing.setPeerHandle(peerHandle);
            pairings.add(pairing);
        }
        pairing.lastWeakConnection = System.currentTimeMillis();
        return pairing;
    }

    public SqAnDevice getDevice() { return device; }
    public void setDevice(SqAnDevice device) { this.device = device; }
    public PeerHandle getPeerHandle() { return peerHandle; }
    public void setPeerHandle(PeerHandle peerHandle) { this.peerHandle = peerHandle; }
    public AbstractConnection getConnection() { return connection; }
    public void setConnection(AbstractConnection connection) { this.connection = connection; }
    public static void setIpv6Address(Inet6Address address) { Pairing.ipv6 = address; }
    public static Inet6Address getIpv6Address() { return ipv6; }

    public static void clear() {
        pairings = null;
        ipv6 = null;
    }
}
