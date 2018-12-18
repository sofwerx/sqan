package org.sofwerx.sqan.manet.nearbycon;

import android.content.Context;
import android.util.Log;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.ManetType;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

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
        //TODO
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
                    Log.d(Config.TAG, "Connection initiated with " + deviceId + "("+info.getEndpointName()+")");
                    setStatus(Status.CONNECTED);
                    SqAnDevice device = new SqAnDevice(deviceId,info.getEndpointName());
                    boolean newDevice = SqAnDevice.add(device);
                    if (listener != null) {
                        listener.onStatus(status);
                        if (newDevice)
                            listener.onDevicesChanged(device);
                    }
                    //TODO
                }

                @Override
                public void onConnectionResult(String deviceId, ConnectionResolution result) {
                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: OK");
                            setStatus(Status.CONNECTED);
                            if (listener != null)
                                listener.onStatus(status);
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Rejected");
                            //TODO
                            break;
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Error");
                            //TODO
                            break;
                        default:
                            Log.d(Config.TAG, "Connection with " + deviceId+" result: Unknown");
                            //TODO
                            break;
                    }
                }

                @Override
                public void onDisconnected(String deviceId) {
                    Log.d(Config.TAG, deviceId+" disconnected");
                    //TODO
                }
            };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback =
            new EndpointDiscoveryCallback() {
                @Override
                public void onEndpointFound(String deviceId, DiscoveredEndpointInfo info) {
                    Log.d(Config.TAG, "Found " +deviceId + "(" + info.getEndpointName() + ")");
                    setStatus(Status.CONNECTED);
                    if (listener != null)
                        listener.onStatus(status);
                    //TODO
                }

                @Override
                public void onEndpointLost(String deviceId) {
                    Log.d(Config.TAG, "Lost " +deviceId);
                }
            };
}
