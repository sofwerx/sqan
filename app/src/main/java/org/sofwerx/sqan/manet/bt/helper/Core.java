package org.sofwerx.sqan.manet.bt.helper;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.bt.Discovery;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.util.CommsLog;

public class Core {
    private static final boolean ALWAYS_ACCEPT_SERVER = true; ///should this device in server mode always accept connections
    public static final int MAX_NUM_CONNECTIONS = 3; //Max connections that the BT mesh will support without a hop
    private static final long ACCEPT_RECHECK_INTERVAL_WHEN_AT_MAX_SOCKETS = 1000l * 15l; //how long to wait to see if new connections should be accepted when the server is at its max number of connections
    private static final boolean ALLOW_FAILOVER_OPTIONS = false;
    private static final String TAG = Config.TAG+".Bt.Core";
    private static final String SQAN_APP_UUID_SEED = "sqan";
    private static volatile UUID appUuid;
    private static volatile UUID DEFAULT_SPP_UUID;
    private static BluetoothAdapter bluetoothAdapter;
    private static final ArrayList<BTSocket> allSockets = new ArrayList<BTSocket>();
    private static BluetoothServerSocket btServerSocket;
    private static volatile boolean listeningIsOn;
    private static volatile boolean connectingNow;
    private static volatile ReadListener readListener;

    /**
     * Create a secure BT socket and connect to a remote BT device (server).
     * Spawns a dedicated connect thread
     */
    public static void connectAsClientAsync(final Context context, final BluetoothDevice device, final DeviceConnectionListener connectionListener) {
        connectAsClientAsync(context, device, connectionListener, true, appUuid);
    }


    /**
     * Create a BT socket and connect to a remote BT device (server).
     *
     * The secureMode param controls the type of RFC socket created i.e. weather
     * createRfcommSocketToServiceRecord or createInsecureRfcommSocketToServiceRecord services will be called.
     *
     * Spawns a dedicated connect thread
     */
    private static void connectAsClientAsync(final Context context, final BluetoothDevice device, final DeviceConnectionListener connectionListener, final boolean secure, final UUID user_serviceUuid) {
        new Thread() {
            public void run() {
                if (device == null)
                    return;
                if (Core.isMacConnected(MacAddress.build(device.getAddress()))) {
                    Log.d(TAG,"connectAsClientAsync(MAC "+device.getAddress()+") called, but this MAC is already connected; ignoring...");
                    return;
                } else
                    Log.d(TAG,"connectAsClientAsync(MAC "+device.getAddress()+")");
                boolean connected;
                BluetoothSocket sock;
                UUID serviceUuid = user_serviceUuid;
                if (serviceUuid != null) {
                    sock = createClientSocket(device, secure, serviceUuid);
                    if (sock == null) {
                        connectionListener.onConnectionError(null, "createClientSocket");
                        return; // operation failed
                    }
                    connected = innerConnectAsClient(context, connectionListener, sock);
                    if (!connected) {
                        if (connectionListener != null)
                            connectionListener.onConnectionError(null, "ConnectAsClient");
                        return; // operation failed
                    }
                } else {
                    if (ALLOW_FAILOVER_OPTIONS) {
                        Log.d(TAG, "Failover 1: first supported UUID");
                        UUID[] uuids = getSupportedUuids(device);
                        if (uuids != null && uuids.length > 0) {
                            serviceUuid = uuids[0];
                            sock = createClientSocket(device, secure, serviceUuid);
                            if (sock != null) {
                                connected = innerConnectAsClient(context, connectionListener, sock);
                                if (connected) {
                                    return; // success
                                }
                            }
                        }

                        Log.d(TAG, "Failover 2: using default SPP");
                        sock = createClientSocket(device, secure, DEFAULT_SPP_UUID);
                        if (sock != null) {
                            connected = innerConnectAsClient(context, connectionListener, sock);
                            if (connected)
                                return; // success
                        }

                        Log.d(TAG, "Failover 3: createRfcommSocket via reflection");
                        sock = createRfcommSocketViaReflection(device, secure);
                        if (sock == null) {
                            connectionListener.onConnectionError(null, "createRfcommSocket");
                            return; // failed
                        }
                        connected = innerConnectAsClient(context, connectionListener, sock);
                        if (!connected) {
                            connectionListener.onConnectionError(null, "ConnectAsClient");
                            return; // failed
                        }
                    } else
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Failover not enabled so since the provided service UUID was null, connectAsClientAsync does nothing");
                }
            }

        }.start();
    }


