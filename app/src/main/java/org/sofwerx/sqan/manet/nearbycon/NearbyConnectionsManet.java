package org.sofwerx.sqan.manet.nearbycon;

import androidx.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PingPacket;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.google.android.gms.nearby.connection.Strategy.P2P_CLUSTER;

/**
 * MANET built over the Google Nearby Connections API
 *  (https://developers.google.com/nearby/connections/overview)
 */
public class NearbyConnectionsManet extends AbstractManet {
    private static final String SERVICE_ID = "sqan";
    public NearbyConnectionsManet(android.os.Handler handler, android.content.Context context, ManetListener listener) { super(handler,context,listener); }

    private HashMap<String,Long> connectionQueue = new HashMap<>(); //attempted work-around for problems Nearby Connections seems to have in connecting

    //Experimental strategies to try to enhance MANET stability
    private final static boolean TACTIC_REPEATED_CONNETION_TRIES = true;
    private final static long CONNECTION_RETRY_RATE = 1000l * 15l;

    //Nearby Connections configurations;
    private final static AdvertisingOptions ADVERTISING_OPTIONS =
            new AdvertisingOptions.Builder().setStrategy(P2P_CLUSTER).build();
    private final static DiscoveryOptions DISCOVERY_OPTIONS =
            new DiscoveryOptions.Builder().setStrategy(P2P_CLUSTER).build();

    @Override
    public ManetType getType() { return ManetType.NEARBY_CONNECTION; }

    @Override
    public boolean checkForSystemIssues() {
        boolean passed = super.checkForSystemIssues();
        return passed;
    }

    @Override
    public int getMaximumPacketSize() {
        return ConnectionsClient.MAX_BYTES_DATA_SIZE;
    }

    @Override
    public boolean isCongested() {
        //FIXME
        return false;
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO toggle advertise/discovery
    }

    @Override
    public String getName() { return "Nearby Connections"; }

    @Override
    public void init() throws ManetException {
        //if (!isRunning) {
            isRunning.set(true);
            startAdvertising();
            startDiscovery();
        //}
    }

    @Override
    public void burst(final AbstractPacket packet) {
        if (packet == null)
            return; //nothing to send
        byte[] bytes = packet.toByteArray();
        if ((bytes == null) || (bytes.length < 2)) {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to send packet; the ByteArray output was too small to be correct");
            return; //nothing to send but that seems like an error
        }
        final List<String> devices;
            devices = SqAnDevice.getActiveDevicesNetworkIds();
        if (devices == null) {
            CommsLog.log(CommsLog.Entry.Category.COMMS, "Packet intended to be sent, but I am not connected to any devices yet.");
            return; //no one to send the burst to
        }

        //temp
            StringWriter outTemp = new StringWriter();
            boolean first = true;
            for (String deviceNetId : devices) {
                if (first)
                    first = false;
                else
                    outTemp.append(',');
                outTemp.append(deviceNetId);
            }
            Log.d(Config.TAG, "Sending burst to: " + outTemp.toString());
        //temp

        final int bytesSent = bytes.length;

        if (bytes.length > getMaximumPacketSize()) { //this packet is too big for Nearby Connections to send in one piece
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Packet is too big for sending directly; segmenting...");
            segmentAndBurst(packet);
            return;
        }

            //This will broadcast to all active devices
            Nearby.getConnectionsClient(context.toAndroid()).sendPayload(devices, Payload.fromBytes(bytes))
                    .addOnSuccessListener(aVoid -> {
                        CommsLog.log(CommsLog.Entry.Category.COMMS, StringUtil.toDataSize(bytesSent) + " sent to " + devices.size() + ((devices.size() == 1) ? " device" : " devices"));
                        setStatus(Status.CONNECTED);
                        ManetOps.addBytesToTransmittedTally(bytesSent);
                        if (listener != null)
                            listener.onTx(packet);
                    })
                    .addOnFailureListener(e -> {
                        String errorMessage = e.getMessage();
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to send payload: " + errorMessage);
                        //status = Status.ERROR;
                        if (listener != null) {
                            listener.onTxFailed(packet);
                            listener.onStatus(status);
                        }
                        if ((errorMessage != null) && (errorMessage.contains("ENDPOINT_UNKNOWN"))) {
                            if (SqAnDevice.cullOldDevices() && (listener != null))
                                listener.onDevicesChanged(null);
                        }
                    });
            //This will broadcast to one device
            /*Nearby.getConnectionsClient(context).sendPayload(device.getNetworkId(), Payload.fromBytes(bytes))
                    .addOnSuccessListener(aVoid -> {
                        CommsLog.log(CommsLog.Entry.Category.COMMS, StringUtil.toDataSize(bytesSent) + " sent to " + device.getNetworkId());
                        setStatus(Status.CONNECTED);
                        ManetOps.addBytesToTransmittedTally(bytesSent);
                        if (listener != null)
                            listener.onTx(packet);
                    })
                    .addOnFailureListener(e -> {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to send payload: " + e.getMessage());
                        //status = Status.ERROR;
                        if (listener != null) {
                            listener.onTxFailed(packet);
                            listener.onStatus(status);
                        }
                    });
        }*/
    }

