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
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
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
    private static final int MAX_HOP_COUNT = 4; //max number of times a message should be relayed
    private static final String SERVICE_NAME = "SqAN";
    private static final long TIME_TO_CONSIDER_STALE_DEVICE = 1000l * 60l * 5l;
    private BluetoothAdapter bluetoothAdapter;
    private long nextTeammateCheck = Long.MIN_VALUE;

    public BtManetV2(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);

        final BluetoothManager bluetoothManager = (BluetoothManager)context.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();

        Core.init(bluetoothAdapter,this);
        Core.listenForConnectionsAsync(SERVICE_NAME,this);
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
        return BTSocket.MAX_PACKET_SIZE;
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public String getName() { return "Bluetooth Only"; }

    @Override
    public void init() throws ManetException {
        Log.d(Config.TAG,"BtManet init()");
        isRunning.set(true);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
        connectToTeammates();
        //TODO
    }

    private void connectToTeammates() {
        Log.d(Config.TAG,"BtManet.connectToTeammates()");
        nextTeammateCheck = System.currentTimeMillis() + TIME_BETWEEN_TEAMMATE_CHECKS;
        if (!Core.isAtMaxConnections()) {
            ArrayList<Config.SavedTeammate> teammates = Config.getSavedTeammates();
            if ((teammates != null) && !teammates.isEmpty()) {
                for (Config.SavedTeammate teammate : teammates) {
                    //FIXME prefer connections that are not already reached by another means (i.e. have a lower priority connection attempt if we're already connected by WiFi)
                    MacAddress mac = teammate.getBluetoothMac();
                    if ((mac != null) && !Core.isMacConnected(mac)) {
                        String macString = mac.toString();
                        Log.d(Config.TAG, "Teammate " + macString + " is not connected yet");
                        BluetoothDevice device = getDevice(macString);
                        if (device != null)
                            Core.connectAsClientAsync(context, device, BtManetV2.this);
                    }
                }
            }
        }
    }

    private void burst(final byte[] bytes, final int destination, final int origin) {
        //FIXME trying to stop flood during testing
        if (bytes == null) {
            Log.d(Config.TAG, "Cannot send empty byte array");
            return;
        }
        if (bytes.length > getMaximumPacketSize()) {
            Log.d(Config.TAG, "Packet larger than " + getName() + " max; segmenting and sending");
            //TODO segment and burst
        } else {
            handler.post(() -> {
                Log.d(Config.TAG, "burst() - " + bytes.length + "b");
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
                Log.d(Config.TAG,"Packet dropped - exceeded max hop count.");
                return;
            }
            packet.incrementHopCount();
        }
        Log.d(Config.TAG,"Bursting "+packet.getClass().getSimpleName());
        burst(packet.toByteArray(), packet.getSqAnDestination(), packet.getOrigin());
        if (listener != null)
            listener.onTx(packet);
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        //FIXME this needs to be looked at again and incorporate hop counts and maybe routing or this method might be dropped from the abstractManet
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

        Core.cleanup();

        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
        isRunning.set(false);
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
        Core.removeUnresponsiveConnections();
        //BtSocketHandler.removeUnresponsiveConnections();
        /*if ((nodes != null) && !nodes.isEmpty()) {
            Iterator it = nodes.entrySet().iterator();
            long timeToConsiderStale = System.currentTimeMillis() + TIME_TO_CONSIDER_STALE_DEVICE;
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                if ((long)pair.getValue() > timeToConsiderStale) {
                    it.remove();
                    //TODO consider notifying the link that the culling has occurred
                }
            }
        }*/
        //and look for new connections
        if (System.currentTimeMillis() > nextTeammateCheck)
            connectToTeammates();
    }

    public BluetoothDevice getDevice(String mac) {
        if ((mac == null) || !BluetoothAdapter.checkBluetoothAddress(mac))
            return null;
        return bluetoothAdapter.getRemoteDevice(mac);
    }

    /**
     *
     */

    @Override
    public void onNewConnectionAccepted(BTSocket newConnection) {
        Log.d(Config.TAG,"Socket #"+newConnection.getBtSocketIdNum()+" acceptListener.onNewConnectionAccepted()");
        //TODO
    }

    @Override
    public void onError(Exception e, String where) {
        Log.d(Config.TAG,"Socket, "+where+", acceptListener.onError(): "+e.getMessage());
        //TODO
    }

    @Override
    public void onConnectSuccess(BTSocket clientSocket) {
        Log.d(Config.TAG,"Socket #"+clientSocket.getBtSocketIdNum()+" connectionListener.onConnectSuccess()");
        setStatus(Status.CONNECTED);
        MacAddress mac = clientSocket.getMac();
        if (mac != null) {
            SqAnDevice device = SqAnDevice.findByBtMac(clientSocket.getMac());
            if (device == null) {
                Config.SavedTeammate teammate = Config.getTeammateByBtMac(mac);
                if (teammate == null)
                    Log.e(Config.TAG,"Could not find saved teammate with MAC "+mac.toString());
                else {
                    device = new SqAnDevice(teammate.getSqAnAddress());
                    device.setBluetoothMac(mac.toString());
                    device.setConnected(0);
                    SqAnDevice.add(device);
                    Log.d(Config.TAG,"Device SqAN #"+device.getUUID()+" added to list of devices");
                }
            }
        }
    }

    @Override
    public void onConnectionError(Exception exception, String where) {
        Log.e(Config.TAG,"connectionListener.onConnectionError() @ "+where+": "+((exception==null)?"":exception.getMessage()));
        //TODO
    }

    @Override
    public void onSuccess(AbstractPacket packet) {
        if (packet == null) {
            Log.e(Config.TAG,"readListener reported receiving data, but packet was null");
            //TODO
            return;
        }
        onReceived(packet);
    }

    @Override
    public void onError(IOException e) {
        Log.e(Config.TAG,"Read Error: "+e.getMessage());
    }

    @Override
    public void onPacketDropped() {
        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Error parsing data, packet dropped");
    }
}
