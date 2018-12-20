package org.sofwerx.sqan.manet.nearbycon;

import android.content.Context;
import android.support.annotation.NonNull;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.Connections;
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
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.ManetType;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.packet.AbstractPacket;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;
import java.util.List;

import static com.google.android.gms.nearby.connection.Strategy.P2P_CLUSTER;

/**
 * MANET built over the Google Nearby Connections API
 *  (https://developers.google.com/nearby/connections/overview)
 */
public class NearbyConnectionsManet extends AbstractManet {
    private static final String SERVICE_ID = "sqan";
    public NearbyConnectionsManet(Context context, ManetListener listener) { super(context,listener); }

    @Override
    public ManetType getType() { return ManetType.NEARBY_CONNECTION; }

    @Override
    public int getMaximumPacketSize() {
        return ConnectionsClient.MAX_BYTES_DATA_SIZE;
    }

    @Override
    public String getName() { return "Nearby Connections"; }

    @Override
    public void init() throws ManetException {
        if (!isRunning) {
            isRunning = true;
            startAdvertising();
            startDiscovery();
        }
    }

    @Override
    public void burst(final AbstractPacket packet) {
        Log.d(Config.TAG,"Attempting burst");
        if (packet == null)
            return; //nothing to send
        byte[] bytes = packet.toByteArray();
        if ((bytes == null) || (bytes.length < 2)) {
            CommsLog.log("Unable to send packet; the ByteArray output was too small to be correct");
            return; //nothing to send but that seems like an error
        }
        List<String> devices = SqAnDevice.getActiveDevicesNetworkIds();
        if (devices == null) {
            CommsLog.log("Packet intended to be sent, but there is no one else on the network to receive it.");
            return; //no one to send the burst to
        }

        //temp
        StringWriter outTemp = new StringWriter();
        boolean first = true;
        for (String deviceNetId:devices) {
            if (first)
                first = false;
            else
                outTemp.append(',');
            outTemp.append(deviceNetId);
        }
        Log.d(Config.TAG,"Sending burst to: "+outTemp.toString());
        //temp

        final int bytesSent = bytes.length;

        if (bytes.length > getMaximumPacketSize()) { //this packet is too big for Nearby Connnections to send in one piece
            CommsLog.log("Packet is too big for sending directly; segmenting...");
            segmentAndBurst(packet);
            return;
        }

        //This will broadcast to all active devices
        //TODO add some selective broadcast options later
        Nearby.getConnectionsClient(context).sendPayload(devices,Payload.fromBytes(bytes))
                .addOnSuccessListener(aVoid -> {
                    CommsLog.log(StringUtil.toDataSize(bytesSent)+" sent to "+devices.size()+((devices.size()==1)?" device":" devices"));
                    setStatus(Status.CONNECTED);
                    ManetOps.addBytesToTransmittedTally(bytesSent);
                    if (listener != null) {
                        listener.onStatus(status);
                        listener.onTx(packet);
                    }
                })
                .addOnFailureListener(e -> {
                    CommsLog.log("Unable to send payload: "+e.getMessage());
                    //status = Status.ERROR;
                    if (listener != null) {
                        listener.onTxFailed(packet);
                        listener.onStatus(status);
                    }
                });
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
        //TODO
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    @Override
    public void disconnect() throws ManetException {
        Nearby.getConnectionsClient(context).stopAdvertising();
        Nearby.getConnectionsClient(context).stopDiscovery();
        Nearby.getConnectionsClient(context).stopAllEndpoints();
        CommsLog.log("MANET disconnected");
        isRunning = false;
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(P2P_CLUSTER).build();
        Nearby.getConnectionsClient(context)
                .startAdvertising(Config.getUUID(), SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            CommsLog.log("Advertising");
                            setStatus(Status.ADVERTISING);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We're advertising!
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            CommsLog.log("Unable to Advertise: "+e.getMessage());
                            setStatus(Status.ERROR);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We were unable to start advertising.
                            try {
                                disconnect();
                            } catch (ManetException e1) {
                                e1.printStackTrace();
                            }
                        });
    }

