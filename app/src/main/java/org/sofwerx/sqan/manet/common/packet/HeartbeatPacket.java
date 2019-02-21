package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.pnt.NetworkTime;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;

import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;

public class HeartbeatPacket extends AbstractPacket {
    private SqAnDevice device;
    private DetailLevel detailLevel;

    public HeartbeatPacket(int originUUID) {
        super(new PacketHeader(originUUID));
        packetHeader.setType(getType());
        packetHeader.setTime(NetworkTime.getNetworkTimeNow());
        device = null;
    }

    public HeartbeatPacket(SqAnDevice device, DetailLevel detailLevel) {
        this(device.getUUID());
        this.device = device;
        this.detailLevel = detailLevel;
    }

    public HeartbeatPacket(PacketHeader packetHeader) {
        super(packetHeader);
    }

    public SqAnDevice getDevice() {
        return device;
    }

    public enum DetailLevel {
        BASIC,
        MEDIUM
    }

    @Override
    public void parse(byte[] bytes) {
        if ((bytes == null) || (packetHeader == null))
            device = null;
        else {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            try {
                device = new SqAnDevice(packetHeader.getOriginUUID());
                if (buf.remaining() >= SpaceTime.SIZE_IN_BYTES) {
                    byte[] spaceTimeBytes = new byte[SpaceTime.SIZE_IN_BYTES];
                    buf.get(spaceTimeBytes);
                    SpaceTime spaceTime = new SpaceTime();
                    spaceTime.parse(spaceTimeBytes);
                    if (!spaceTime.isValid())
                        spaceTime = null;
                    device.setLastLocation(spaceTime);
                } else
                    return;
                int callsignSize = buf.getInt();
                if (callsignSize > 0) {
                    if (callsignSize > 256) { //that's too big to be a proper callsign
                        Log.e(Config.TAG,"Heartbeat contained a callsign "+callsignSize+"b long; message was dropped as it is likely an error");
                        return;
                    }
                    byte[] callsignBytes = new byte[callsignSize];
                    buf.get(callsignBytes);
                    try {
                        device.setCallsign(new String(callsignBytes,"UTF-8"));
                    } catch (UnsupportedEncodingException ignore) {
                    }
                }
            } catch (BufferOverflowException e) {
                Log.e(Config.TAG,"Could not parse Heartbeat: "+e.getMessage());
            }
        }
    }

    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();

        if ((detailLevel == null) || (detailLevel == DetailLevel.BASIC) || (device == null))
            return superBytes;

        byte[] callsignBytes = null;
        if (device.getCallsign() != null) {
            try {
                callsignBytes = device.getCallsign().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        int size;
        if (superBytes == null)
            size = 0;
        else
            size = superBytes.length;
        switch (detailLevel) {
            case BASIC:
                size += 4;
            case MEDIUM:
                size += SpaceTime.SIZE_IN_BYTES;
                size += 4 + 4;
                if (callsignBytes != null)
                    size += callsignBytes.length;
                break;
        }

        ByteBuffer out = ByteBuffer.allocate(size);

        if (superBytes != null)
            out.put(superBytes);

        switch (detailLevel) {
            case MEDIUM:
                SpaceTime spaceTime = device.getLastLocation();
                if (spaceTime == null)
                    out.put(SpaceTime.toByteArrayEmptySpaceTime());
                else
                    out.put(spaceTime.toByteArray());
                if (callsignBytes == null)
                    out.putInt(0);
                else {
                    out.putInt(callsignBytes.length);
                    out.put(callsignBytes);
                }
                break;
        }

        return out.array();
    }

    @Override
    protected int getType() {
        return PacketHeader.PACKET_TYPE_HEARTBEAT;
    }

    @Override
    public boolean isAdminPacket() { return true; }
}
