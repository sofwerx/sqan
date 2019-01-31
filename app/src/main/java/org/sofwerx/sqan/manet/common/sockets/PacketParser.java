package org.sofwerx.sqan.manet.common.sockets;

import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

public class PacketParser {
    private final AbstractManet manet;

    public PacketParser(AbstractManet manet) {
        this.manet = manet;
    }

    public void parse(byte[] bytes) {
        if (bytes == null)
            return;
        AbstractPacket packet = AbstractPacket.newFromBytes(bytes);
        manet.onReceived(packet);
    }

    public byte[] toBytes(AbstractPacket packet) {
        if (packet == null)
            return null;
        return packet.toByteArray();
    }
}
