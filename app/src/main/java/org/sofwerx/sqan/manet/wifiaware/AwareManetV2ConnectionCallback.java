package org.sofwerx.sqan.manet.wifiaware;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.util.NetUtil;

public class AwareManetV2ConnectionCallback extends ConnectivityManager.NetworkCallback {
    private final static String TAG = Config.TAG+".AwareCon";
    private final static long CALLBACK_TIMEOUT = 1000l * 10l;
    //private final Connection connection;
    //private final long timeToStale;
    private boolean success = false;

    /*public AwareManetV2ConnectionCallback(Connection connection) {
        super();
        timeToStale = System.currentTimeMillis() + CALLBACK_TIMEOUT;
        this.connection = connection;
    }*/

    /*public boolean isStale() {
        return !success && (System.currentTimeMillis() > timeToStale);
    }*/

    @Override
    public void onAvailable(Network network) {
        /*success = true;
        Log.d(TAG,"NetworkCallback onAvailable() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
        if (ipv6 == null) {
            ipv6 = NetUtil.getAwareAddress(context, network);
            if (ipv6 != null) {
                Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
                handleNetworkChange(network,connection,ipv6);
            }
        }*/
    }

    @Override
    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
        /*Log.d(TAG,"NetworkCallback onLinkPropertiesChanged() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
        ipv6 = NetUtil.getAwareAddress(linkProperties);
        SqAnDevice thisDevice = Config.getThisDevice();
        if (thisDevice == null)
            return;
        if (thisDevice.getAwareServerIp() == null)
            Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
        else if (!ipv6.equals(thisDevice.getAwareServerIp())) {
            Log.d(TAG, "Aware IP address changed to " + ipv6.getHostAddress());
            stopSocketConnections(false);
        }

        if (ipv6 != null)
            handleNetworkChange(network,connection,ipv6);
        else
            Log.d(TAG,"Could not do anything with the link property change as the ipv6 address was null");*/
    }

    @Override
    public void onLost(Network network) {
        /*success = false;
        Log.d(TAG,"Aware onLost() for "+((connection.getDevice()==null)?"null device":connection.getDevice().getLabel()));
        if (connection != null)  {
            try {
                connectivityManager.unregisterNetworkCallback(this);
                connection.setCallback(null);
                Log.d(TAG,"unregistered NetworkCallback for "+connection.toString());
            } catch (Exception e) {
                Log.w(TAG,"Unable to unregister this NetworkCallback: "+e.getMessage());
            }
        }
        if (Config.getThisDevice() != null)
            handleNetworkChange(null,connection,Config.getThisDevice().getAwareServerIp());*/
    }

    @Override
    public void onUnavailable() {
        success = false;
        Log.d(TAG, "NetworkCallback onUnavailable()");
    }

    @Override
    public void onCapabilitiesChanged(Network network, NetworkCapabilities networkCapabilities) {
        Log.d(TAG, "NetworkCallback onCapabilitiesChanged()");
    }

    @Override
    public void onLosing(Network network, int maxMsToLive) {
        Log.d(TAG, "NetworkCallback onLosing()");
    }
}
