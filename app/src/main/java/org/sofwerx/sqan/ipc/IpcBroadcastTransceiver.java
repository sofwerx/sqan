package org.sofwerx.sqan.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;

/**
 * Simple mechanism to support SqAN broadcasts based on IPC from other apps. To send/receive
 * data over SqAN, another app will register a listener (to receive) and then use broadcast()
 * to send the data to SqAN.
 */
public class IpcBroadcastTransceiver extends BroadcastReceiver {
    private final static String BROADCAST_PKT = "org.sofwerx.sqan.pkt";
    private final static String PACKET_BYTES = "bytes";
    private final static String RECEIVED = "rcv";
    private static IpcBroadcastTransceiver receiver = null;
    private static IpcBroadcastListener listener = null;
    private static boolean isSqAn;

    public interface IpcBroadcastListener {
        void onPacketReceived(byte[] packet);
    }

    public static void registerAsSqAn(Context context, IpcBroadcastListener listener) {
        register(context,listener);
        isSqAn = true;
    }

    public static void register(Context context, IpcBroadcastListener listener) {
        IpcBroadcastTransceiver.listener = listener;
        if ((context != null) && (listener != null)) {
            if (receiver != null)
                receiver.unregister(context);
            receiver = new IpcBroadcastTransceiver();
            context.registerReceiver(receiver, new IntentFilter(BROADCAST_PKT));
        }
        isSqAn = false;
    }

    public static void unregister(Context context) {
        if ((context != null) && (receiver != null)) {
            try {
                context.unregisterReceiver(receiver);
                receiver = null;
            } catch (IllegalArgumentException e) {
                Log.e("SqAn","Unable to unregister SqAN IPC transceiver: "+e.getMessage());
            }
        }
    }

    /**
     * Sends data to SqAN (or from SqAN) to be consumed by other apps
     * @param context
     * @param bytes the raw byte payload (this should be immediately parsed into an AbstractPacket
     */
    public static void broadcast(Context context, byte[] bytes) {
        if ((context != null) && (bytes != null)) {
            Intent intent = new Intent(BROADCAST_PKT);
            intent.putExtra(PACKET_BYTES,bytes);
            if (isSqAn)
                intent.putExtra(RECEIVED,true);
            context.sendBroadcast(intent);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent != null) {
            if (BROADCAST_PKT.equalsIgnoreCase(intent.getAction()) && (listener != null)) {
                Bundle bundle = intent.getExtras();
                if (bundle != null) {
                    if (isSqAn != bundle.getBoolean(RECEIVED,false)) { //only consume this if it did not come from us
                        byte[] bytes = bundle.getByteArray(PACKET_BYTES);
                        if (bytes != null)
                            listener.onPacketReceived(bytes);
                    }
                }
            }
        }
    }
}