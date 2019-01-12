package org.sofwerx.sqan.manet.common.packet;

import org.sofwerx.sqan.manet.common.pnt.NetworkTime;

public class HeartbeatPacket extends AbstractPacket {
    public HeartbeatPacket() {
        super(new PacketHeader());
        packetHeader.setType(getType());
        packetHeader.setTime(NetworkTime.getNetworkTimeNow());
    }

    public HeartbeatPacket(PacketHeader packetHeader) {
        super(packetHeader);
    }

    @Override
    public void parse(byte[] bytes) {
        //ignore since there is no payload in the heartbeat (at least in this stage of development)
    }

    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();

        //nothing to add since the heartbeat is empty (at least in this stage of development)

        return superBytes;
    }

    @Override
    protected int getType() {
        return PacketHeader.PACKET_TYPE_HEARTBEAT;
    }
}
