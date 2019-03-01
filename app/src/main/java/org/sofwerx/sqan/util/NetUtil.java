package org.sofwerx.sqan.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;

public class NetUtil {
    public enum ConnectionType {WIFI,MOBILE,NOT_CONNECTED};
    public static String INTENT_CONNECTIVITY_CHANGED = "android.net.conn.CONNECTIVITY_CHANGE";
    public static String INTENT_WIFI_CHANGED = "android.net.wifi.WIFI_STATE_CHANGED";

    public static ConnectionType getConnectivityStatus(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);

        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        if (activeNetwork != null) {
            if(activeNetwork.getType() == ConnectivityManager.TYPE_WIFI)
                return ConnectionType.WIFI;

            if(activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE)
                return ConnectionType.MOBILE;
        }
        return ConnectionType.NOT_CONNECTED;
    }

    /**
     * Turns-on WiFi is it is not already
     */
    public static void turnOnWiFiIfNeeded(Context context) {
        WifiManager wiFiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (!wiFiManager.isWifiEnabled())
            wiFiManager.setWifiEnabled(true);
    }

    /**
     * Leave any current WiFi networks
     */
    public static void forceLeaveWiFiNetworks(Context context) {
        if (isWiFiConnected(context)) {
            Log.d(Config.TAG,"WiFi connection detected; disconnecting");
            WifiManager wiFiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            wiFiManager.disconnect(); //TODO maybe find a more elegant solution than this
            SqAnService.clearIssue(new WiFiInUseIssue(false,null));
        }
    }

    /**
     * Is this device currently actively connected to a WiFi network
     * @param context
     * @return true == active WiFi connection
     */
    public static boolean isWiFiConnected(Context context) {
        ConnectivityManager connectionManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectionManager.getActiveNetworkInfo();
        return ((activeNetworkInfo != null) && activeNetworkInfo.isConnected() && (activeNetworkInfo.getType() == ConnectivityManager.TYPE_WIFI));
    }

    /**
     * Provides a checksum byte for a given byte array
     * @param bytes
     * @return
     */
    public static byte getChecksum(byte[] bytes) {
        byte checksum = 0b111000;
        if (bytes != null) {
            for (byte b:bytes) {
                checksum ^= b; //just a quick XOR for checksum
            }
        }
        return checksum;

    }

    public static final byte[] intToByteArray(int value) {
        return new byte[] {
                (byte)(value >>> 24),
                (byte)(value >>> 16),
                (byte)(value >>> 8),
                (byte)value};
    }
}