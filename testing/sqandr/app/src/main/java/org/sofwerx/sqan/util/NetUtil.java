package org.sofwerx.sqan.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.sofwerx.sqan.Config;

import java.io.StringWriter;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

public class NetUtil {
    private final static int FNV_OFFSET_BASIS = 0b10000001000111001001110111000101; //binary representation of FNV prime 2166136261 but formatted to be a cast from an unsigned int
    private final static int FNV_PRIME = 16777619;
    /**
     * Provides a checksum byte for a given byte array. Modified version of FNV-1a
     * algorithm (https://en.wikipedia.org/wiki/Fowler%E2%80%93Noll%E2%80%93Vo_hash_function) with
     * the output truncated to a byte.
     * @param bytes
     * @return
     */
    public static byte getChecksum(byte[] bytes) {
        int checksum = FNV_OFFSET_BASIS;
        if (bytes != null) {
            for (byte b:bytes) {
                checksum = updateChecksum(checksum,b);
            }
        }
        return (byte)(checksum & 0xFF);
    }
    public static byte updateChecksum(int checksum, byte b) {
        checksum = checksum ^ b;
        return (byte)(checksum * FNV_PRIME);
    }

    public static final byte[] longToByteArray(long value) {
        return new byte[] {
                (byte)(value >>> 56),
                (byte)(value >>> 48),
                (byte)(value >>> 40),
                (byte)(value >>> 32),
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }

    public static final byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }

    public static final int byteArrayToInt(byte[] bytes) {
        if ((bytes == null) || (bytes.length != 4))
            return Integer.MIN_VALUE;
        return bytes[0] << 24 | (bytes[1] & 0xFF) << 16 | (bytes[2] & 0xFF) << 8 | (bytes[3] & 0xFF);
    }

    public static final int byteArrayToShort(byte[] bytes) {
        if ((bytes == null) || (bytes.length != 2))
            return Integer.MIN_VALUE;
        return bytes[0] << 8 | (bytes[1] & 0xFF);
    }

    /**
     * Provides a string listing of the byte values in this array for debug purposes
     * @param bytes
     * @return
     */
    public static String getStringOfBytesForDebug(byte[] bytes) {
        if (bytes == null)
            return null;
        StringWriter out = new StringWriter();

        boolean first = true;
        for (byte b:bytes) {
            if (first)
                first = false;
            else
                out.append(' ');
            out.append(Integer.toString(b));
        }

        return out.toString();
    }

    /**
     * Gets the Int value at a particular offset in an IP Packet
     * @param packet
     * @param offset
     * @return Int value or Int.MIN_VALUE if something went wrong
     */
    public static int getIntInIpPacket(byte[] packet, int offset) {
        if ((packet == null) || (offset < 0) || (offset + 4 > packet.length))
            return Integer.MIN_VALUE;
        byte[] intBytes = new byte[4];
        for (int i=0;i<4;i++) {
            intBytes[i] = packet[offset+i];
        }
        return byteArrayToInt(intBytes);
    }

    public static enum DscpType {
        Network_Control,
        Telephony,
        Signaling,
        Multimedia_Conferencing,
        Real_Time_Interactive,
        Multimedia_Streaming,
        Broadcast_Video,
        Low_Latency_Data,
        OAM,
        High_Throughput_Data,
        Standard,
        Low_Priority_Data,
        UNKNOWN
    }

    /**
     * Gets the DCP type based on the BSCP byte
     * @param dscp
     * @return type or UNKNOWN
     */
    public static DscpType getDscpType(byte dscp) {
        switch (dscp & MASK_DSCP) {
            case ((byte)0b110000):
                return DscpType.Network_Control;

            case ((byte)0b101110):
                return DscpType.Telephony;

            case ((byte)0b101000):
                return DscpType.Signaling;

            case ((byte)0b100010):
            case ((byte)0b100100):
            case ((byte)0b100110):
                return DscpType.Multimedia_Conferencing;

            case ((byte)0b100000):
                return DscpType.Real_Time_Interactive;

            case ((byte)0b011010):
            case ((byte)0b0111000):
            case ((byte)0b011110):
                return DscpType.Multimedia_Streaming;

            case ((byte)0b011000):
                return DscpType.Broadcast_Video;

            case ((byte)0b010010):
            case ((byte)0b010100):
            case ((byte)0b010110):
                return DscpType.Low_Latency_Data;

            case ((byte)0b010000):
                return DscpType.OAM;

            case ((byte)0b001010):
            case ((byte)0b001100):
            case ((byte)0b001110):
                return DscpType.High_Throughput_Data;

            case ((byte)0b000000):
                return DscpType.Standard;

            case ((byte)0b001000):
                return DscpType.Low_Priority_Data;

            default:
                return DscpType.UNKNOWN;
        }
    }

