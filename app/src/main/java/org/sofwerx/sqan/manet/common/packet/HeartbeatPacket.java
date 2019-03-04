package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.RelayConnection;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.pnt.NetworkTime;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;

import java.io.UnsupportedEncodingException;
import java.nio.BufferOverflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

public class HeartbeatPacket extends AbstractPacket {
    private SqAnDevice device;
    private DetailLevel detailLevel;

    //FIXME include a list of the devices this device is connected to (and maybe some measure of strength/stability)

    public HeartbeatPacket(int originUUID) {
        super(new PacketHeader(originUUID));
        if (originUUID <= 0)
            Log.e(Config.TAG,"trying to create Heartbeat packet with origin UUID of "+originUUID+" (this should not happen)");
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
                if (packetHeader.getOriginUUID() > 0) {
                    device = new SqAnDevice(packetHeader.getOriginUUID());
                    if (buf.remaining() < SpaceTime.SIZE_IN_BYTES)
                        return;
                    byte[] spaceTimeBytes = new byte[SpaceTime.SIZE_IN_BYTES];
                    buf.get(spaceTimeBytes);
                    SpaceTime spaceTime = new SpaceTime();
                    spaceTime.parse(spaceTimeBytes);
                    if (!spaceTime.isValid())
                        spaceTime = null;
                    device.setLastLocation(spaceTime);
                    if (buf.remaining() <4)
                        return;
                    int relaySize = buf.getInt();
                    if (relaySize > 0) {
                        byte[] relayBytes = new byte[RelayConnection.SIZE];
                        for (int i=0;i<relaySize;i++) {
                            buf.get(relayBytes);
                            parseRelayConnection(new RelayConnection(relayBytes));
                        }
                    }
                    if (buf.remaining() < 4)
                        return;
                    int callsignSize = buf.getInt();
                    if (callsignSize > 0) {
                        if (callsignSize > 256) { //that's too big to be a proper callsign
                            Log.e(Config.TAG, "Heartbeat contained a callsign " + callsignSize + "b long; message was dropped as it is likely an error");
                            return;
                        }
                        byte[] callsignBytes = new byte[callsignSize];
                        buf.get(callsignBytes);
                        try {
                            device.setCallsign(new String(callsignBytes, "UTF-8"));
                        } catch (UnsupportedEncodingException ignore) {
                        }
                    }
                } else
                    Log.e(Config.TAG,"trying to parse Heartbeat packet, but UUID was "+packetHeader.getOriginUUID()+" (this should never happen)");
            } catch (BufferOverflowException e) {
                Log.e(Config.TAG,"Could not processPacketAndNotifyManet Heartbeat: "+e.getMessage());
            }
        }
    }

    //FIXME hey dummy! you need to send the RelayConnections and then also figure out if an incoming packet needs to be relayed to this device based on its hop count

    private void parseRelayConnection(RelayConnection relay) {
        if (relay == null)
            return;
        SqAnDevice device = SqAnDevice.findByUUID(relay.getSqAnID());
        if (device == null) {
            device = new SqAnDevice(relay.getSqAnID());
            device.setHopsAway(relay.getHops());
            device.setLastConnect(relay.getLastConnection());
        } else {
            if (relay.getLastConnection() > device.getLastConnect()) {
                device.setHopsAway(relay.getHops());
                device.setLastConnect(relay.getLastConnection());
            }
        }
    }

    private void parseRelayConnections(ArrayList<RelayConnection> relays) {
        if (relays == null)
            return;
        for (RelayConnection relay:relays) {
            parseRelayConnection(relay);
        }
    }

    private ArrayList<RelayConnection> getRelayConnections() {
        ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
        if (devices == null)
            return null;
        ArrayList<RelayConnection> relays = new ArrayList<>();
        for (SqAnDevice device:devices) {
            relays.add(new RelayConnection(device.getUUID(),device.getHopsAway(),device.getLastConnect()));
        }
        return relays;
    }

    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();

        boolean includePosition;
        boolean includeRelays;
        boolean includeCallsign;

        if ((detailLevel == null) || (detailLevel == DetailLevel.BASIC) || (device == null))
            return superBytes;

        switch (detailLevel) {
            case MEDIUM:
                includePosition = true;
                includeRelays = true;
                includeCallsign = true;
                break;

            default:
                includePosition = false;
                includeRelays = false;
                includeCallsign = false;
        }

        //for now at least, Callsign, Relays, and Position must have all the flags before it to be valid
        if (includeCallsign && (!includeRelays || !includePosition)) {
            Log.e(Config.TAG,"Invalid hearbeat request - check flags");
            return null; //invalid
        }
        if (includeRelays && !includePosition) {
            Log.e(Config.TAG,"Invalid hearbeat request - check flags");
            return null; //invalid
        }

        byte[] spaceTimeBytes = null;
        if (includePosition && (device.getLastLocation() != null)) {
            SpaceTime spaceTime = device.getLastLocation();
            if (spaceTime == null)
                spaceTimeBytes = SpaceTime.toByteArrayEmptySpaceTime();
            else
                spaceTimeBytes = spaceTime.toByteArray();
        }

        byte[] callsignBytes = null;
        if (includeCallsign && (device.getCallsign() != null)) {
            try {
                callsignBytes = device.getCallsign().getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }

        byte[] relayBytes = null;
        if (includeRelays) {
            ByteBuffer relayBuf;
            ArrayList<RelayConnection> relays = getRelayConnections();
            int nums = 0;
            if (relays != null)
                nums = relays.size();
            relayBuf = ByteBuffer.allocate(4 + nums * RelayConnection.SIZE);
            relayBuf.putInt(nums);
            if (relays != null) {
                for (RelayConnection relay:relays) {
                    relayBuf.put(relay.toBytes());
                }
            }
            relayBytes = relayBuf.array();
        }

        int size;
        if (superBytes == null)
            size = 0;
        else
            size = superBytes.length;
        if (includePosition && (spaceTimeBytes != null)) {
            size += spaceTimeBytes.length;
            if (includeRelays && (relayBytes != null)) {
                size += relayBytes.length;
                if (includeCallsign && (callsignBytes != null))
                    size += 4 + callsignBytes.length;
            }
        }

        ByteBuffer out = ByteBuffer.allocate(size);

        if (superBytes != null) {
            out.put(superBytes);
        }
        if (includePosition && (spaceTimeBytes != null)) {
            out.put(spaceTimeBytes);
            if (includeRelays && (relayBytes != null)) {
                out.put(relayBytes);
                if (includeCallsign && (callsignBytes != null)) {
                    out.putInt(callsignBytes.length);
                    out.put(callsignBytes);
                }
            }
        }

        return out.array();
    }

    @Override
    protected int getType() {
        return PacketHeader.PACKET_TYPE_HEARTBEAT;
    }

    @Override
    public int getApproxSize() {
        if (detailLevel == null)
            return 4;
        switch (detailLevel) {
            case BASIC:
                return 4;
            case MEDIUM:
                return 72;
            default:
                return 4;
        }
    }

    @Override
    public boolean isAdminPacket() { return true; }
}
