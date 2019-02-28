package org.sofwerx.sqan.manet.common.sockets;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;

import java.net.Inet4Address;
import java.net.InetAddress;

public class AddressUtil {

    /**
     * Should this InetAddress receive a message to this SqAN address
     * @param sqanAddress
     * @param address
     * @return
     */
    /*public static boolean isApplicableAddress(int sqanAddress, InetAddress address) {
        if (address == null)
            return false;
        return isApplicableAddress(sqanAddress,getSqAnAddress(address));
    }*/

    /**
     * Should this InetAddress receive a message to this SqAN address
     * @param sqanAddress
     * @param filterAddress
     * @return
     */
    public static boolean isApplicableAddress(int sqanAddress, int filterAddress) {
        if (filterAddress == PacketHeader.BROADCAST_ADDRESS)
            return true;
        return (sqanAddress == filterAddress);
    }

    /**
     * Converts an InetAddress into a SqAN address
     * @param address
     * @return
     */
    /*public static int getSqAnAddress(InetAddress address) {
        if (!(address instanceof Inet4Address))
            Log.e(Config.TAG,"SqAN addresses can currently only map to IPV4 addresses");
        int destination = 0;
        for (byte b: address.getAddress()) {
            destination = destination << 8 | (b & 0xFF);
        }
        return destination;
    }*/
}
