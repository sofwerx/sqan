package org.sofwerx.sqan.manet.wifiaware;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.aware.WifiAwareManager;
import android.os.Build;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 */
public class WiFiAwareManet extends AbstractManet {
    private WifiAwareManager wifiAwareManager;
    private BroadcastReceiver hardwareStatusReceiver;

    public WiFiAwareManet(Context context, ManetListener listener) {
        super(context,listener);
        wifiAwareManager = (WifiAwareManager)context.getSystemService(Context.WIFI_AWARE_SERVICE);
        hardwareStatusReceiver = null;
    }

    @Override
    public ManetType getType() { return ManetType.WIFI_AWARE; }

    @Override
    public boolean isSupported(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE);
    }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
    }

    @Override
    public void setNewNodesAllowed(boolean newNodesAllowed) {
        //TODO
    }

    @Override
    public void onNodeLost(SqAnDevice node) {
        //TODO
    }

    @Override
    public String getName() { return "WiFi Aware™"; }

    @Override
    public void init() throws ManetException {
        //TODO
    }

    @Override
    public void burst(AbstractPacket packet) throws ManetException {
        //TODO
    }

    @Override
    public void burst(AbstractPacket packet, SqAnDevice device) throws ManetException {
        //TODO
    }

    @Override
    public void connect() throws ManetException {
        //TODO
        if (hardwareStatusReceiver == null) {
            hardwareStatusReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                     onWiFiAwareStatusChanged();
                }
            };
            IntentFilter filter = new IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED);
            context.registerReceiver(hardwareStatusReceiver, filter);
        }
    }

    @Override
    public void pause() throws ManetException {
        //TODO
    }

    @Override
    public void resume() throws ManetException {
        //TODO
    }

    @Override
    public void disconnect() throws ManetException {
        //TODO
        if (hardwareStatusReceiver != null) {
            context.unregisterReceiver(hardwareStatusReceiver);
            hardwareStatusReceiver = null;
        }
    }

    @Override
    public void executePeriodicTasks() {
        //TODO
    }

    /**
     * Entry point when a change in the availability of WiFiAware is detected
     */
    private void onWiFiAwareStatusChanged() {
        //TODO
    }
}
