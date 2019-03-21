package org.sofwerx.sqantest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

import org.sofwerx.sqan.ipc.BftBroadcast;
import org.sofwerx.sqan.ipc.BftDevice;

import java.util.ArrayList;

/**
 * Simple mechanism to support SqAN broadcasts based on IPC from other apps. To send/receive
 * data over SqAN, another app will register a listener (to receive) and then use broadcast()
 * to send the data to SqAN.
 */
public class IpcBroadcastTransceiver extends BroadcastReceiver {
    private final static String BROADCAST_PKT = "org.sofwerx.sqan.pkt";
    private final static String PACKET_BYTES = "bytes";
    private final static String PACKET_ORIGIN = "src";
    private final static String PACKET_CHANNEL = "channel";
    private final static String RECEIVED = "rcv";
    private final static String CHAT_CHANNEL = "chat";
    private static IpcBroadcastTransceiver receiver = null;
    private static boolean isSqAn;
    private static BftBroadcast broadcast = new BftBroadcast();
    private static int deviceIndex = 0;
    private static IpcListener listener;

    public static void setListener(IpcListener listener) { IpcBroadcastTransceiver.listener = listener; }

    public interface IpcListener {
        void onChatPacketReceived(int origin, byte[] data);
        void onSaBroadcastReceived(BftBroadcast broadcast);
    }

    public static void register(Context context) {
        if (context != null) {
            Log.i("SqAN","IpcBroadcastTransceiver registered to "+context.getClass().getSimpleName());
            if (receiver != null)
                receiver.unregister(context);
            receiver = new IpcBroadcastTransceiver();
            context.registerReceiver(receiver, new IntentFilter(BROADCAST_PKT));
            if (context instanceof IpcListener)
                listener = (IpcListener)context;
        }
        isSqAn = false;
    }

    public static void unregister(Context context) {
        try {
            if ((context != null) && (receiver != null)) {
                try {
                    context.unregisterReceiver(receiver);
                    receiver = null;
                } catch (IllegalArgumentException e) {
                    Log.e("SqAn", "Unable to unregister SqAN IPC transceiver: " + e.getMessage());
                }
                Log.i("SqAN", "IpcBroadcastTransceiver unregistered by " + context.getClass().getSimpleName());
            }
        } catch (Exception ignore) {
        }
    }

    // resets the index, gets this device, and advances the index by 1
    public static BftDevice resetRetrievalIndex() {
        deviceIndex = 0;
        return getDeviceAndAdvanceIndex();
    }

    public static BftDevice getDeviceAndAdvanceIndex() {
        synchronized (broadcast) {
            ArrayList<BftDevice> devices = broadcast.getDevices();
            if ((deviceIndex >= 0) && (deviceIndex < devices.size())) {
                int indexToGet = deviceIndex;
                deviceIndex++;
                return devices.get(indexToGet);
            }
        }
        return null;
    }

    public static void broadcastChat(Context context, byte[] bytes) {
        if ((context != null) && (bytes != null)) {
            Intent intent = new Intent(BROADCAST_PKT);
            intent.putExtra(PACKET_BYTES,bytes);
            intent.putExtra(PACKET_CHANNEL,CHAT_CHANNEL);
            if (isSqAn)
                intent.putExtra(RECEIVED,true);
            context.sendBroadcast(intent);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            if (BROADCAST_PKT.equalsIgnoreCase(intent.getAction())) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    if (isSqAn != bundle.getBoolean(RECEIVED,false)) { //only consume this if it did not come from us
                        byte[] bytes = bundle.getByteArray(PACKET_BYTES);
                        String incomingChannel = bundle.getString(PACKET_CHANNEL,null);
                        if (BftBroadcast.BFT_CHANNEL.equalsIgnoreCase(incomingChannel)) {
                            synchronized (broadcast) {
                                broadcast.parse(bytes);
                                Log.i("SqAN","Broadcast object updated");
                            }
                            if (listener != null)
                                listener.onSaBroadcastReceived(broadcast);
                        } else if (CHAT_CHANNEL.equalsIgnoreCase(incomingChannel)) {
                            int src = bundle.getInt(PACKET_ORIGIN,Integer.MIN_VALUE);
                            if (listener != null)
                                listener.onChatPacketReceived(src,bytes);
                        }
                    }
                }
            }
        }
    }
}