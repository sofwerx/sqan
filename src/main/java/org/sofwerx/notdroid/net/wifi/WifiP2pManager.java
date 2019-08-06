package org.sofwerx.notdroid.net.wifi;

import org.sofwerx.sqan.manet.wifidirect.WiFiDirectManet;
import org.sofwerx.notdroid.net.wifi.nsd.WifiP2pDnsSdServiceInfo;
import org.sofwerx.notdroid.net.wifi.nsd.WifiP2pDnsSdServiceRequest;

public class WifiP2pManager {

    public static String EXTRA_WIFI_STATE;
    public static String WIFI_P2P_STATE_ENABLED;
    public static String WIFI_P2P_PEERS_CHANGED_ACTION;
    public static String WIFI_P2P_CONNECTION_CHANGED_ACTION;
    public static String WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
    public static String EXTRA_WIFI_P2P_DEVICE;
    public static String WIFI_P2P_STATE_CHANGED_ACTION;

    public static final int BUSY = 0;
    public static final int P2P_UNSUPPORTED = 1;
    public static final int ERROR = 2;
    public static final int NO_SERVICE_REQUESTS = 3;


    public void discoverServices(Channel channel, ActionListener actionListener) {}

    public void requestConnectionInfo(Channel channel, WiFiDirectManet manet) {}

    public void requestPeers(Channel channel, WiFiDirectManet manet) {}

    public void addLocalService(Channel channel, WifiP2pDnsSdServiceInfo info) {}

    public void setDnsSdResponseListeners(Channel channel, DnsSdServiceResponseListener srvListener, DnsSdTxtRecordListener txtListener) {}

    public void addServiceRequest(Channel channel, WifiP2pDnsSdServiceRequest svcRequest, ActionListener actionListener) {}

    public class Channel {

    }

    public abstract class ActionListener {
        public void onSuccess() {}
        public void onFailure(int code) {}
    }

    public class DnsSdTxtRecordListener {

    }

    public interface PeerListListener {

    }

    public class DnsSdServiceResponseListener {

    }

    public interface ConnectionInfoListener {

    }
}
