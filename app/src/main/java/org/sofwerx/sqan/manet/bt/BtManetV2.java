package org.sofwerx.sqan.manet.bt;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.bt.helper.BTSocket;
import org.sofwerx.sqan.manet.bt.helper.Core;
import org.sofwerx.sqan.manet.bt.helper.AcceptListener;
import org.sofwerx.sqan.manet.bt.helper.DeviceConnectionListener;
import org.sofwerx.sqan.manet.bt.helper.ReadListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.TeammateConnectionPlanner;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.util.ArrayList;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * MANET built over the Bluetooth only primarily as a way of testing Bluetooth support architecture
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothSocket)
 *  (https://developer.android.com/reference/android/bluetooth/BluetoothServerSocket)
 *  (https://developer.android.com/guide/topics/connectivity/bluetooth#java)
 *
 */
public class BtManetV2 extends AbstractManet implements AcceptListener, DeviceConnectionListener, ReadListener {
    private static final long TIME_BETWEEN_TEAMMATE_CHECKS = 1000l * 15l;
    private static final long OLD_DEVICE_CHECK_INTERVAL = 1000l * 60l;
    private static final int MAX_HOP_COUNT = 4; //max number of times a message should be relayed
    private static final String SERVICE_NAME = "SqAN";
    private final static String TAG = Config.TAG+".BTSocket";
    private BluetoothAdapter bluetoothAdapter;
    private long nextTeammateCheck = Long.MIN_VALUE;
    private long nextOldDeviceCheck = Long.MIN_VALUE;
    private static BtManetV2 instance;

    public BtManetV2(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        instance = this;

        final BluetoothManager bluetoothManager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        Core.init(bluetoothAdapter,this);
        Core.listenForConnectionsAsync(SERVICE_NAME,this);
    }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        //ignore
    }

    @Override
    public ManetType getType() { return ManetType.BT_ONLY; }

    public static BtManetV2 getInstance() { return instance; }

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
        return BTSocket.MAX_PACKET_SIZE;
    }

    @Override
    public boolean isCongested() {
        //FIXME
        return false;
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public String getName() { return "Bluetooth Only"; }

    @Override
    public void init() throws ManetException {
        Log.d(TAG,"BtManetV2 init()");
        isRunning.set(true);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
        connectToTeammates();
        //TODO
    }

    private boolean reportedAtMax = false;
    private void connectToTeammates() {
        Log.d(TAG,"BtManetV2.connectToTeammates()");
        nextTeammateCheck = System.currentTimeMillis() + TIME_BETWEEN_TEAMMATE_CHECKS;
        boolean addedNewCheck = false;
        boolean notSeekingNew = false;
        int pendingConnections = Core.getActiveClientsCount();
        if (!Core.isAtMaxConnections()) {
            reportedAtMax = false;
            ArrayList<SavedTeammate> teammates = TeammateConnectionPlanner.getDescendingPriorityTeammates();
            int active = Core.getActiveClientsAndServerCount();
            if ((teammates != null) && !teammates.isEmpty()) {
                for (SavedTeammate teammate : teammates) {
                    //when there are few (i.e. 2 or less) connections, try to connect with everyone, otherwise just try to connect with a few priority devices
                    if (teammate.isEnabled() && ((active < 3) || (pendingConnections < Core.MAX_NUM_CONNECTIONS))) {
                        MacAddress mac = teammate.getBluetoothMac();
                        if ((mac != null) && !Core.isMacConnected(mac)) {
                            String macString = mac.toString();
                            Log.d(TAG, "Teammate " + teammate.getLabel() + " is not connected yet");
                            BluetoothDevice device = getDevice(macString);
                            if (device != null) {
                                pendingConnections++;
                                Core.connectAsClientAsync(context.getApplicationContext(), device, BtManetV2.this);
                                addedNewCheck = true;
                            }
                        }
                    } else {
                        if (teammate.isEnabled())
                            notSeekingNew = true;
                    }
                }
            }
        } else {
            notSeekingNew = true;
            if (!reportedAtMax) {
                reportedAtMax = true;
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Max number of bluetooth connections reached; not accepting new connections at this time.");
            }
        }
        if (!addedNewCheck) {
            Log.d(TAG, "No teammates found without a current connection or connection attempt");
            if (notSeekingNew)
                CommsLog.log(CommsLog.Entry.Category.STATUS,"BT MANET considers itself at capacity and is not seeking new connections as a client (with "+pendingConnections+" pending connections)");
        }
    }

    private void burst(final byte[] bytes, final int destination, final int origin) {
        if (bytes == null) {
            Log.d(TAG, "Cannot send empty byte array");
            return;
        }
        if (bytes.length > getMaximumPacketSize()) {
            Log.d(TAG, "Packet larger than " + getName() + " max; segmenting and sending");
            //TODO segment and burst
        } else {
            handler.post(() -> {
                Log.d(TAG, "burst() - " + bytes.length + "b");
                Core.send(bytes, destination, origin);
            });
        }
    }

    @Override
    public void burst(final AbstractPacket packet) throws ManetException {
        if (packet == null)
            return;
        if (packet.getOrigin() != Config.getThisDevice().getUUID()) {
            if (packet.getCurrentHopCount() > MAX_HOP_COUNT) {
                Log.d(TAG,"Packet dropped - exceeded max hop count.");
                return;
            }
        }
        Log.d(TAG,"Bursting "+packet.getClass().getSimpleName());
        burst(packet.toByteArray(), packet.getSqAnDestination(), packet.getOrigin());
        if (listener != null)
            listener.onTx(packet);
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
        instance = this;
    }

    @Override
    public void disconnect() throws ManetException {
        //TODO
        instance = null;
        setStatus(Status.OFF);

        Core.cleanup();

        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
        super.disconnect();
    }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        //TODO
    }

    @Override
    public void executePeriodicTasks() {
        if (!isRunning()) {
            try {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Attempting to restart "+getName());
                init();
            } catch (ManetException e) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to initialize "+getName()+": " + e.getMessage());
            }
        }

        //clear out stale nodes
        if (System.currentTimeMillis() > nextOldDeviceCheck) {
            nextOldDeviceCheck = System.currentTimeMillis() + OLD_DEVICE_CHECK_INTERVAL;
            Core.removeUnresponsiveConnections();
            SqAnDevice.cullOldDevices();
        }

        //and look for new connections
        if (System.currentTimeMillis() > nextTeammateCheck)
            connectToTeammates();
    }

    public BluetoothDevice getDevice(String mac) {
        if ((mac == null) || !BluetoothAdapter.checkBluetoothAddress(mac))
            return null;
        return bluetoothAdapter.getRemoteDevice(mac);
    }

    @Override
    protected boolean isBluetoothBased() { return true; }

    @Override
    protected boolean isWiFiBased() { return false; }

    @Override
    public void onNewConnectionAccepted(BTSocket newConnection) {
        Log.d(TAG,"Socket #"+newConnection.getBtSocketIdNum()+" acceptListener.onNewConnectionAccepted()");
        //TODO
    }

    @Override
    public void onError(Exception e, String where) {
        Log.d(TAG,"Socket, "+where+", acceptListener.onError(): "+e.getMessage());
        //TODO
    }

    @Override
    public void onConnectSuccess(BTSocket clientSocket) {
        Log.d(TAG,"Socket #"+clientSocket.getBtSocketIdNum()+" connectionListener.onConnectSuccess()");
        setStatus(Status.CONNECTED);
        MacAddress mac = clientSocket.getMac();
        if (mac != null) {
            SqAnDevice device = SqAnDevice.findByBtMac(clientSocket.getMac());
            if (device == null) {
                SavedTeammate teammate = Config.getTeammateByBtMac(mac);
                if (teammate == null)
                    Log.e(TAG,"Could not find saved teammate with MAC "+mac.toString());
                else {
                    device = new SqAnDevice(teammate.getSqAnAddress());
                    device.setBluetoothMac(mac.toString());
                    device.setConnected(0,true,false);
                    SqAnDevice.add(device);
                    CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Device SqAN #"+device.getUUID()+" added to list of devices");
                }
            }
        }
    }

    @Override
    public void onConnectionError(Exception exception, String where) {
        Log.e(TAG,"connectionListener.onConnectionError() @ "+where+": "+((exception==null)?"":exception.getMessage()));
        //TODO
    }

    @Override
    public void onSuccess(AbstractPacket packet) {
        if (packet == null) {
            Log.e(TAG,"readListener reported receiving data, but packet was null");
            //TODO
            return;
        }
        onReceived(packet);
    }

    @Override
    public void onError(IOException e) {
        Log.e(TAG,"Read Error: "+e.getMessage());
    }

    @Override
    public void onPacketDropped() {
        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Error parsing data, packet dropped");
    }
}
