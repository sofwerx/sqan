package org.sofwerx.sqan.ipc;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import org.sofwerx.sqan.BuildConfig;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.ChannelBytesPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.RawBytesPacket;

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
    private final static String HIGH_PERFORMANCE_ONLY = "high"; //packets marked with this will be routed through high performance pipes
    private static IpcBroadcastTransceiver receiver = null;
    private static IpcBroadcastListener listener = null;
    private static boolean isSqAn;

    public interface IpcBroadcastListener {
        void onIpcPacketReceived(AbstractPacket packet);
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
     * @param channel
     * @param originatorSqAnAddress SqAnAddress for the message originator
     * @param bytes the raw byte payload (this should be immediately parsed into an AbstractPacket
     */
    public static void broadcast(Context context, String channel, int originatorSqAnAddress, byte[] bytes) {
        if ((context != null) && (bytes != null)) {
            Intent intent = new Intent(BROADCAST_PKT);
            intent.putExtra(PACKET_BYTES,bytes);
            intent.putExtra(PACKET_ORIGIN,originatorSqAnAddress);
            if (channel != null)
                intent.putExtra(PACKET_CHANNEL,channel);
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
                        String channel = bundle.getString(PACKET_CHANNEL,null);
                        AbstractPacket packet;
                        if (channel == null) {
                            Log.d(Config.TAG,"Received raw data packet from IPC");
                            packet = new RawBytesPacket(new PacketHeader(Config.getThisDevice().getUUID()));
                            ((RawBytesPacket) packet).setData(bytes);
                        } else {
                            Log.d(Config.TAG,"Received packet from channel "+channel);
                            packet = new ChannelBytesPacket(new PacketHeader(Config.getThisDevice().getUUID()));
                            ((ChannelBytesPacket) packet).setChannel(channel);
                            ((ChannelBytesPacket) packet).setData(bytes);
                            if (bundle.getBoolean(HIGH_PERFORMANCE_ONLY,false))
                                packet.setHighPerformanceNeeded(true);
                        }
                        if (packet != null)
                            listener.onIpcPacketReceived(packet);
                    }
                }
            }
        }
    }
}