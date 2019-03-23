package org.sofwerx.sqan.ipc;

import android.content.Context;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;

import java.util.ArrayList;

public class IpcSaBroadcastTransmitter {
    private final static boolean BROADCAST_LINKS = true;

    public static void broadcast(Context context) {
        SqAnDevice thisDevice = Config.getThisDevice();
        if (thisDevice == null) {
            Log.w(Config.TAG+".Tx","Cannot transmit the SqAN SA broadcast as this device is not yet known (this should not happen)");
            return;
        }
        SpaceTime lastLocation = thisDevice.getLastLocation();
        if ((lastLocation == null) || !lastLocation.isValid()) {
            Log.d(Config.TAG+".Tx","Cannot transmit the SqAN SA broadcast as this device's location is not known");
            return;
        }
        int originatorId = thisDevice.getUUID();
        BftBroadcast broadcast = new BftBroadcast();
        broadcast.add(new BftDevice(thisDevice.getUUID(),thisDevice.getCallsign(),lastLocation.getLatitude(),
                lastLocation.getLongitude(),lastLocation.getAltitude(),lastLocation.getAccuracy(),lastLocation.getTime(),false,false,null));

        ArrayList<SqAnDevice> otherDevices = SqAnDevice.getDevices();
        if (otherDevices != null) {
            for (SqAnDevice other:otherDevices) {
                if (other != null) {
                    lastLocation = other.getLastLocation();
                    if ((lastLocation != null) && lastLocation.isValid()) {
                        broadcast.add(new BftDevice(other.getUUID(), other.getCallsign(), lastLocation.getLatitude(),
                                lastLocation.getLongitude(), lastLocation.getAltitude(), lastLocation.getAccuracy(), lastLocation.getTime(), other.isDirectBt(), other.isDirectWiFi(),BROADCAST_LINKS?other.getLinks():null));
                    } else
                        broadcast.add(new BftDevice(other.getUUID(), other.getCallsign(), other.getLastConnect(),other.isDirectBt(),other.isDirectWiFi(),BROADCAST_LINKS?other.getLinks():null));
                }
            }
        }

        //Log.d(Config.TAG+".Tx","Sharing (with other apps on this device) SA information on "+broadcast.getNumberOfDevices()+" devices");
        IpcBroadcastTransceiver.broadcast(context,BftBroadcast.BFT_CHANNEL,originatorId,broadcast.toBytes());
    }
}
