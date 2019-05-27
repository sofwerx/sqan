package org.sofwerx.sqan.manet.sdr;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.TeammateConnectionPlanner;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.sdr.helper.AcceptListener;
import org.sofwerx.sqan.manet.sdr.helper.DeviceConnectionListener;
import org.sofwerx.sqan.manet.sdr.helper.ReadListener;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.util.ArrayList;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

/**
 * MANET built for including hops over SDR
 *
 */
public class SdrManet extends AbstractManet implements AcceptListener, DeviceConnectionListener, ReadListener {
    private static final int MAX_PACKET_SIZE = 64000; //TODO arbitrary
    private final static long INTERVAL_BEFORE_STALE = 1000l * 60l;
    private static final long TIME_BETWEEN_TEAMMATE_CHECKS = 1000l * 15l;
    private static final long OLD_DEVICE_CHECK_INTERVAL = 1000l * 60l;
    private static final int MAX_HOP_COUNT = 4; //max number of times a message should be relayed
    private static final String SERVICE_NAME = "SqAN";
    private final static String TAG = Config.TAG+".Sdr";
    private long nextTeammateCheck = Long.MIN_VALUE;
    private long nextOldDeviceCheck = Long.MIN_VALUE;
    private static SdrManet instance;
    private long staleTime = Long.MIN_VALUE;
    //TODO private SqANDRService sqANDRService;

    public SdrManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        instance = this;
    }

    @Override
    public ManetType getType() { return ManetType.SDR; }

    public static SdrManet getInstance() { return instance; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        //TODO check for SDR connection
        passed = false;

        return passed;
    }

    @Override
    public int getMaximumPacketSize() { return MAX_PACKET_SIZE; /*TODO*/ }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //ignore
    }

    @Override
    public String getName() { return "Software Defined Radio"; }

    @Override
    public void init() throws ManetException {
        Log.d(TAG,"SDR Manet init()");
        isRunning.set(true);
        setStatus(Status.ADVERTISING_AND_DISCOVERING);
        connectToTeammates();
        //TODO
    }

    private boolean reportedAtMax = false;
    private void connectToTeammates() {
        Log.d(TAG,"connectToTeammates()");
        nextTeammateCheck = System.currentTimeMillis() + TIME_BETWEEN_TEAMMATE_CHECKS;
        //TODO
    }

    private void burst(final byte[] bytes, final int destination, final int origin) {
        if (bytes == null) {
            Log.d(TAG, "Cannot send empty byte array");
            return;
        }
        if (bytes.length > getMaximumPacketSize()) {
            Log.e(TAG, "Packet larger than " + getName() + " max; dropping packet");
        } else {
            handler.post(() -> {
                Log.d(TAG, "burst() - " + bytes.length + "b");
                //TODO burst over SqANDR
                ManetOps.addBytesToTransmittedTally(bytes.length);
                if (listener != null)
                    listener.onTx(bytes);
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
        instance = null;
        setStatus(Status.OFF);

        //TODO cleanup SqANDR

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
            //TODO remove unresponsive connections
            SqAnDevice.cullOldDevices();
        }

        //and look for new connections
        if (System.currentTimeMillis() > nextTeammateCheck)
            connectToTeammates();
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return false; }

    @Override
    public void onNewConnectionAccepted() {
        //TODO
    }

    @Override
    public void onError(Exception e, String where) {
        Log.d(TAG,"Socket, "+where+", acceptListener.onError(): "+e.getMessage());
        //TODO
    }

    @Override
    public void onConnectSuccess() {
        //TODO
        setStatus(Status.CONNECTED);
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
        setCurrent();
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

    @Override
    public boolean isRunning() {
        if (false /*TODO sqandr not running*/)
            return false;
        return super.isRunning();
    }

    private void setCurrent() {
        staleTime = System.currentTimeMillis() + INTERVAL_BEFORE_STALE;
    }

    /**
     * Is this manet stale (i.e. no traffic has been received recently)?
     * @return true == no traffic has been received recently
     */
    public boolean isStale() {
        return (System.currentTimeMillis() > staleTime);
    }
}
