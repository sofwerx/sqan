package org.sofwerx.sqan.manet.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;

public class Util {
    public static String getFailureStatusString(int reason) {
        switch (reason) {
            case WifiP2pManager.BUSY:
                return "busy";

            case WifiP2pManager.P2P_UNSUPPORTED:
                return "P2P unsupported";

            case WifiP2pManager.ERROR:
                return "unspecified error";

            case WifiP2pManager.NO_SERVICE_REQUESTS:
                return "no service requests";
        }
        return "unknown";
    }

    public static String getDeviceStatusString(int status) {
        switch (status) {
            case WifiP2pDevice.AVAILABLE:
                return "Available";

            case WifiP2pDevice.FAILED:
                return "Failed";

            case WifiP2pDevice.INVITED:
                return "Invited";

            case WifiP2pDevice.CONNECTED:
                return "Connected";

            case WifiP2pDevice.UNAVAILABLE:
                return "Unavailable";
        }
        return "unknown";
    }

    /**
     * An abritrary method that makes it possible to label one MAC as a higher priority than the other
     * @param firstMAC
     * @param secondMAC
     * @return true == firstMAC is the "higher" priority MAC
     */
    public static boolean isHigherPriorityMac(String firstMAC, String secondMAC) {
        if (firstMAC == null)
            return false;
        if (secondMAC == null)
            return true;
        return firstMAC.hashCode() > secondMAC.hashCode();
    }
}
