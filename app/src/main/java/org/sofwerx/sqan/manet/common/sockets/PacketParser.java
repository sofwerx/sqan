package org.sofwerx.sqan.manet.common.sockets;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

public class PacketParser {
    private final AbstractManet manet;

    public PacketParser(AbstractManet manet) {
        this.manet = manet;
    }
    public AbstractManet getManet() { return manet; };

    public static SqAnDevice process(AbstractPacket packet) {
        if (packet == null) {
            Log.e(Config.TAG, "Could not process the packet as it was null");
            return null;
        } else
            Log.d(Config.TAG,"Processed "+packet.getClass().getName());
        SqAnDevice device = SqAnDevice.findByUUID(packet.getOrigin());
        if (device == null) {
            if (packet.getOrigin() > 0) {
                device = new SqAnDevice(packet.getOrigin());
                device.setConnected(packet.getCurrentHopCount());
            }
        } else {
            int hops = device.getHopsAway();
            if (packet.getCurrentHopCount() < hops)
                device.setConnected(packet.getCurrentHopCount());
        }
        if (device != null)
            device.addToDataTally(packet.getApproxSize());
        return device;
    }

    public SqAnDevice processPacketAndNotifyManet(AbstractPacket packet) {
        manet.onReceived(packet);
        return process(packet);
    }

    @Deprecated
    public SqAnDevice processPacketAndNotifyManet(byte[] bytes) {
        if (bytes == null) {
            Log.e(Config.TAG, "PacketParser.processPacketAndNotifyManet(null) ignored");
            return null;
        }
        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
        return processPacketAndNotifyManet(packet);
    }

    public byte[] toBytes(AbstractPacket packet) {
        if (packet == null)
            return null;
        return packet.toByteArray();
    }
}
