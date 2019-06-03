package org.sofwerx.sqan.util;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.NetUtil;

import java.net.Inet4Address;
import java.net.InetAddress;

public class AddressUtil {
    /**
     * Should this InetAddress receive a message to this SqAN address
     * @param sqanAddress
     * @param filterAddress
     * @return
     */
    public static boolean isApplicableAddress(int sqanAddress, int filterAddress) {
        if ((filterAddress == PacketHeader.BROADCAST_ADDRESS) || (sqanAddress == PacketHeader.BROADCAST_ADDRESS))
            return true;

        //FIXME work-around to forward IP addresses that are connected to a SqAN node but not in the mesh directly as multicast - this is a work around for now
        //SqAnDevice device = SqAnDevice.findByUUID(filterAddress);
        //if (device == null) {
        //    Log.d(Config.TAG,"Unable to find SqAN device with UUID "+filterAddress+" (corresponds to "+AddressUtil.intToIpv4String(filterAddress)+") so this address is being handled as applicable to all SqAN nodes (i.e. multicast)");
        //    return true;
        //}
        //FIXME end of work around


        return (sqanAddress == filterAddress);
    }

    /**
     * Converts an InetAddress into an int
     * @param address
     * @return
     */
    public static int getIpv4ToInt(InetAddress address) {
        if (!(address instanceof Inet4Address))
            Log.e(Config.TAG,"SqAN addresses can currently only map to IPV4 addresses");
        int destination = 0;
        for (byte b: address.getAddress()) {
            destination = destination << 8 | (b & 0xFF);
        }
        return destination;
    }

    public static String intToIpv4String(int i) {
        return ((i >> 24 ) & 0xFF) + "." +
                ((i >> 16 ) & 0xFF) + "." +
                ((i >>  8 ) & 0xFF) + "." +
                ( i        & 0xFF);
    }

    /**
     * Creates the 169.254.x.x address for this device on the VPN
     * @param sqanId
     * @return
     */
    public static int getSqAnVpnIpv4Address(int sqanId) {
        byte[] ipv4 = new byte[4];
        ipv4[0] = (byte)0xA9; //169
        ipv4[1] = (byte)0xFE; //254
        byte[] idBytes = NetUtil.intToByteArray(sqanId);
        ipv4[2] = idBytes[2];
        ipv4[3] = idBytes[3];
        return NetUtil.byteArrayToInt(ipv4);
    }

    public final static String VPN_NET_MASK = "169.254.0.0";
    public final static String[] VPN_MULTICAST_MASK = {"224.0.0.0","225.0.0.0","226.0.0.0","239.0.0.0"};
}
