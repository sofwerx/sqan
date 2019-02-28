package org.sofwerx.sqan.manet.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * MANET built over the Bluetooth only primarily as a way of testing Bluetooth support architecture
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothSocket)
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothServerSocket)
 *  (https://developer.android.com/guide/topics/connectivity/bluetooth#java)
 *
 */
@Deprecated
public class BtManet extends AbstractManet implements BtSocketListener {
    private static final int MAX_NUM_CONNECTIONS = 4; //Max connections that the BT mesh will support without a hop
    private static final String SERVICE_NAME = "sqan";
    private static final String SQAN_APP_UUID_SEED = "sqan";
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private BluetoothAdapter bluetoothAdapter;
    private Role role = Role.NONE;
    private HashMap<String,Long> nodes = new HashMap<>();
    private final UUID thisAppUuid;
    private AcceptThread acceptThread = null;

    private enum Role {HUB, SPOKE, NONE}

    public BtManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        final BluetoothManager bluetoothManager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        byte[] uuidNameSeed = null;

        try {
            uuidNameSeed = SQAN_APP_UUID_SEED.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
        thisAppUuid = UUID.nameUUIDFromBytes(uuidNameSeed);
    }

    @Override
    public ManetType getType() { return ManetType.BT_ONLY; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            SqAnService.onIssueDetected(new WiFiIssue(true,"This device does not have Bluetooth"));
            passed = false;
        }
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
            try {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                enableBtIntent.addFlags(FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(enableBtIntent);
            } catch (Exception ignore) {
            }
            passed = false;
        }
        return passed;
    }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public String getName() { return "Bluetooth Only"; }

    @Override
    public void init() throws ManetException {
        isRunning.set(true);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
        connectToTeammates();
        //TODO
    }

    private void connectToTeammates() {
        ArrayList<Config.SavedTeammate> teammates = Config.getSavedTeammates();
        if ((teammates != null) && !teammates.isEmpty()) {
            for (Config.SavedTeammate teammate:teammates) {
                MacAddress mac = teammate.getBluetoothMac();
                if ((mac != null) && mac.isValid()) {
                    if ((BtSocketHandler.getNumConnections() < Math.min(teammates.size(),MAX_NUM_CONNECTIONS)) && !BtSocketHandler.isConnected(mac)) {
                        String macString = mac.toString();
                        Log.d(Config.TAG, "Attempting to connect to " + macString);
                        ConnectThread connectThread = new ConnectThread(bluetoothAdapter.getRemoteDevice(macString));
                        connectThread.start();
                    }
                }
            }
        }
    }

    private void burst(AbstractPacket packet, String uuid) {
        if (packet == null) {
            Log.d(Config.TAG,"Cannot send empty packet");
            return;
        }
        burst(packet.toByteArray(),uuid);
    }

    private void burst(final byte[] bytes, final String uuid) {
        if (bytes == null) {
            Log.d(Config.TAG,"Cannot send empty byte array");
            return;
        }
        if (uuid == null) {
            Log.d(Config.TAG,"Cannot send packet to an empty UUID");
            return;
        }
        if (bytes.length > getMaximumPacketSize()) {
            Log.d(Config.TAG,"Packet larger than "+getName()+" max; segmenting and sending");
            //TODO segment and burst
        } else
            handler.post(() -> {
                //TODO
            });
    }

    @Override
    public void burst(final AbstractPacket packet) throws ManetException {
        if (handler != null) {
            handler.post(() -> {
                BtSocketHandler.burst(packet);
                //TODO iterate through connected nodes and burst
                //else
                //    Log.d(Config.TAG,"Tried to burst but no nodes available to receive");
            });
        }
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        if (device == null)
            burst(packet);
        else {
            //TODO find peer and burst
        }
    }

    @Override
    public void connect() throws ManetException {
        //TODO
    }

    @Override
    public void pause() throws ManetException {
        //TODO
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    @Override
    public void disconnect() throws ManetException {
        //TODO
        setStatus(Status.OFF);
        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
        BtSocketHandler.removeAllConnections();
        isRunning.set(false);
        if (acceptThread != null) {
            acceptThread.cancel();
            acceptThread = null;
        }
    }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        //TODO
    }

    @Override
    public void executePeriodicTasks() {
        if (!isRunning()) {
            try {
                Log.d(Config.TAG,"Attempting to restart "+getName());
                init();
            } catch (ManetException e) {
                Log.e(Config.TAG, "Unable to initialize "+getName()+": " + e.getMessage());
            }
        }

        //AcceptThread isnt run immediately to allow the device to try to connect to existing peers first
        if (acceptThread == null) {
            acceptThread = new AcceptThread();
            acceptThread.start();
        }

        //clear out stale nodes
        BtSocketHandler.removeUnresponsiveConnections();
        if ((nodes != null) && !nodes.isEmpty()) {
            Iterator it = nodes.entrySet().iterator();
            long timeToConsiderStale = System.currentTimeMillis() + TIME_TO_CONSIDER_STALE_DEVICE;
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                if ((long)pair.getValue() > timeToConsiderStale) {
                    it.remove();
                    //TODO consider notifying the link that the culling has occurred
                }
            }
        }
        //and look for new connections
        connectToTeammates();
    }

    /**
     *
     */

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            Log.d(Config.TAG,"AcceptThread constructor");
            // Use a temporary object that is later assigned to mmServerSocket
            // because mmServerSocket is final.
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the client code.
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, thisAppUuid);
                Log.d(Config.TAG,"RF Comm socket listener");
            } catch (IOException e) {
                Log.e(Config.TAG, "Socket's listen() method failed: "+e.getMessage());
            }
            mmServerSocket = tmp;
        }

        public void run() {
            Log.d(Config.TAG,"AcceptThread.run()");
            BluetoothSocket socket = null;
            // Keep listening until exception occurs or a socket is returned.
            while (true) {
                try {
                    socket = mmServerSocket.accept();
                    Log.d(Config.TAG,"mmServerSocket accepted");
                } catch (IOException e) {
                    Log.e(Config.TAG, "Socket accept() method failed: " + e.getMessage());
                    break;
                }

                if (socket != null) {
                    // A connection was accepted. Perform work associated with
                    // the connection in a separate thread.
                    handleSocket(socket);
                    try {
                        mmServerSocket.close();
                    } catch (IOException e) {
                        Log.e(Config.TAG,"Error closing socket: "+e.getMessage());
                    }
                    break;
                }
            }
        }

        // Closes the connect socket and causes the thread to finish.
        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                Log.e(Config.TAG, "Could not close the connect socket", e);
            }
        }
    }

    private void handleSocket(BluetoothSocket socket) {
        Log.d(Config.TAG,"handleSocket()");
        if (socket == null)
            return;
        BtSocketHandler h = new BtSocketHandler(socket, parser, this);
        h.start();
        setStatus(Status.CONNECTED);
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            Log.d(Config.TAG,"ConnectThread created");
            // Use a temporary object that is later assigned to mmSocket
            // because mmSocket is final.
            BluetoothSocket tmp = null;
            mmDevice = device;

            try {
                // Get a BluetoothSocket to connect with the given BluetoothDevice.
                // MY_UUID is the app's UUID string, also used in the server code.
                tmp = device.createRfcommSocketToServiceRecord(thisAppUuid);
                Log.d(Config.TAG,"RF Comm Socket created");
            } catch (IOException e) {
                Log.e(Config.TAG, "Socket's create() method failed" + e.getMessage());
            }
            mmSocket = tmp;
        }

        public void run() {
            Log.d(Config.TAG,"ConnectThread.run()");
            //bluetoothAdapter.cancelDiscovery(); //TODO discovery not initiated (and probably not needed)

            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
                if (mmSocket.getRemoteDevice() != null)
                    Log.d(Config.TAG,"BT socket connected to "+mmSocket.getRemoteDevice().getName());
            } catch (IOException connectException) {
                Log.d(Config.TAG,"Connect failure: "+connectException.getMessage());
                // Unable to connect; close the socket and return.
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(Config.TAG, "Could not close the client socket: " + closeException.getMessage());
                }
                return;
            }

            handleSocket(mmSocket);
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(Config.TAG, "Could not close the client socket: " + e.getMessage());
            }
        }
    }

    private UUID getUUID(SqAnDevice device) {
        UUID uuid;
        if (device == null)
            return null;

        long id = (long)device.getUUID();
        uuid = new UUID(id,id);

        return uuid;
    }

    public BluetoothDevice getDevice(String mac) {
        if ((mac == null) || !BluetoothAdapter.checkBluetoothAddress(mac))
            return null;
        return bluetoothAdapter.getRemoteDevice(mac);
    }

    @Override
    public void onConnectionError(String warning) {
        Log.e(Config.TAG,"BtManet error: "+warning);
        //TODO
    }

    @Override
    public void onBtSocketDisconnected(SqAnDevice device) {
        Log.d(Config.TAG,"onBtSocketDisconnected");
        //TODO
    }
}
