package org.sofwerx.sqan.manet.common.packet;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

/**
 * A Channel Bytes Packet is used to send raw data but make it available in a pub/sub model.
 * This is intended to allow raw data over SqAN but to also support routing to different apps
 * on the same device all connected over SqAN
 */
public class ChannelBytesPacket extends AbstractPacket {
    private String channel;
    private byte[] data;

    public ChannelBytesPacket(PacketHeader packetHeader) {
        super(packetHeader);
        data = null;
    }

    @Override
    protected byte getChecksum() { return PacketHeader.calcChecksum(data); }

    @Override
    public void parse(byte[] bytes) {
        if (bytes == null) {
            channel = null;
            data = null;
        } else {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            int channelBytesLen = buf.getInt();
            if (buf.remaining() < channelBytesLen) {
                Log.e(Config.TAG,"Could not processPacketAndNotifyManet incoming channel bytes buffer; incomplete data");
                channel = null;
                data = null;
                return;
            }
            try {
                byte[] channelBytes = new byte[channelBytesLen];
                buf.get(channelBytes);
                channel = new String(channelBytes,"UTF-8");
                int remaining = buf.remaining();
                if (remaining > 0) {
                    data = new byte[remaining];
                    buf.get(data);
                } else
                    data = null;
            } catch (UnsupportedEncodingException e) {
                channel = null;
                data = null;
                e.printStackTrace();
            }
        }
    }

    @Override
    public byte[] toByteArray() {
        byte[] superBytes = super.toByteArray();
        int len = 4; //minimum is the int that provides the length of the channel name
        byte[] channelBytes = null;
        if (channel != null) {
            try {
                channelBytes = channel.getBytes("UTF-8");
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }
        }
        if (channelBytes != null)
            len += channelBytes.length;
        if (data != null)
            len += data.length;
        ByteBuffer out = ByteBuffer.allocate(superBytes.length + len);
        out.put(superBytes);
        if (channelBytes == null)
            out.putInt(0);
        else {
            out.putInt(channelBytes.length);
            out.put(channelBytes);
        }
        if (data != null)
            out.put(data);
        return out.array();
    }

    @Override
    public boolean isAdminPacket() { return false; }

    @Override
    protected byte getType() {
        return PacketHeader.PACKET_TYPE_CHANNEL_BYTES;
    }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    /**
     * Gets the channel that this data belongs to
     * @return channel name (or null if none)
     */
    public String getChannel() { return channel; }

    /**
     * Sets the channel that this data belongs to. Of note, the channel name is UTF-8 converted
     * to a byte array, so a longer channel name has a direct impact on the overall
     * packet size.
     * @param channel channel name (null is legal, but it would make more sense to use the RawBytesPacket)
     */
    public void setChannel(String channel) { this.channel = channel; }
}
