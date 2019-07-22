package org.sofwerx.sqan.manet.wifiaware;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.NetworkSpecifier;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Build;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.wifiaware.client.ClientConnection;
import org.sofwerx.sqan.manet.wifiaware.server.ServerConnection;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.util.StringUtil;

import java.io.IOException;
import java.io.StringWriter;
import java.net.Inet6Address;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;

public class Pairing {
    private final static String TAG = Config.TAG+".AwarePair";
    private final static long WEAK_CONNECTION_STALE_AFTER = 1000l * 30l;
    public final static int CONNECTION_STRONG = 4; // full bandwidth support
    public final static int CONNECTION_STRONG_PREPARING = 3; // full bandwidth connection is in the process of being built
    public final static int CONNECTION_WEAK = 2; // narrow bandwidth support only
    public final static int CONNECTION_STALE = 1; // a stale narrow bandwidth connection
    public final static int CONNECTION_NONE = 0; // no connection
    private SqAnDevice device;
    private PeerHandle peerHandle;
    private PeerHandle alternatePeerHandle; //this is used to merge pairings for a PUB peerHandle and a SUB peerHandle but still link both handles to this pairing
    private PeerHandleOrigin origin = PeerHandleOrigin.PUB;
    private AbstractConnection connection;
    private Network network;
    private NetworkInterface networkInterface;
    private AwareManetV2ConnectionCallback networkCallback;
    private long lastWeakConnection = Long.MAX_VALUE;
    private static ArrayList<Pairing> pairings;
    private static Inet6Address ipv6 = null;
    private static Context context;
    private static AbstractManet manet;
    private static ConnectivityManager connectivityManager;