    private void segmentAndBurst(AbstractPacket packet) {
        Log.d(Config.TAG,"This packet is too big for Nearby Connections; since there is no segmentation capability yet, this packet is being dropped.");
        //TODO break up this large packet and send it as smaller packets
    }

    @Override
    public void connect() throws ManetException {
        //TODO
    }

    @Override
    public void pause() throws ManetException {
        disconnect(); //TODO possibly implement a warm solution rather than disconnecting
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    @Override
    public void disconnect() throws ManetException {
        Nearby.getConnectionsClient(context.toAndroid()).stopAdvertising();
        Nearby.getConnectionsClient(context.toAndroid()).stopDiscovery();
        Nearby.getConnectionsClient(context.toAndroid()).stopAllEndpoints();
        CommsLog.log(CommsLog.Entry.Category.STATUS, "MANET disconnected");
        setStatus(Status.OFF);
        isRunning.set(false);
    }

    @Override
    protected boolean isBluetoothBased() { return false; }

    @Override
    protected boolean isWiFiBased() { return true; }

    @Override
    protected void onDeviceLost(SqAnDevice device, boolean directConnection) {
        setNewNodesAllowed(true); //TODO make this more sophisticated than just turning discovery back on
    }

    @Override
    public void executePeriodicTasks() {
        if (TACTIC_REPEATED_CONNETION_TRIES && (connectionQueue != null) && !connectionQueue.isEmpty()) {
            Iterator it = connectionQueue.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry pair = (Map.Entry)it.next();
                if (System.currentTimeMillis() > (Long)pair.getValue()) {
                    String deviceId = (String)pair.getKey();
                    if (deviceId == null)
                        it.remove();
                    else {
                        SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                        if ((device == null) || device.isActive())
                            it.remove();
                        else {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Attempting to connect to " + deviceId + " again");
                            Nearby.getConnectionsClient(context.toAndroid())
                                    .requestConnection(Integer.toString(Config.getThisDevice().getUUID()), deviceId, connectionLifecycleCallback);
                            connectionQueue.put(deviceId, System.currentTimeMillis() + CONNECTION_RETRY_RATE);
                        }
                    }
                }
            }
        }
    }

    private void startAdvertising() {
        Nearby.getConnectionsClient(context.toAndroid())
                .startAdvertising(Integer.toString(Config.getThisDevice().getUUID()), SERVICE_ID, connectionLifecycleCallback, ADVERTISING_OPTIONS)
                .addOnSuccessListener(
                        (Void unused) -> {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Advertising");
                            setStatus(Status.ADVERTISING);
                            if (listener != null)
                                listener.onStatus(status);
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to Advertise: "+e.getMessage());
                            setStatus(Status.ERROR);
                            if (listener != null)
                                listener.onStatus(status);
                            try {
                                disconnect();
                            } catch (ManetException e1) {
                                e1.printStackTrace();
                            }
                        });
    }