    private final static byte MASK_DSCP = (byte)0b11111100;
    /**
     * Parse an IP packet to get the DSCP
     * @param packet
     * @return
     */
    public static byte getDscpFromIpPacket(byte[] packet) {
        if ((packet == null) || (packet.length < 4))
            return Byte.MIN_VALUE;
        byte dscpByte = packet[1];
        return (byte)(dscpByte & MASK_DSCP);
    }

    public enum PacketType {TCP,UDP,OTHER};

    public static PacketType getPacketType(byte[] packet) {
        if ((packet == null) || (packet.length < 10))
            return PacketType.OTHER;
        switch(packet[9]) {
            case 17:
                return PacketType.UDP;

            case 6:
                return PacketType.TCP;

            default:
                return PacketType.OTHER;
        }
    }

    private final static byte MASK_IPV4_IHL = 0b00001111;

    private static int getHeaderLength(byte[] packet) {
        if ((packet == null) || (packet.length < 1))
            return -1;
        byte ihl = (byte)(packet[0] & MASK_IPV4_IHL);

        return ihl * 4;
    }

    public static int getPort(byte[] packet) {
        int port = -1;
        if (packet != null) {
            int offset = getHeaderLength(packet);
            if ((offset > 0) && (offset < packet.length)) {
                byte[] intbytes = new byte[4];
                intbytes[0] = (byte)0;
                intbytes[1] = (byte)0;
                //FIXME this isn't right
                intbytes[2] = packet[offset];
                intbytes[3] = packet[offset+1];
                port = byteArrayToInt(intbytes);
            }
        }
        return port;
    }

    public static NetworkInterface getAwareNetworkInterface(LinkProperties linkProperties) {
        if (linkProperties != null) {
            try {
                return NetworkInterface.getByName(linkProperties.getInterfaceName());
            } catch (SocketException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public static Inet6Address getAwareAddress(LinkProperties linkProperties) {
        Inet6Address ipv6 = null;
        try {
            NetworkInterface awareNi = getAwareNetworkInterface(linkProperties);
            if (awareNi == null)
                return null;
            Enumeration inetAddresses = awareNi.getInetAddresses();
            while (inetAddresses.hasMoreElements()) {
                InetAddress addr = (InetAddress) inetAddresses.nextElement();
                if (addr instanceof Inet6Address) {
                    if (addr.isLinkLocalAddress()) {
                        ipv6 = (Inet6Address) addr;
                        break;
                    }
                }
            }
        } catch (Exception ignore) {
        }

        return ipv6;
    }

    private final static int MIN_PASSPHRASE_LENGTH = 8; //TODO unable to find these definitively, so they are guesses
    private final static int MAX_PASSPHRASE_LENGTH = 63; //TODO unable to find these definitively, so they are guesses

    /**
     * Adjusts the passcode to meet WiFi Aware requirements
     * @param passcode
     * @return
     */
    public static String conformPasscodeToWiFiAwareRequirements(String passcode) {
        if ((passcode != null) && (passcode.length() >= MIN_PASSPHRASE_LENGTH) && (passcode.length() <= MAX_PASSPHRASE_LENGTH))
            return passcode;
        StringWriter out = new StringWriter();

        int i=0;
        while (i < MIN_PASSPHRASE_LENGTH) {
            if (i < passcode.length())
                out.append(passcode.charAt(i));
            else
                out.append('x');
            i++;
        }
        while ((i < MAX_PASSPHRASE_LENGTH) && (i < passcode.length())) {
            out.append(passcode.charAt(i));
            i++;
        }
        String output = out.toString();
        Log.d(Config.TAG,"Passphrase \""+passcode+"\" adjusted to \""+output+"\" to conform with WiFi Aware requirements");
        return out.toString();
    }
}