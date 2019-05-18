package org.sofwerx.sqan.manet.wifiaware;

import android.net.wifi.aware.PeerHandle;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;
import java.net.Inet6Address;
import java.util.ArrayList;

public class Pairing {
    private final static String TAG = Config.TAG+".AwarePair";
    private final static long WEAK_CONNECTION_STALE_AFTER = 1000l * 30l;
    public final static int CONNECTION_STRONG = 3; // full bandwidth support
    public final static int CONNECTION_WEAK = 2; // narrow bandwidth support only
    public final static int CONNECTION_STALE = 1; // a stale narrow bandwidth connection
    public final static int CONNECTION_NONE = 0; // no connection
    private SqAnDevice device;
    private PeerHandle peerHandle;
    private PeerHandle alternatePeerHandle; //this is used to merge pairings for a PUB peerHandle and a SUB peerHandle but still link both handles to this pairing
    private PeerHandleOrigin origin = PeerHandleOrigin.PUB;
    private AbstractConnection connection;
    private long lastWeakConnection = Long.MAX_VALUE;
    private static ArrayList<Pairing> pairings;
    private static Inet6Address ipv6 = null;

    public static enum PeerHandleOrigin {PUB,SUB};

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

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        out.append("Aware Handle ");
        if (peerHandle == null)
            out.append("Unknown");
        else {
            out.append(Integer.toString(peerHandle.hashCode()));
            out.append('(');
            out.append(origin.name());
            out.append(')');
        }
        if (alternatePeerHandle != null) {
            out.append(" alt ");
            out.append(Integer.toString(alternatePeerHandle.hashCode()));
        }
        out.append("; ");
        if (device == null)
            out.append("Unknown device");
        else
            out.append(device.getLabel());
        out.append("; ");
        if (connection != null)
            out.append("Socket Connection "+connection.getClass().getName());
        else
            out.append("No Socket Connection");
        if (lastWeakConnection > 0l) {
            out.append("; last weak connection ");
            out.append(StringUtil.toDuration(System.currentTimeMillis() - lastWeakConnection));
        }


        return out.toString();
    }

    public static Pairing find(PeerHandle peerHandle) {
        if ((peerHandle != null) && (pairings != null) && !pairings.isEmpty()) {
            final int hashCode = peerHandle.hashCode();
            synchronized (pairings) {
                for (Pairing pair : pairings) {
                    if (pair != null) {
                        if ((pair.peerHandle != null) && (pair.peerHandle.hashCode() == hashCode))
                            return pair;
                        if ((pair.alternatePeerHandle != null) && (pair.alternatePeerHandle.hashCode() == hashCode))
                            return pair;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Merge pairings that refer to the same device
     */
    public static void dedup() {
        if ((pairings != null) && (pairings.size() > 1)) {
            synchronized (pairings) {
                int a=0;
                int b=1;
                while ((a < pairings.size()-1) && (b<pairings.size())) {
                    if ((a != b) && pairings.get(a).isSame(pairings.get(b))) {
                        Log.d(TAG,"Duplicate pairings found: "+pairings.get(a).toString()+" and "+pairings.get(b).toString());
                        pairings.get(a).update(pairings.get(b));
                        pairings.remove(b);
                    } else {
                        b++;
                        if (b >= pairings.size()) {
                            a++;
                            b = a + 1;
                        }
                    }
                }
            }
        }
    }

    public boolean isSame(Pairing other) {
        if (other == null)
            return false;
        if ((device != null) && (other.device != null))
            return device.isSame(other.device);
        return isMatchingPeerHandle((other));
    }

    private boolean isMatchingPeerHandle(Pairing other) {
        if (other == null)
            return false;
        boolean match = false;
        if ((peerHandle != null) && (other.peerHandle != null)) {
            match = peerHandle.hashCode() == other.peerHandle.hashCode();
        }
        if (!match) {
            if (peerHandle != null) {
                if (other.alternatePeerHandle != null)
                    match = match || (peerHandle.hashCode() == other.alternatePeerHandle.hashCode());
            }
            if (alternatePeerHandle != null) {
                if (other.peerHandle != null)
                    match = match || (alternatePeerHandle.hashCode() == other.peerHandle.hashCode());
                if (other.alternatePeerHandle != null)
                    match = match || (alternatePeerHandle.hashCode() == other.alternatePeerHandle.hashCode());
            }
        }
        return match;
    }

    /**
     * Updates this pairing with the relevant values from another pairing. Both pairings should be
     * to the same device.
     * @param other
     */
    public void update(Pairing other) {
        if (other == null)
            return;
        if (device == null)
            device = other.device;
        if (peerHandle == null) {
            peerHandle = other.peerHandle;
            if (other.origin == PeerHandleOrigin.PUB) //favor PUB over SUB
                origin = PeerHandleOrigin.PUB;
        } else
            alternatePeerHandle = other.peerHandle;
        if (connection == null)
            connection = other.connection;
        else {
            if (other.connection != null) {
                if (connection.getLastComms() > other.connection.getLastComms()) {
                    Log.d(TAG,"both Pairings in update had a socket connection so closing "+other.connection.getClass().getName()+" and keeping open "+connection.getClass().getName());
                    other.connection.close();
                } else {
                    Log.d(TAG,"both Pairings in update had a socket connection so closing "+connection.getClass().getName()+" and using "+other.connection.getClass().getName()+" instead");
                    connection.close();
                    connection = other.connection;
                }
            }
        }
        if (other.lastWeakConnection > lastWeakConnection)
            lastWeakConnection = other.lastWeakConnection;
    }

    /**
     * Updates the pairing associated with this peer handle or creates a new pairing
     * @param peerHandle
     * @return the pairing updated or created
     */
    public static Pairing update(PeerHandle peerHandle) {
        Pairing pairing = null;
        if (peerHandle == null) {
            Log.d(TAG,"Cannot update pairing with a null peer handle");
            return null;
        }
        if (pairings == null)
            pairings = new ArrayList<>();
        else
            pairing = find(peerHandle);
        if (pairing == null) {
            pairing = new Pairing();
            pairing.setPeerHandle(peerHandle);
            pairings.add(pairing);
            Log.d(TAG,"Added pairing "+pairing.toString());
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
    public void setLastWeakConnection(long time) { lastWeakConnection = time; }
    public void setLastWeakConnection() { setLastWeakConnection(System.currentTimeMillis()); }
    public long getLastWeakConnection() { return lastWeakConnection; }
    public static void setIpv6Address(Inet6Address address) { Pairing.ipv6 = address; }
    public static Inet6Address getIpv6Address() { return ipv6; }
    public static ArrayList<Pairing> getPairings() { return pairings; }

    public static void clear() {
        pairings = null;
        ipv6 = null;
    }
}
