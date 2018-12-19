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
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.ManetType;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

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
    public void burst(AbstractPacket packet) {
        if (packet == null)
            return; //nothing to send
        byte[] bytes = packet.toByteArray();
        if ((bytes == null) || (bytes.length < 2)) {
            Log.e(Config.TAG,"Unable to send packet; the ByteArray output was too small to be correct");
            return; //nothing to send but that seems like an error
        }
        List<String> devices = SqAnDevice.getActiveDevicesUuid();
        if (devices == null)
            return; //no one to send the burst to

        //This will broadcast to all active devices
        Nearby.getConnectionsClient(context).sendPayload(devices,Payload.fromBytes(bytes))
                .addOnFailureListener(e -> {
                    Log.e(Config.TAG,"Unable to send payload: "+e.getMessage());
                    status = Status.ERROR;
                    if (listener != null)
                        listener.onStatus(status);
                });
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
        stopAdvertising();
        stopDiscovery();
        isRunning = false;
    }

    private void stopAdvertising() {
        Nearby.getConnectionsClient(context).stopAdvertising();
    }

    private void stopDiscovery() {
        Nearby.getConnectionsClient(context).stopDiscovery();
    }

    private void startAdvertising() {
        AdvertisingOptions advertisingOptions =
                new AdvertisingOptions.Builder().setStrategy(P2P_CLUSTER).build();
        Nearby.getConnectionsClient(context)
                .startAdvertising(Config.getCallsign(), SERVICE_ID, connectionLifecycleCallback, advertisingOptions)
                .addOnSuccessListener(
                        (Void unused) -> {
                            setStatus(Status.ADVERTISING);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We're advertising!
                        })
                .addOnFailureListener(
                        (Exception e) -> {
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
                            setStatus(Status.DISCOVERING);
                            if (listener != null)
                                listener.onStatus(status);
                            // TODO We're discovering!
                        })
                .addOnFailureListener(
                        (Exception e) -> {
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
                    SqAnDevice device = SqAnDevice.find(deviceId);
                    handleDeviceConnection(device);
                    Log.d(Config.TAG, "Connection initiated with " + deviceId + "("+info.getEndpointName()+")");
                    setStatus(Status.CONNECTED);
                    //TODO add some security check here
                    Nearby.getConnectionsClient(context).acceptConnection(deviceId, payloadCallback);
                }

                @Override
                public void onConnectionResult(String deviceId, ConnectionResolution result) {
                    SqAnDevice device = SqAnDevice.find(deviceId);
                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: OK");
                            setStatus(Status.CONNECTED);
                            if (listener != null)
                                listener.onStatus(status);
                            handleDeviceConnection(device);
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Rejected");
                            if (device != null)
                                device.setDisconnected();
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Error");
                            if (device != null)
                                device.setDisconnected();
                            //TODO
                            break;
                        default:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Unknown");
                            if (device != null)
                                device.setDisconnected();
                            //TODO
                            break;
                    }
                }

                @Override
                public void onDisconnected(String deviceId) {
                    Log.d(Config.TAG, deviceId+" disconnected");
                    SqAnDevice device = SqAnDevice.find(deviceId);
                    if (device != null)
                        device.setDisconnected();
                    //TODO
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String deviceId, DiscoveredEndpointInfo info) {
                    Log.d(Config.TAG, "Found " +deviceId + "(" + info.getEndpointName() + ")");
                    SqAnDevice device = new SqAnDevice(deviceId,info.getEndpointName());
                    handleDeviceConnection(device);
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
                    Log.d(Config.TAG, "Lost " +deviceId);
                    SqAnDevice device = SqAnDevice.find(deviceId);
                    if (device != null) {
                        device.setDisconnected();
                        if (listener != null)
                            listener.onDevicesChanged(device);
                    }
                    setStatus(Status.CHANGING_MEMBERSHIP);
                }
            };

    private void handleDeviceConnection(SqAnDevice device) {
        if (device != null)
            device.setConnected();
    }

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String deviceId, @NonNull Payload payload) {
            switch (payload.getType()) {
                case Payload.Type.BYTES:
                    SqAnDevice device = SqAnDevice.find(deviceId);
                    if (device != null)
                        device.setConnected();
                    byte[] bytes = payload.asBytes();
                    if (bytes == null) {
                        Log.e(Config.TAG,"Empty payload received from "+deviceId);
                    } else {
                        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
                        if (packet == null)
                            Log.e(Config.TAG,"Unable to parse payload from "+deviceId);
                        else {
                            if (listener != null)
                                listener.onRx(packet);
                        }
                    }
                    break;

                    //TODO handle the File and Stream types
            }
        }

        @Override
        public void onPayloadTransferUpdate(@NonNull String s, @NonNull PayloadTransferUpdate payloadTransferUpdate) {
            //TODO handle transfer updates from File and Stream types
        }
    };
}