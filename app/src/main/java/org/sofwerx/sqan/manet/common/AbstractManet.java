package org.sofwerx.sqan.manet.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.bt.BtManetV2;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.PingPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.nearbycon.NearbyConnectionsManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.SegmentTool;
import org.sofwerx.sqan.manet.wifiaware.WiFiAwareManet;
import org.sofwerx.sqan.manet.wifidirect.WiFiDirectManet;
import org.sofwerx.sqan.util.CommsLog;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Abstract class that handles all broad MANET activity. This abstracts away any MANET specific
 * implementation issues and lets SqAN deal with all MANETs in a uniform manner.
 */
public abstract class AbstractManet {
    protected Status status = Status.OFF;
    protected ManetListener listener;
    protected AtomicBoolean isRunning = new AtomicBoolean(false);
    protected final Context context;
    protected final Handler handler;
    protected final PacketParser parser;

    //TODO look to add support for the WiFi Round Trip Timing API for spacing
    //https://developer.android.com/guide/topics/connectivity/wifi-rtt

    /**
     * Constructor for MANET
     * @param handler the handler for the thread where this MANET should run be default or null for the main thread (not advised)
     * @param context
     * @param listener
     */
    public AbstractManet(Handler handler, Context context, ManetListener listener) {
        this.handler = handler;
        this.context = context;
        this.listener = listener;
        SegmentTool.setMaxPacketSize(getMaximumPacketSize());
        parser = new PacketParser(this);
    }

    /**
     * Gets they type of MANET in use (i.e. Nearby Connections, WiFi Aware, WiFi Direct
     * @return
     */
    public abstract ManetType getType();

