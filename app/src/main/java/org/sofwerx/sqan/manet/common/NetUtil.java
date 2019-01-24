package org.sofwerx.sqan.manet.common;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.issues.WiFiInUseIssue;

/**
 * A utility class with helper functions to address some network issues
 */
public class NetUtil {
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
}