    protected static BluetoothSocket createRfcommSocketViaReflection(BluetoothDevice device, boolean secure) {
        try {
            Method createMethod;
            if (secure)
                createMethod = device.getClass().getMethod("createRfcommSocket", new Class[] {int.class });
            else
                createMethod = device.getClass().getMethod("createInsecureRfcommSocket", new Class[] { int.class });
            BluetoothSocket sock = (BluetoothSocket)createMethod.invoke(device, 1);
            if (sock==null) {
                throw new RuntimeException("createRfcommSocket activation failed");
            }
            return sock;
        } catch (Exception e) {
            e.printStackTrace();
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Activation of createRfcommSocket via reflection failed: " + e);
            return null;
        }
    }

    public static void send(final byte[] data, final int destination, final int origin) {
        send(data, destination, origin,false,false);
    }

    /**
     * Sends data to another destination
     * @param data
     * @param destination
     * @param origin
     * @param clientsOnly true == only send this to sockets where I am the hub
     * @param isForwardedPacket true == this packet is being forwarded from another device
     */
    public static void send(final byte[] data, int destination, final int origin, boolean clientsOnly, boolean isForwardedPacket) {
        if (origin <= 0)
            Log.d(TAG,"send() with an origin value of "+origin+" (this should not be)");
        synchronized (allSockets) {
            if ((data != null) && !allSockets.isEmpty()) {
                boolean sent = false;
                int i=0;
                boolean useThisManet = true;
                AbstractPacket reconstructedPacket = null;

                while (i<allSockets.size()) {
                    BTSocket socket = allSockets.get(i);
                    if (socket.isApplicableToThisDevice(destination)) {
                        if (socket.isActive()) {
                            if ((socket.getDevice() != null) && !socket.isThisDeviceOrigin(origin)) { //avoid circular reporting and spamming devices that haven't passed their basic identifying info yet
                                if (!clientsOnly || (socket.getRole() == BTSocket.Role.SERVER)) {
                                    if (isForwardedPacket) {
                                        if (socket.getDevice().isBtPreferred()) {
                                            socket.getDevice().setLastForward();
                                            CommsLog.log(CommsLog.Entry.Category.COMMS, "BT socket #" + socket.getBtSocketIdNum() + " relaying " + data.length + "b from " + origin + " to " + socket.getDevice().getUUID() + ((socket.getDevice() == null) ? "" : " (" + socket.getDevice().getCallsign() + ")"));
                                        } else {
                                            if ((SqAnService.getInstance() != null) && SqAnService.getInstance().isWiFiManetAvailable()) {
                                                if (reconstructedPacket == null)
                                                    reconstructedPacket = AbstractPacket.newFromBytes(data);
                                                CommsLog.log(CommsLog.Entry.Category.COMMS, "BT socket #" + socket.getBtSocketIdNum() + " referring " + data.length + "b from " + origin + " to " + socket.getDevice().getUUID() + ((socket.getDevice() == null) ? "" : " (" + socket.getDevice().getCallsign() + ") for relay over WiFi"));
                                                SqAnService.burstVia(reconstructedPacket, TransportPreference.WIFI);
                                                useThisManet = (socket.getDevice().getPreferredTransport() == TransportPreference.ALL);
                                                if (useThisManet) {
                                                    socket.getDevice().setLastForward();
                                                    CommsLog.log(CommsLog.Entry.Category.COMMS, "BT socket #" + socket.getBtSocketIdNum() + " relaying (WiFi was preferred but not available) " + data.length + "b from " + origin + " to " + socket.getDevice().getUUID() + ((socket.getDevice() == null) ? "" : " (" + socket.getDevice().getCallsign() + ")"));
                                                }
                                            } else {
                                                socket.getDevice().setLastForward();
                                                CommsLog.log(CommsLog.Entry.Category.COMMS, "BT socket #" + socket.getBtSocketIdNum() + " relaying (WiFi was preferred but not available) " + data.length + "b from " + origin + " to " + socket.getDevice().getUUID() + ((socket.getDevice() == null) ? "" : " (" + socket.getDevice().getCallsign() + ")"));
                                            }
                                        }
                                    } else
                                        CommsLog.log(CommsLog.Entry.Category.COMMS,"BT socket #"+socket.getBtSocketIdNum()+" sending "+data.length+"b to "+socket.getDevice().getUUID());
                                    if (useThisManet) {
                                        socket.write(data);
                                        sent = true;
                                    }
                                } else
                                    Log.d(TAG, "Skipping " + data.length + "b burst over BT socket #" + socket.getBtSocketIdNum() + " (packet destined for spokes only)");
                            }
                        } else
                            Log.d(TAG, "Skipping " + data.length + "b burst over BT socket #" + socket.getBtSocketIdNum() + " (socket is not active)");
                    } else
                        Log.d(TAG,"Skipping packet because destination "+destination+" is not applicable to the device on BT Socket #"+socket.getBtSocketIdNum());
                    i++;
                }
                if (sent)
                    ManetOps.addBytesToTransmittedTally(data.length);
                else
                    Log.d(TAG,"send(data) ignored as destination SqAN #"+((destination==PacketHeader.BROADCAST_ADDRESS)?"(All node broadcast)":Integer.toString(destination))+" did not apply to any currently connected devices");
            }
        }
    }