    public static void init(@NonNull Context context, AbstractManet manet) {
        Pairing.context = context;
        Pairing.manet = manet;
        connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public boolean burst(AbstractPacket packet) {
        if ((packet == null) || (connection == null))
            return false;
        return connection.burst(packet);
    }

    public void requestNetwork(WifiAwareSession awareSession, DiscoverySession discoverySession, boolean server) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (device == null) {
                Log.d(TAG,"Pairing cannot requestNetwork with a null device");
                return;
            }
            String passcode = NetUtil.conformPasscodeToWiFiAwareRequirements(Config.getPasscode()); //TODO
            NetworkSpecifier networkSpecifier = null;
            if ((peerHandle != null) && (discoverySession != null)) {
                Log.d(TAG, "Building an aware network specifier based on PeerHandle");
                networkSpecifier = discoverySession.createNetworkSpecifierOpen(peerHandle);
            } else if ((awareSession != null) && (device.getAwareMac() != null) && device.getAwareMac().isValid()) {
                Log.d(TAG, "Building an aware network specifier based on OOB details as " + (server ? "Initiator" : "Responder"));
                networkSpecifier = awareSession.createNetworkSpecifierOpen(server ? WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_INITIATOR : WifiAwareManager.WIFI_AWARE_DATA_PATH_ROLE_RESPONDER, device.getAwareMac().toByteArray());
            }
            if (networkSpecifier == null) {
                Log.d(TAG, "Unable to build a network specifier so not establishing an Aware network connection at this time");
                return;
            }
            NetworkRequest networkRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
                    .setNetworkSpecifier(networkSpecifier)
                    .build();

            Log.d(TAG, "Requesting a network connection for "+getLabel()+"...");
            networkCallback = new AwareManetV2ConnectionCallback(context, manet, this);
            connectivityManager.requestNetwork(networkRequest, networkCallback, manet.getHandler().toAndroid());
        }
    }

    public AwareManetV2ConnectionCallback getNetworkCallback() { return networkCallback; }

    public void checkNetwork() {
        handleNetworkChange(network);
    }

    private void startServer() {
        if ((connection == null) && (network != null) && (networkInterface != null) && (manet != null)) {
            Log.d(TAG,getLabel()+" creating new Server Connection...");
            connection = new ServerConnection(manet, this);
        }
    }

    public void handleNetworkChange(final Network network) {
        Log.d(TAG,getLabel()+": handleNetworkChange()");
        if ((network == null) && (this.network != null)) {
            Log.d(TAG,getLabel()+" lost network");
            //TODO
        }
        this.network = network;
        if (network == null)
            return;
        Inet6Address serverAddress = device.getAwareServerIp();
        if (shouldBeServer()) {
            startServer();
            return;
        }
        if ((connection == null) && (serverAddress != null) && (networkInterface != null)) {
            try {
                serverAddress = (Inet6Address)Inet6Address.getByAddress(null,serverAddress.getAddress(),networkInterface);
                Socket cs = network.getSocketFactory().createSocket(serverAddress, AbstractManet.SQAN_PORT);
                connection = new ClientConnection(manet,cs);
            } catch (IOException e) {
                Log.e(TAG,"Unable to start client: "+e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public static SqAnDevice.NodeRole getRole() {
        if ((pairings == null) || pairings.isEmpty())
            return SqAnDevice.NodeRole.OFF;
        boolean isServer = false;
        boolean isClient = false;

        synchronized (pairings) {
            AbstractConnection conn;
            for (Pairing pairing:pairings) {
                if (pairing != null) {
                    conn = pairing.connection;
                    if (conn != null) {
                        if (conn instanceof ServerConnection)
                            isServer = true;
                        else
                            isClient = true;
                    }
                }
            }
        }

        if (isServer) {
            if (isClient)
                return SqAnDevice.NodeRole.BOTH;
            return SqAnDevice.NodeRole.HUB;
        } else if (isClient)
            return SqAnDevice.NodeRole.SPOKE;
        return SqAnDevice.NodeRole.OFF;
    }

    public void setNetworkInterface(NetworkInterface networkInterface) { this.networkInterface = networkInterface; }
    public NetworkInterface getNetworkInterface() { return networkInterface; }

    public enum PeerHandleOrigin {PUB,SUB};
    public enum PairingStatus {INFO_NEEDED, SHOULD_BE_IGNORED,SHOULD_BE_SERVER, NEEDS_NETWORK, SHOULD_BE_CLIENT, CONNECTING,CONNECTED}

    public boolean isPeerHandlePub() { return origin != PeerHandleOrigin.SUB; }
    public boolean isPeerHandleSub() { return origin != PeerHandleOrigin.PUB; }
    public void setPeerHandleOrigin(PeerHandleOrigin origin) { this.origin = origin; }

    public boolean shouldBeServer() {
        SqAnDevice thisDevice = Config.getThisDevice();
        if ((thisDevice != null) && thisDevice.isUuidKnown() && (device != null) && device.isUuidKnown())
            return thisDevice.getUUID() < device.getUUID(); //lower UUIDs should be server
        return false;
    }

    public PairingStatus getStatus() {
        if (connection == null) {
            SqAnDevice thisDevice = Config.getThisDevice();
            if ((device != null) && (thisDevice != null) && device.isUuidKnown() && thisDevice.isUuidKnown()) {
                device.setDirectWiFiHiPerf(false);
                if (shouldBeServer()) {
                    if ((peerHandle == null) || (origin == PeerHandleOrigin.PUB)) {
                        if (network == null)
                            return PairingStatus.NEEDS_NETWORK;
                        return PairingStatus.SHOULD_BE_SERVER;
                    } else
                        return PairingStatus.SHOULD_BE_IGNORED;
                } else {
                    if ((peerHandle == null) || (origin == PeerHandleOrigin.SUB)) {
                        if (network == null)
                            return PairingStatus.NEEDS_NETWORK;
                        else
                            return PairingStatus.SHOULD_BE_CLIENT;
                    } else
                        return PairingStatus.SHOULD_BE_IGNORED;
                }
            } else
                return PairingStatus.INFO_NEEDED;
        } else {
            if (network == null)
                return PairingStatus.NEEDS_NETWORK;
            if (connection.isConnected()) {
                device.setDirectWiFiHiPerf(true);
                return PairingStatus.CONNECTED;
            } else {
                device.setDirectWiFiHiPerf(false);
                return PairingStatus.CONNECTING;
            }
        }
    }

    public int isConnectionActive() {
        if (connection != null) {
            if (connection.isConnected())
                return CONNECTION_STRONG;
            else
                return CONNECTION_STRONG_PREPARING;
        }
        if (lastWeakConnection > 0l) {
            if (System.currentTimeMillis() > lastWeakConnection + WEAK_CONNECTION_STALE_AFTER) {
                if (peerHandle == null)
                    return CONNECTION_NONE;
                else
                    return CONNECTION_STALE;
            } else
                return CONNECTION_WEAK;
        } else
            return CONNECTION_NONE;
    }

    public String getLabel() {
        String pubLabel;
        String serverLabel;
        if (peerHandle == null)
            pubLabel = "";
        else {
            if (origin == PeerHandleOrigin.PUB)
                pubLabel = " [pub]";
            else
                pubLabel = " [sub]";
        }
        if (connection == null) {
            if (shouldBeServer())
                serverLabel = " (should be server)";
            else
                serverLabel = "";
        } else {
            if (connection instanceof ServerConnection)
                serverLabel = " (server)";
            else
                serverLabel = " (client)";
        }
        if (device != null)
            return device.getLabel()+pubLabel+serverLabel;
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
        out.append(", ");
        out.append(getStatus().name());

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
                        boolean overwriteA;
                        if (pairings.get(a).shouldBeServer() && pairings.get(b).shouldBeServer())
                            overwriteA = (pairings.get(a).origin == PeerHandleOrigin.PUB);
                        else
                            overwriteA = (pairings.get(a).origin == PeerHandleOrigin.SUB);
                        if (overwriteA) {
                            pairings.get(a).update(pairings.get(b));
                            Log.d(TAG,"removing "+pairings.get(b).toString());
                            pairings.remove(b);
                        } else {
                            pairings.get(b).update(pairings.get(a));
                            Log.d(TAG,"removing "+pairings.get(a).toString());
                            pairings.remove(a);
                        }
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
        Log.d(TAG,"Updating "+getLabel()+" with values from "+other.getLabel());
        if (device == null)
            device = other.device;
        if (peerHandle == null) {
            peerHandle = other.peerHandle;
            origin = other.origin;
            alternatePeerHandle = null;
        } else if (other.peerHandle != null) {
            alternatePeerHandle = other.peerHandle;
        }
        if (other.networkCallback != null) {
            if (networkCallback != null)
                removeNetworkCallback();
            networkCallback = other.networkCallback;
        }
        if (connection == null)
            connection = other.connection;
        else {
            if (other.connection != null) {
                if (connection.getLastComms() > other.connection.getLastComms()) {
                    Log.d(TAG,"Both Pairings in update had a socket connection so closing "+other.getLabel()+" - "+other.connection.getClass().getSimpleName()+" and keeping open "+getLabel()+" - "+connection.getClass().getSimpleName());
                    other.connection.close();
                    other.connection = null;
                } else {
                    Log.d(TAG,"Both Pairings in update had a socket connection so closing "+getLabel()+" - "+connection.getClass().getSimpleName()+" and using "+other.getLabel()+" - "+other.connection.getClass().getSimpleName()+" instead");
                    connection.close();
                    connection = other.connection;
                }
            }
        }
        if (other.lastWeakConnection > lastWeakConnection)
            lastWeakConnection = other.lastWeakConnection;
        Log.d(TAG,"Pairing updated: "+toString());
        if (other.device != null)
            checkNetwork();
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
    public void setDevice(SqAnDevice device) {
        this.device = device;
        handleNetworkChange(network);
    }
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

    public static void shutdown() {
        if (pairings != null) {
            synchronized (pairings) {
                for (Pairing pairing : pairings) {
                    pairing.removeNetworkCallback();
                }
            }
        }
        pairings = null;
        ipv6 = null;
        context = null;
        connectivityManager = null;
        manet = null;
    }

    public static void removeUnresponsiveConnections() {
        if ((pairings != null) && !pairings.isEmpty()) {
            synchronized (pairings) {
                int i=0;
                Pairing pairing;
                while (i<pairings.size()) {
                    pairing = pairings.get(i);
                    if (pairing == null) {
                        Log.d(TAG,"Removing null pairing #"+i);
                        pairings.remove(i);
                        continue;
                    }
                    if (pairing.connection != null) {
                        synchronized (pairing.connection) {
                            if (pairing.connection.isStale()) {
                                Log.d(TAG, "Removing stale connection in pairing #" + i + ": " + pairing.toString());
                                pairing.connection.close();
                                pairing.connection = null;
                            }
                        }
                    }
                    /*if (pairing.isConnectionActive() == CONNECTION_STALE) {
                        Log.d(TAG,"Removing stale pairing #"+i+": "+pairing.toString());
                        pairings.get(i).removeNetworkCallback();
                        pairings.remove(i);
                    } else*/
                        i++;
                }
            }
        }
    }

    public void removeNetworkCallback() {
        if ((networkCallback != null) && (connectivityManager != null)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.d(TAG,"Unable to unregister network callback for "+getLabel()+": "+e.getMessage());
            }
            networkCallback = null;
        }
    }
}
