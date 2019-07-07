package org.sofwerx.sqan.manet.common.sockets;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.HeartbeatPacket;
import org.sofwerx.sqan.util.CommsLog;

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
        }
        SqAnDevice device = SqAnDevice.findByUUID(packet.getOrigin());
        if (packet instanceof HeartbeatPacket) {
            if (packet.isValid())
                CommsLog.log(CommsLog.Entry.Category.COMMS, "Processed Heartbeat from " + ((device != null) ? (device.getCallsign() + " (" + device.getUUID() + ")") : packet.getOrigin()) + ", " + packet.getCurrentHopCount() + " hops");
            else {
                CommsLog.log(CommsLog.Entry.Category.COMMS, "Invalid heartbeat received");
                return null;
            }
        } else
            Log.d(Config.TAG, "Processed " + packet.getClass().getSimpleName()+" from " + ((device!=null)?(device.getCallsign()+" ("+device.getUUID()+")"):packet.getOrigin()) + ", " + packet.getCurrentHopCount() + " hops");
        if (device == null) {
            if (packet.getOrigin() > 0) {
                device = new SqAnDevice(packet.getOrigin());
                if (packet.getCurrentHopCount() == 0)
                    device.setConnected(0,device.isDirectBt(),device.isDirectWiFi());
                else
                    device.setConnected(packet.getCurrentHopCount(),false,false);
            }
        } else {
            int hops = device.getHopsAway();
            if (packet.getCurrentHopCount() < hops) {
                if (packet.getCurrentHopCount() == 0)
                    device.setConnected(0,device.isDirectBt(),device.isDirectWiFi());
                else
                    device.setConnected(packet.getCurrentHopCount(),false,false);
            }
        }
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
        if (packet != null) {
            SqAnDevice device = SqAnDevice.findByUUID(packet.getOrigin());
            if (device != null)
                device.addToDataTally(bytes.length);
        }
        return processPacketAndNotifyManet(packet);
    }

    public byte[] toBytes(AbstractPacket packet) {
        if (packet == null)
            return null;
        return packet.toByteArray();
    }
}