    private static boolean innerConnectAsClient(Context context, DeviceConnectionListener connectionListener, BluetoothSocket sock) {
        Log.d(TAG,"innerConnectAsClient()");
        BTSocket clientSocket = new BTSocket(sock, BTSocket.Role.CLIENT, readListener);
        boolean success;
        try {
            // block until success
            Core.markConnecting(true);
            success = clientSocket.connect();
            Core.markConnecting(false);
            if (success) {
                clientSocket.startConnections();
                if (connectionListener != null)
                    connectionListener.onConnectSuccess(clientSocket);
                return true; // success
            }
        } catch (Exception e) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Connect error: " + e.getMessage());
            if (connectionListener != null)
                connectionListener.onConnectionError(e, "connect");
        } finally {
            Core.markConnecting(false);
        }
        synchronized (allSockets) {
            if (clientSocket.getMac() == null)
                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing failed BT socket #"+clientSocket.getBtSocketIdNum());
            else
                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing failed BT socket #"+clientSocket.getBtSocketIdNum()+" to "+clientSocket.getMac().toString() + ((clientSocket.getDevice()==null)?"":" ("+clientSocket.getDevice().getCallsign()+")"));
            allSockets.remove(clientSocket);
        }
        return false; // failure
    }

    private static BluetoothSocket createClientSocket(BluetoothDevice device, boolean secure, UUID serviceUuid) {
        BluetoothSocket sock;
        try {
            if (secure)
                sock = device.createRfcommSocketToServiceRecord(serviceUuid);
            else
                sock = device.createInsecureRfcommSocketToServiceRecord(serviceUuid);
            if (sock == null)
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Null socket error after createRfcommSocket" );
            return sock;

        } catch (IOException e) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Error in createRfcommSocket: " + e);
            return null;
        }
    }

    public static boolean isSqAnSupported(BluetoothDevice device) {
        if (device == null)
            return false;
        String name = device.getName();
        if (name == null)
            name = device.getAddress();
        if (appUuid == null) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "isSqAnSupported cannot examine "+name+" yet as Core.init() has not been called");
            return false;
        }
        UUID[] uuids = getSupportedUuids(device);
        if (uuids != null) {
            CommsLog.log(CommsLog.Entry.Category.CONNECTION, uuids.length+" UUIDs provided for "+name+" ("+device.getAddress()+")");
            for (UUID uuid : uuids) {
                if (uuid.equals(appUuid)) {
                    CommsLog.log(CommsLog.Entry.Category.CONNECTION, " ...which includes one with a SqAN designation");
                    return true;
                }
            }
            CommsLog.log(CommsLog.Entry.Category.CONNECTION, " ...none of which are registered to SqAN");
        } else
            CommsLog.log(CommsLog.Entry.Category.CONNECTION, "No UUIDs provided for "+name+" ("+device.getAddress()+")");
        return false;
    }

    /**
     * Returns the supported features (UUIDs) of the remote device (no discovery!)
     */
    public static UUID[] getSupportedUuids(BluetoothDevice device) {
        if (device == null)
            return null;
        ParcelUuid[] pUuids;
        //if (android.os.Build.VERSION.SDK_INT >= VERSION_CODES.ICE_CREAM_SANDWICH_MR1)
            pUuids = device.getUuids();
        /*else {
            // getUuids is (?) an hidden api; use reflection
            try {
                Class cl = Class.forName("android.bluetooth.BluetoothDevice");
                Class[] params = {};
                Method method = cl.getMethod("getUuids", params);
                Object[] args = {};
                pUuids = (ParcelUuid[])method.invoke(device, args);
            } catch (Exception e) {
                Log.e(TAG, "Activation of getUuids() via reflection failed: " + e);
            }
        }*/

        if (pUuids == null || pUuids.length == 0) {
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"No UUIDs stored for "+device.getName()+"; trying to fetch UUIDs with SDP...");
            device.fetchUuidsWithSdp();
            return null;
        }
        int len = pUuids.length;
        UUID[] results = new UUID[len];
        for (int i = 0; i < len; i++) {
            results[i] = pUuids[i].getUuid();
        }
        return results;
    }


    /**
     * Create a secure-mode BT server, enter an accept loop and listen for BT connections
     * Spawns a dedicated connect thread
     */
    public static void listenForConnectionsAsync(final String name, final AcceptListener acceptListener) {
        listenForConnectionsAsync(name, acceptListener, true);
    }

    /**
     * Create a BT server, enter an accept loop and listen for BT connections
     *
     * The secureMode param controls the secure mode of the server, i.e. weather listenUsingRfcommWithServiceRecord
     * or listenUsingInsecureRfcommWithServiceRecord services will be called.
     *
     * Spawns a dedicated connect thread
     */
    public static void listenForConnectionsAsync(final String name, final AcceptListener acceptListener, final boolean secure) {
        listeningIsOn = true;
        new Thread() {
            public void run() {
                try {
                    createBTServerSocket(name, secure);
                } catch (Exception e) {
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "BT Socket creation error: " + e.getMessage());
                    if (acceptListener != null)
                        acceptListener.onError(e, "createBTServerSocket");
                    return;
                }

                try {
                    boolean keepGoing = true;
                    while (listeningIsOn && keepGoing) {
                        keepGoing = acceptConnection(acceptListener);
                    }
                } finally {
                    closeBTServerSocket();
                }
            }
        }.start();
    }

    public static int getActiveClientsCount() {
        int active = 0;
        synchronized (allSockets) {
            for (BTSocket socket : allSockets) {
                if (socket.isActive() && (socket.getRole() == BTSocket.Role.SERVER))
                    active++;
            }
        }
        return active;
    }

    public static int getActiveClientsAndServerCount() {
        int active = 0;
        boolean isClient = false;
        boolean isServer = false;
        synchronized (allSockets) {
            for (BTSocket socket : allSockets) {
                if (socket.isActive()) {
                    isClient = isClient || (socket.getRole() == BTSocket.Role.CLIENT);
                    isServer = isServer || (socket.getRole() == BTSocket.Role.SERVER);
                    active++;
                }
            }
        }
        SqAnDevice thisDevice = Config.getThisDevice();
        if (isClient) {
            if (isServer)
                thisDevice.setRoleBT(SqAnDevice.NodeRole.BOTH);
            else
                thisDevice.setRoleBT(SqAnDevice.NodeRole.SPOKE);
        } else if (isServer)
            thisDevice.setRoleBT(SqAnDevice.NodeRole.HUB);
        else
            thisDevice.setRoleBT(SqAnDevice.NodeRole.OFF);
        return active;
    }

    /**
     * Accepts an incoming BT connection
     */
    public static boolean acceptConnection(AcceptListener acceptListener) {
        BluetoothSocket sock;

        try {
            sock = btServerSocket.accept(); // returns a connected socket
            if (ALWAYS_ACCEPT_SERVER || (getActiveClientsCount() < MAX_NUM_CONNECTIONS)) {
            } else {
                Log.d(TAG, "Server is no longer listener for client connections as the max number of clients has been reached; closing any connection attempts");
                sock.close();
                try {
                    Thread.sleep(ACCEPT_RECHECK_INTERVAL_WHEN_AT_MAX_SOCKETS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                return true;
            }
        } catch (IOException e) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Socket accept error: " + e);
            if (acceptListener != null)
                acceptListener.onError(e, "accept");
            e.printStackTrace();
            return false; // exit accept loop
        }

        if (sock == null) {
            return true; // accept() failed; re-enter function
        }

        if (sock.getRemoteDevice() != null)
            removeClient(MacAddress.build(sock.getRemoteDevice().getAddress()));

        BTSocket newConnection = new BTSocket(sock, BTSocket.Role.SERVER, readListener);
        newConnection.startConnections();

        if (acceptListener != null)
            acceptListener.onNewConnectionAccepted(newConnection);
        return true; // keep going
    }

    /**
     * Creates a server socket to be used by the accept thread
     */
    public static void createBTServerSocket(String name, boolean secure) throws IOException {
        BluetoothServerSocket tmp;
        btServerSocket = null;
        try {
            if (secure) {
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(name, appUuid);
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "BT Server listening for RFC Comms (Secure)");
            } else {
                tmp = bluetoothAdapter.listenUsingInsecureRfcommWithServiceRecord(name, appUuid);
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "BT Server listening for RFC Comms (Insecure)");
            }
            btServerSocket = tmp;
        } catch (IOException e) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "listenUsingRfcomm error: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Cleanup all BT resources.
     * Should be called once BT usage by the app is completed
     */
    public static void cleanup() {
        closeBTServerSocket();
        closeAllOpenSockets();
        BTSocket.closeIOThreads();
        bluetoothAdapter = null;
    }

    private static void closeBTServerSocket() {
        listeningIsOn = false;
        if (btServerSocket != null) {
            try {
                btServerSocket.close(); // thread safe
            } catch (Exception ignore) {
            }
            btServerSocket = null;
        }
    }

    /**
     * Checks for device BT support and initializes the bluetoothAdapter
     * Should not, typically, be called directly by the using app
     */
    public static void init(BluetoothAdapter adapter, ReadListener readListener) {
        if (bluetoothAdapter != null)
            return; // already initialized

        Discovery.checkPairedDeviceStatus(adapter);
        Core.readListener = readListener;
        adapter.cancelDiscovery(); //make sure discovery mode is off
        byte[] uuidNameSeed = null;

        try {
            uuidNameSeed = SQAN_APP_UUID_SEED.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        appUuid = UUID.nameUUIDFromBytes(uuidNameSeed);
        DEFAULT_SPP_UUID = appUuid; //TODO temp

        bluetoothAdapter = adapter;
    }

    /**
     * Registered a BluetoothSocket for future cleanup
     */
    public static void registerForCleanup(BTSocket socket) {
        if (socket==null)
            throw new RuntimeException("Registered socket cannot be null!");
        synchronized (allSockets) {
            allSockets.add(socket);
            Log.d(TAG,"allSockets count now "+allSockets.size());
        }
    }

    /**
     * Is this MAC address already connected
     * @param mac
     * @return
     */
    public static boolean isMacConnected(MacAddress mac) {
        if ((mac != null) && mac.isValid()) {
            synchronized (allSockets) {
                for (BTSocket socket : allSockets) {
                    if (mac.isEqual(socket.getMac()))
                        return true;
                }
            }
        }
        return false;
    }

    public static void closeAllOpenSockets() {
        synchronized (allSockets) {
            for (BTSocket sock: allSockets) {
                sock.close();
            }
            allSockets.clear();
        }
    }

    public static void markConnecting(boolean connecting) {
        connectingNow = connecting;
    }

    /**
     * Forcibly removes a client (usually used if this device is just accepted a connection to
     * this mac while this device is acting as a server)
     * @param mac
     */
    public static void removeClient(MacAddress mac) {
        if ((mac != null) && mac.isValid()) {
            synchronized (allSockets) {
                int i = 0;
                while (i < allSockets.size()) {
                    if (mac.isEqual(allSockets.get(i).getMac())) {
                        MacAddress otherMac = allSockets.get(i).getMac();
                        if (otherMac != null) {
                            Log.d(TAG, "BT socket #" + allSockets.get(i).getBtSocketIdNum() + "("+otherMac.toString()+")" + " being forcibly removed from current connections");
                            allSockets.get(i).close();
                            allSockets.remove(i);
                        }
                    } else
                        i++;
                }
            }
        } else
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Cannot removeClient() on a "+((mac==null)?"null":"invalid ("+mac.toString()+")")+" MAC");
    }

    public static void removeUnresponsiveConnections() {
        synchronized (allSockets) {
            int i=0;
            while (i<allSockets.size()) {
                //de-dupe
                if (!allSockets.get(i).isActive()) {
                    MacAddress mac = allSockets.get(i).getMac();
                    if ((mac == null) || !mac.isValid()) {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing BT socket #"+allSockets.get(i).getBtSocketIdNum()+", null or invalid MAC");
                        allSockets.remove(i);
                        continue;
                    }
                    int b=i+1;
                    boolean iRemoved = false;
                    while (b<allSockets.size()) {
                        if (mac.isEqual(allSockets.get(b).getMac())) {
                            if (allSockets.get(b).isActive()) {
                                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing duplicate (MAC "+mac.toString()+") on inactive BT socket #"+allSockets.get(i).getBtSocketIdNum());
                                allSockets.remove(i);
                                iRemoved = true;
                                break;
                            } else {
                                CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing duplicate (MAC "+mac.toString()+") on inactive BT socket #"+allSockets.get(b).getBtSocketIdNum());
                                allSockets.remove(b);
                            }
                        } else
                            b++;
                    }
                    if (iRemoved)
                        continue;
                }

                //handle stale
                if (allSockets.get(i).isStale()) {
                    Log.d(TAG,"BT socket #"+allSockets.get(i).getBtSocketIdNum()+" is stale; removing from current connections");
                    allSockets.get(i).close();
                    allSockets.remove(i);
                } else
                    i++;
            }
        }
    }

    public static boolean isAtMaxConnections() {
        return getActiveClientsAndServerCount() >= MAX_NUM_CONNECTIONS;
    }
}