package org.sofwerx.sqan.manet.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.AttachCallback;
import android.net.wifi.aware.DiscoverySession;
import android.net.wifi.aware.DiscoverySessionCallback;
import android.net.wifi.aware.IdentityChangedListener;
import android.net.wifi.aware.PeerHandle;
import android.net.wifi.aware.PublishConfig;
import android.net.wifi.aware.PublishDiscoverySession;
import android.net.wifi.aware.SubscribeConfig;
import android.net.wifi.aware.SubscribeDiscoverySession;
import android.net.wifi.aware.WifiAwareManager;
import android.net.wifi.aware.WifiAwareSession;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.NetUtil;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MANET built over the Bluetooth only primarily as a way of testing Bluetooth support architecture
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothSocket)
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothServerSocket)
 *  (https://developer.android.com/guide/topics/connectivity/bluetooth#java)
 *
 */
public class BtManet extends AbstractManet {
    private static final String SERVICE_NAME = "sqan";
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private BluetoothAdapter bluetoothAdapter;
    private Role role = Role.NONE;
    private HashMap<String,Long> nodes = new HashMap<>();
    private final UUID thisDeviceUuid;

    private enum Role {HUB, SPOKE, NONE}

    public BtManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        final BluetoothManager bluetoothManager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        thisDeviceUuid = getUUID(Config.getThisDevice());

        //FIXME hey dummy! you're making a teamates list and teamates activity and then triggering any bluetooth fixes from there
            //then try to connect to teamates base on most recent to last seen
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
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            context.startActivity(enableBtIntent);
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
        AcceptThread acceptThread = new AcceptThread();
        acceptThread.run();
        //TODO
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
        isRunning.set(true);
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
        //clear out stale nodes
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
    }

    /**
     *
     */

    private class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;

        public AcceptThread() {
            // Use a temporary object that is later assigned to mmServerSocket
            // because mmServerSocket is final.
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the client code.
                tmp = bluetoothAdapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, thisDeviceUuid);
            } catch (IOException e) {
                Log.e(Config.TAG, "Socket's listen() method failed", e);
            }
            mmServerSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            // Keep listening until exception occurs or a socket is returned.
            while (true) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    Log.e(Config.TAG, "Socket's accept() method failed", e);
                    break;
                }

                if (socket != null) {
                    // A connection was accepted. Perform work associated with
                    // the connection in a separate thread.
                    manageMyConnectedSocket(socket);
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

    private void manageMyConnectedSocket(BluetoothSocket socket) {
        //TODO
    }

    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            // Use a temporary object that is later assigned to mmSocket
            // because mmSocket is final.
            BluetoothSocket tmp = null;
            mmDevice = device;

            try {
                // Get a BluetoothSocket to connect with the given BluetoothDevice.
                // MY_UUID is the app's UUID string, also used in the server code.
                tmp = device.createRfcommSocketToServiceRecord(thisDeviceUuid);
            } catch (IOException e) {
                Log.e(Config.TAG, "Socket's create() method failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter.cancelDiscovery();

            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and return.
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(Config.TAG, "Could not close the client socket", closeException);
                }
                return;
            }

            // The connection attempt succeeded. Perform work associated with
            // the connection in a separate thread.
            manageMyConnectedSocket(mmSocket);
        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(Config.TAG, "Could not close the client socket", e);
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
}
