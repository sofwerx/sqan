package org.sofwerx.sqan.util;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;

public class NetworkUtil {
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
}