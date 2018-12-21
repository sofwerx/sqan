package org.sofwerx.sqan.manet.wifiaware;

import android.content.Context;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.ManetType;
import org.sofwerx.sqan.manet.SqAnDevice;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 */
public class WiFiAwareManet extends AbstractManet {
    public WiFiAwareManet(Context context, ManetListener listener) { super(context,listener); }

    @Override
    public ManetType getType() { return ManetType.WIFI_AWARE; }

    @Override
    public int getMaximumPacketSize() {
        return 64000; //TODO temp maximum
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
    }

    @Override
    public void executePeriodicTasks() {
        //TODO
    }
}
