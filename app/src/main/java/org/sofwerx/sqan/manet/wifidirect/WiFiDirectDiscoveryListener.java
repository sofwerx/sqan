package org.sofwerx.sqan.manet.wifidirect;

import android.net.wifi.p2p.WifiP2pDevice;

public interface WiFiDirectDiscoveryListener {
    void onDeviceDiscovered(WifiP2pDevice device);
    void onDiscoveryError(String error);
}