    private void startDiscovery() {
        Nearby.getConnectionsClient(context.toAndroid())
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, DISCOVERY_OPTIONS)
                .addOnSuccessListener(
                        (Void unused) -> {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Discovering");
                            setStatus(Status.DISCOVERING);
                            if (listener != null)
                                listener.onStatus(status);
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to start discovery: "+e.getMessage());
                            setStatus(Status.ERROR);
                            if (listener != null)
                                listener.onStatus(status);
                        });
    }

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String deviceId, ConnectionInfo info) {
                    Log.d(Config.TAG,"onConnectionInitiated with "+deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if ((device == null) && (info != null)) {
                        try {
                            device = SqAnDevice.findByUUID(Integer.parseInt(info.getEndpointName()));
                        } catch (NumberFormatException ignore) {
                        }
                    } else {
                        if (device.getNetworkId() == null)
                            device.setNetworkId(deviceId);
                    }
                    if (device != null) {
                        device.setConnected(0,isBluetoothBased(),isWiFiBased()); //direct, no hops in between
                        device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Connection initiated"));
                    }
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "Connection initiated with " + deviceId + "("+info.getEndpointName()+")");
                    setStatus(Status.CONNECTED);
                    //TODO add some security check here
                    Nearby.getConnectionsClient(context.toAndroid()).acceptConnection(deviceId, payloadCallback);
                }

                @Override
                public void onConnectionResult(String deviceId, ConnectionResolution result) {
                    Log.d(Config.TAG,"onConnectionResult for "+deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            setStatus(Status.CONNECTED);
                            if (listener != null) {
                                listener.onStatus(status);
                                listener.onDevicesChanged(device);
                            }
                            if (device != null) {
                                device.setConnected(0,isBluetoothBased(),isWiFiBased()); //direct, no hops in between
                                String lagString = "discovery to connection "+StringUtil.toDuration(device.getDiscoveryConnectLag());
                                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, lagString));
                                CommsLog.log(CommsLog.Entry.Category.STATUS, "Connection established with " + deviceId+", "+lagString);
                            } else
                                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Connection reported for "+deviceId+" but that device is not on my roster.");
                            if (TACTIC_REPEATED_CONNETION_TRIES)
                                connectionQueue.remove(deviceId);
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to connection with " + deviceId+" - Rejected");
                            if (device != null) {
                                device.setStatus(SqAnDevice.Status.ERROR);
                                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Connection rejected"));
                            }
                            if (TACTIC_REPEATED_CONNETION_TRIES)
                                connectionQueue.remove(deviceId);
                            break;
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM,  "Unable to connect with " + deviceId+" - Error");
                            if (device != null) {
                                device.setStatus(SqAnDevice.Status.ERROR);
                                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Error connecting (NFI)"));
                            }
                            if (TACTIC_REPEATED_CONNETION_TRIES) {
                                CommsLog.log(CommsLog.Entry.Category.PROBLEM,  "Trying to resent connection by disconnecting from " + deviceId);
                                Nearby.getConnectionsClient(context.toAndroid()).disconnectFromEndpoint(deviceId);
                                connectionQueue.put(deviceId,System.currentTimeMillis() + CONNECTION_RETRY_RATE);
                            }
                            break;
                        default:
                            String resultText = ConnectionsStatusCodes.getStatusCodeString(result.getStatus().getStatusCode());
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Connection with " + deviceId+" result code: "+resultText);
                            if (device != null) {
                                device.setStatus(SqAnDevice.Status.ERROR);
                                device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Error: "+resultText));
                            }
                            break;
                    }
                }

                @Override
                public void onDisconnected(String deviceId) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, deviceId+" disconnected");
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device != null) {
                        device.setStatus(SqAnDevice.Status.OFFLINE);
                        device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Disconnected"));
                        listener.onDevicesChanged(device);
                    }
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String deviceId, DiscoveredEndpointInfo info) {
                    if (deviceId == null) {
                        Log.d(Config.TAG,"null endpoint found by Nearby Connections; that does not make any sense so ignoring this deviceId");
                        return;
                    }
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "Found " +deviceId + "(" + info.getEndpointName() + ")");
                    int uuid = SqAnDevice.UNASSIGNED_UUID;
                    try {
                        if (info.getEndpointName() != null)
                            uuid = Integer.parseInt(info.getEndpointName());
                    } catch (NumberFormatException e) {
                    }
                    SqAnDevice device = SqAnDevice.findByUUID(uuid);
                    if (device == null)
                        device = new SqAnDevice(uuid);
                    device.setNetworkId(deviceId);
                    device.setStatus(SqAnDevice.Status.ONLINE);
                    device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Device found"));

                    Nearby.getConnectionsClient(context.toAndroid())
                            .requestConnection(Integer.toString(Config.getThisDevice().getUUID()),deviceId,connectionLifecycleCallback);
                    if (TACTIC_REPEATED_CONNETION_TRIES)
                        connectionQueue.put(deviceId,System.currentTimeMillis() + CONNECTION_RETRY_RATE);
                    SqAnDevice.add(device);
                    if (listener != null) {
                        listener.onStatus(status);
                        listener.onDevicesChanged(device);
                    }
                    setStatus(Status.CHANGING_MEMBERSHIP);
                    if (listener != null)
                        listener.onStatus(status);
                    //TODO
                }

                @Override
                public void onEndpointLost(String deviceId) {
                    if (deviceId == null) {
                        Log.d(Config.TAG,"null endpoint lost by Nearby Connections; that does not make any sense so ignoring this deviceId");
                        return;
                    }
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Lost " +deviceId);
                    if (TACTIC_REPEATED_CONNETION_TRIES)
                        connectionQueue.remove(deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device != null) {
                        device.setStatus(SqAnDevice.Status.OFFLINE);
                        device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Connection lost"));
                        if (listener != null)
                            listener.onDevicesChanged(device);
                    }
                    setStatus(Status.CHANGING_MEMBERSHIP);
                }
            };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String deviceId, @NonNull Payload payload) {
            if (payload == null) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "null payload received from " + deviceId);
                return;
            }
            setStatus(Status.CONNECTED);
            if (listener != null)
                listener.onStatus(status);
            switch (payload.getType()) {
                case Payload.Type.BYTES:
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device == null) {
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Received a packet from "+deviceId+", but that device was not on my roster so I added it");
                        device = new SqAnDevice();
                        device.setNetworkId(deviceId);
                    }
                    device.setConnected(0,isBluetoothBased(),isWiFiBased()); //direct, no hops in between
                    byte[] bytes = payload.asBytes();
                    if (bytes == null) {
                        device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Empty payload received"));
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Empty payload received from "+deviceId);
                    } else {
                        device.addToDataTally(bytes.length);
                        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
                        if (packet == null) {
                            device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.PROBLEM, "Unable to processPacketAndNotifyManet last payload"));
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Unable to processPacketAndNotifyManet payload from " + deviceId);
                        } else {
                            if (packet instanceof PingPacket) {
                                PingPacket pingPacket = (PingPacket)packet;
                                if (pingPacket.isAPingRequest()) {
                                    CommsLog.log(CommsLog.Entry.Category.COMMS, "Received ping request from " + deviceId);
                                    pingPacket.setMidpointLocalTime(System.currentTimeMillis());
                                    burst(pingPacket);
                                } else {
                                    device.addLatencyMeasurement(pingPacket.getLatency());
                                    CommsLog.log(CommsLog.Entry.Category.COMMS, "Received ping (round trip latency "+Long.toString(pingPacket.getLatency())+"ms) from " + deviceId);
                                }
                            } else
                                CommsLog.log(CommsLog.Entry.Category.COMMS, "Received "+StringUtil.toDataSize(bytes.length)+" packet (Byte type payload) from "+deviceId);
                            device.setLastEntry(new CommsLog.Entry(CommsLog.Entry.Category.STATUS, "Operating normally"));
                            onReceived(packet);
                        }
                    }
                    break;

                default:
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Payload type "+payload.getType()+" received from "+deviceId+" but SqAN is not equipped to process that type yet.");
                    //TODO handle the File and Stream types
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
            //TODO handle transfer updates from File and Stream types
        }
    };

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        //ignore
    }
}