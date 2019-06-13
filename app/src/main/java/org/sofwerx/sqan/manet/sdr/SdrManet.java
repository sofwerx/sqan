package org.sofwerx.sqan.manet.sdr;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqandr.SqANDRListener;
import org.sofwerx.sqandr.SqANDRService;
import org.sofwerx.sqandr.sdr.DataConnectionListener;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.serial.SerialConnection;

/**
 * MANET built for including hops over SDR
 *
 */
public class SdrManet extends AbstractManet implements SqANDRListener {
    private static final int MAX_PACKET_SIZE = Segmenter.MAX_POSSIBLE_LENGTH;
    private final static long INTERVAL_BEFORE_STALE = 1000l * 60l;
    private static final long TIME_BETWEEN_TEAMMATE_CHECKS = 1000l * 15l;
    private static final long OLD_DEVICE_CHECK_INTERVAL = 1000l * 60l;
    private static final int MAX_HOP_COUNT = 4; //max number of times a message should be relayed
    private final static String TAG = Config.TAG+".Sdr";
    private long nextOldDeviceCheck = Long.MIN_VALUE;
    private static SdrManet instance;
    private long staleTime = Long.MIN_VALUE;
    private SqANDRService sqANDRService;

    public SdrManet(Handler handler, Context context, ManetListener listener) {
        super(handler, context,listener);
        sqANDRService = new SqANDRService(context,this);
        instance = this;
    }

    @Override
    public ManetType getType() { return ManetType.SDR; }

    public static SdrManet getInstance() { return instance; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        passed = true; //TODO actually check for SDR issues
        return passed;
    }

    @Override
    public int getMaximumPacketSize() { return MAX_PACKET_SIZE; }

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
        setStatus(Status.OFF);
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
                sqANDRService.burst(bytes);
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
        if (sqANDRService != null) {
            sqANDRService.shutdown();
            sqANDRService = null;
        }
        setStatus(Status.OFF);

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
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return false; }

    @Override
    public void onSdrError(String message) { Log.e(TAG,"Read Error: "+message); }

    @Override
    public void onSdrReady(boolean isReady) {
        if (isReady)
            setStatus(Status.CONNECTED);
        else
            setStatus(Status.OFF);
    }

    @Override
    public void onSdrMessage(String message) {
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,message);
    }

    @Override
    public void onPacketReceived(byte[] data) {
        if (data == null)
            return;
        AbstractPacket packet = AbstractPacket.newFromBytes(data);
        if (packet == null) {
            Log.w(TAG,"Unable to parse data, packet dropped");
            onPacketDropped();
            return;
        }
        setCurrent();
        onReceived(packet);
    }

    @Override
    public void onPacketDropped() {
        CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Error parsing data, packet dropped");
    }

    @Override
    public boolean isRunning() {
        if (sqANDRService == null)
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

    public void setTerminal(DataConnectionListener terminal) {
        if (sqANDRService != null)
            sqANDRService.setDataConnectionListener(terminal);
    }

    public SerialConnection getSerialConnection() {
        if (sqANDRService == null)
            return null;
        return sqANDRService.getSerialConnection();
    }
}
