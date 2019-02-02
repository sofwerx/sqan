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

    public void parse(byte[] bytes) {
        if (bytes == null) {
            Log.e(Config.TAG, "PacketParser.parse(null) ignored");
            return;
        }
        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
        if (packet == null)
            Log.e(Config.TAG,"Attempted to parse packet but could not parse the "+bytes.length+"b");
        else
            Log.d(Config.TAG,"Parsed "+packet.getClass().getName());
        SqAnDevice device = SqAnDevice.findByUUID(packet.getOrigin());
        if (device != null)
            device.addToDataTally(bytes.length);
        manet.onReceived(packet);
    }

    public byte[] toBytes(AbstractPacket packet) {
        if (packet == null)
            return null;
        return packet.toByteArray();
    }
}
