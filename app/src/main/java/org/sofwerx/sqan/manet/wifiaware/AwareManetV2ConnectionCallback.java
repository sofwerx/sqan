package org.sofwerx.sqan.manet.wifiaware;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.wifiaware.server.ServerConnection;
import org.sofwerx.sqan.util.NetUtil;

import java.net.Inet6Address;
import java.net.NetworkInterface;

public class AwareManetV2ConnectionCallback extends ConnectivityManager.NetworkCallback {
    private final static String TAG = Config.TAG+".AwareCon";
    private final static long CALLBACK_TIMEOUT = 1000l * 10l;
    private final long timeToStale;
    private boolean success = false;
    private Pairing pairing;
    private final Context context;
    private final AbstractManet manet;

    public AwareManetV2ConnectionCallback(Context context, AbstractManet manet, Pairing pairing) {
        super();
        this.context = context;
        this.manet = manet;
        timeToStale = System.currentTimeMillis() + CALLBACK_TIMEOUT;
        this.pairing = pairing;
    }

    public boolean isStale() {
        return !success && (System.currentTimeMillis() > timeToStale);
    }

    @Override
    public void onAvailable(Network network) {
        if (pairing == null)
            return;
        //success = true;
        Log.d(TAG,"NetworkCallback onAvailable() for "+((pairing.getDevice()==null)?"null device":pairing.getDevice().getLabel()));
        if (Pairing.getIpv6Address() == null) {
            Inet6Address ipv6 = NetUtil.getAwareAddress(context, network);
            if (Pairing.getIpv6Address() == null)
                Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
            else if (!ipv6.equals(Pairing.getIpv6Address())) {
                Log.d(TAG, "Aware IP address changed to " + ipv6.getHostAddress());
            }
            if (ipv6 != null) {
                Pairing.setIpv6Address(ipv6);
                if (pairing.shouldBeServer()) {
                    SqAnDevice thisDevice = Config.getThisDevice();
                    if (thisDevice != null) {
                        Config.getThisDevice().setAwareServerIp(Pairing.getIpv6Address());
                        thisDevice.setRoleWiFi(SqAnDevice.NodeRole.HUB);
                    }
                }
            }
            pairing.handleNetworkChange(network);
        }
    }

    @Override
    public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
        if (pairing == null)
            return;
        Log.d(TAG,"NetworkCallback onLinkPropertiesChanged() for "+((pairing.getDevice()==null)?"null device":pairing.getDevice().getLabel()));
        Inet6Address ipv6 = NetUtil.getAwareAddress(linkProperties);
        if (Pairing.getIpv6Address() == null)
            Log.d(TAG, "Aware IP address assigned as " + ipv6.getHostAddress());
        else if (!ipv6.equals(Pairing.getIpv6Address())) {
            Log.d(TAG, "Aware IP address changed to " + ipv6.getHostAddress());
        }
        if (ipv6 != null)
            Pairing.setIpv6Address(ipv6);

        pairing.setNetworkInterface(NetUtil.getAwareNetworkInterface(linkProperties));

        if (pairing.shouldBeServer()) {
            SqAnDevice thisDevice = Config.getThisDevice();
            if (thisDevice != null) {
                Config.getThisDevice().setAwareServerIp(Pairing.getIpv6Address());
                thisDevice.setRoleWiFi(SqAnDevice.NodeRole.HUB);
            }
        }

        pairing.handleNetworkChange(network);
    }

    @Override
    public void onLost(Network network) {
        success = false;
        if (pairing == null)
            return;
        Log.d(TAG,"Aware onLost() for "+((pairing.getDevice()==null)?"null device":pairing.getDevice().getLabel()));
        try {
            pairing.removeNetworkCallback();
            Log.d(TAG,"unregistered NetworkCallback for "+pairing.toString());
        } catch (Exception e) {
            Log.w(TAG,"Unable to unregister this NetworkCallback: "+e.getMessage());
        }
        if (Config.getThisDevice() != null) {
            pairing.handleNetworkChange(null);
        }
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