    private void startDiscovery() {
        DiscoveryOptions discoveryOptions = new DiscoveryOptions.Builder().setStrategy(P2P_CLUSTER).build();
        Nearby.getConnectionsClient(context)
                .startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discoveryOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            CommsLog.log("Discovering");
                            setStatus(Status.DISCOVERING);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We're discovering!
                        })
                .addOnFailureListener(
                        (Exception e) -> {
                            CommsLog.log("Unable to start discovery: "+e.getMessage());
                            setStatus(Status.ERROR);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We're unable to start discovering.
                        });
    }

    private final ConnectionLifecycleCallback connectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String deviceId, ConnectionInfo info) {
                    Log.d(Config.TAG,"onConnectionInitiated with "+deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if ((device == null) && (info != null))
                        device = SqAnDevice.findByUUID(info.getEndpointName());
                    if (device != null)
                        device.setConnected();
                    CommsLog.log("Connection initiated with " + deviceId + "("+info.getEndpointName()+")");
                    setStatus(Status.CONNECTED);
                    //TODO add some security check here
                    Nearby.getConnectionsClient(context).acceptConnection(deviceId, payloadCallback);
                }

                @Override
                public void onConnectionResult(String deviceId, ConnectionResolution result) {
                    Log.d(Config.TAG,"onConnectionResult for "+deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            CommsLog.log("Connection with " + deviceId+" result: OK");
                            setStatus(Status.CONNECTED);
                            if (listener != null)
                                listener.onStatus(status);
                            if (device != null)
                                device.setConnected();
                            else
                                CommsLog.log("Connection reported for "+deviceId+" but that device is not on my roster.");
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                            CommsLog.log("Unable to connection with " + deviceId+" - Rejected");
                            if (device != null)
                                device.setStatus(SqAnDevice.Status.ERROR);
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            CommsLog.log( "Unable to connect with " + deviceId+" - Error");
                            if (device != null)
                                device.setStatus(SqAnDevice.Status.ERROR);
                            //TODO
                            break;
                        default:
                            CommsLog.log("Connection with " + deviceId+" result: Unknown");
                            if (device != null)
                                device.setStatus(SqAnDevice.Status.ERROR);
                            //TODO
                            break;
                    }
                }

                @Override
                public void onDisconnected(String deviceId) {
                    CommsLog.log(deviceId+" disconnected");
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device != null)
                        device.setStatus(SqAnDevice.Status.OFFLINE);
                    //TODO
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String deviceId, DiscoveredEndpointInfo info) {
                    CommsLog.log("Found " +deviceId + "(" + info.getEndpointName() + ")");
                    SqAnDevice device = new SqAnDevice(info.getEndpointName(),deviceId);
                    device.setStatus(SqAnDevice.Status.ONLINE);
                    Nearby.getConnectionsClient(context)
                            .requestConnection("test",deviceId,connectionLifecycleCallback);
                    //TODO        .requestConnection(Config.getUUID(),deviceId,connectionLifecycleCallback);
                    boolean newDevice = SqAnDevice.add(device);
                    if (listener != null) {
                        listener.onStatus(status);
                        if (newDevice)
                            listener.onDevicesChanged(device);
                    }
                    setStatus(Status.CHANGING_MEMBERSHIP);
                    if (listener != null)
                        listener.onStatus(status);
                    //TODO
                }

                @Override
                public void onEndpointLost(String deviceId) {
                    CommsLog.log("Lost " +deviceId);
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device != null) {
                        device.setStatus(SqAnDevice.Status.OFFLINE);
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
                CommsLog.log("null payload received from " + deviceId);
                return;
            }
            setStatus(Status.CONNECTED);
            if (listener != null)
                listener.onStatus(status);
            switch (payload.getType()) {
                case Payload.Type.BYTES:
                    SqAnDevice device = SqAnDevice.findByNetworkID(deviceId);
                    if (device == null) {
                        CommsLog.log("Received a packet from "+deviceId+", but that device was not on my roster");
                        return;
                    }
                    device.setConnected();
                    byte[] bytes = payload.asBytes();
                    if (bytes == null) {
                        CommsLog.log("Empty payload received from "+deviceId);
                    } else {
                        device.addToDataTally(bytes.length);
                        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
                        if (packet == null)
                            CommsLog.log("Unable to parse payload from "+deviceId);
                        else {
                            CommsLog.log("Received "+StringUtil.toDataSize(bytes.length)+" packet (Byte type payload) from "+deviceId);
                            if (listener != null)
                                listener.onRx(packet);
                        }
                    }
                    break;

                default:
                    CommsLog.log("Payload type "+payload.getType()+" received from "+deviceId+" but SqAN is not equipped to process that type yet.");
                    //TODO handle the File and Stream types
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
            //TODO handle transfer updates from File and Stream types
        }
    };
}