    /**
     * Checks for any issues blocking or impeding MANET
     * @return true == some issue exists effecting the MANET
     */
    public boolean checkForSystemIssues() {
        boolean passed = true;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            SqAnService.onIssueDetected(new WiFiIssue(true,"WiFi absent"));
            passed = false;
        }
        return passed;
    }

    public abstract int getMaximumPacketSize();

    /**
     * Intended to support more efficient radio operations by allowing devices to stop
     * advertising/discovering one the group has been adequately formed.
     * @param newNodesAllowed
     */
    public abstract void setNewNodesAllowed(boolean newNodesAllowed);

    public final static AbstractManet newFromType(Handler handler, Context context, ManetListener listener, ManetType type) {
        switch (type) {
            case NEARBY_CONNECTION:
                return new NearbyConnectionsManet(handler, context, listener);

            case WIFI_AWARE:
                return new WiFiAwareManet(handler, context, listener);

            case WIFI_DIRECT:
                return new WiFiDirectManet(handler, context, listener);

            case BT_ONLY:
                return new BtManetV2(handler, context, listener);

            default:
                return null;
        }
    }

    public abstract String getName();
    public boolean isRunning() { return isRunning.get(); }

    public void setListener(ManetListener listener) { this.listener = listener; }

    /**
     * Do any initialization required to move the MANET into a pause state. This may include initial
     * interactions with other nodes in the network.
     */
    public abstract void init() throws ManetException;

    public void setStatus(Status status) {
        boolean changed = false;
        switch (status) {
            case ADVERTISING:
                if ((this.status == Status.DISCOVERING) || (this.status == Status.ADVERTISING_AND_DISCOVERING))
                    this.status = Status.ADVERTISING_AND_DISCOVERING;
                else {
                    this.status = Status.ADVERTISING;
                    changed = true;
                }
                break;

            case DISCOVERING:
                if ((this.status == Status.ADVERTISING) || (this.status == Status.ADVERTISING_AND_DISCOVERING))
                    this.status = Status.ADVERTISING_AND_DISCOVERING;
                else {
                    this.status = Status.DISCOVERING;
                    changed = true;
                }
                break;

            default:
                changed = (this.status != status);
                this.status = status;
        }
        if (changed && (listener != null))
            listener.onStatus(this.status);
    }

    /**
     * Send a pack over the MANET
     * @param packet
     */
    public abstract void burst(AbstractPacket packet) throws ManetException;

    /**
     * Connect to the MANET (i.e. start communicating with other nodes on the network)
     */
    public abstract void connect() throws ManetException;

    /**
     * Pause communication with the MANET (i.e. stop communicating with other nodes on the network but
     * keep the connection warm)
     */
    public abstract void pause() throws ManetException;

    /**
     * Move from a paused state back to a connected state (i.e. resume communicating with the MANET)
     */
    public abstract void resume() throws ManetException;

    /**
     * Disconnect from the MANET (i.e. stop/shutdown - release any resources needed to connect with
     * the MANET)
     */
    public void disconnect() throws ManetException {
        isRunning.set(false);
    }

    public void onReceived(AbstractPacket packet) {
        if (packet == null)
            Log.d(Config.TAG,"Empty packet received over "+getClass());
        if ((packet.getOrigin() == Config.getThisDevice().getUUID()) && !(packet instanceof PingPacket)) {
            Log.d(Config.TAG,"Circular reporting detected - dropping packet");
            return;
        }
        setStatus(Status.CONNECTED);
        SqAnDevice device = SqAnDevice.findByUUID(packet.getOrigin());
        if (packet.isDirectFromOrigin()) {
            if (packet instanceof PingPacket) {
                if ((packet.getOrigin() == PacketHeader.BROADCAST_ADDRESS) || (packet.getSqAnDestination() == PacketHeader.BROADCAST_ADDRESS)) {
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "PingPacket cannot be addressed to or from the BROADCAST SqAnAddress. Packet dropped.");
                    return;
                }
                PingPacket pingPacket = (PingPacket) packet;
                if (pingPacket.isAPingRequest() || (pingPacket.getOrigin() != Config.getThisDevice().getUUID())) {
                    pingPacket.setDestination(device.getUUID());
                    CommsLog.log(CommsLog.Entry.Category.COMMS, "Received ping request from " + device.getUUID());
                    pingPacket.setMidpointLocalTime(System.currentTimeMillis());
                    try {
                        burst(pingPacket);
                    } catch (ManetException e) {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Error handling Ping request " + e.getMessage());
                        return;
                    }
                } else {
                    device.addLatencyMeasurement(pingPacket.getLatency());
                    CommsLog.log(CommsLog.Entry.Category.COMMS, "Received ping (round trip latency " + Long.toString(pingPacket.getLatency()) + "ms) from " + device.getUUID());
                }
                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Operating normally"));
                device.setConnected(0, isBluetoothBased(), isWiFiBased()); //direct, no hops in between
                SavedTeammate teammate = Config.getTeammate(device.getUUID());
                if (teammate != null)
                    teammate.update(device.getCallsign(), System.currentTimeMillis());
                if (listener != null)
                    listener.updateDeviceUi(device);
                return;
            }
        }
        if (device == null) {
            String callsign = null;
            if (packet instanceof HeartbeatPacket) {
                HeartbeatPacket hb = (HeartbeatPacket)packet;
                if (hb.getDevice() != null)
                    callsign = hb.getDevice().getCallsign();
            }
            if (callsign == null)
                callsign = "SqAN ID "+Integer.toString(packet.getOrigin());
            CommsLog.log(CommsLog.Entry.Category.COMMS,"Connected to device "+callsign);
            if (packet.getOrigin() > 0) {
                device = new SqAnDevice(packet.getOrigin());
                SavedTeammate saved = Config.getTeammate(packet.getOrigin());
                if (saved != null)
                    device.setCallsign(saved.getCallsign());
                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Operating normally"));
                if (packet.getCurrentHopCount() == 0)
                    device.setConnected(0,isBluetoothBased(),isWiFiBased());
                else
                    device.setConnected(packet.getCurrentHopCount(),false,false);
                if (listener != null)
                    listener.onDevicesChanged(device);
            }
        } else {
            if (packet instanceof HeartbeatPacket)
                device.update(((HeartbeatPacket)packet).getDevice());
            device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Operating normally"));
            if (packet.getCurrentHopCount() == 0)
                device.setConnected(0,isBluetoothBased(),isWiFiBased());
            else
                device.setConnected(packet.getCurrentHopCount(),false,false);
            if (listener != null)
                listener.onDevicesChanged(device);
        }
        if (device != null) {
            SavedTeammate teammate = Config.getTeammate(device.getUUID());
            if (teammate != null)
                teammate.update(device.getCallsign(), System.currentTimeMillis());
            if (listener != null)
                listener.updateDeviceUi(device);
            if (listener != null)
                listener.onRx(packet);
            if (packet instanceof DisconnectingPacket)
                onDeviceLost(device, packet.isDirectFromOrigin());
        }
    }

    protected abstract boolean isBluetoothBased();
    protected abstract boolean isWiFiBased();

    /**
     * This device's hub (if this MANET is in a spoke/hub architecture) has become disconnected
     */
    protected abstract void onDeviceLost(SqAnDevice device, boolean directConnection);

    public Status getStatus() { return status; }

    /**
     * A periodically executed method to help with any link housekeeping issues
     */
    public abstract void executePeriodicTasks();

    public ManetListener getListener() { return listener; };

    public void onAuthenticatedOnNet() {
        if (listener != null)
            listener.onAuthenticatedOnNet();
    }
}