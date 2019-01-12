package org.sofwerx.sqan.manet.wifidirect;

import android.content.Context;
import android.content.pm.PackageManager;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.ManetException;
import org.sofwerx.sqan.manet.common.ManetType;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet {
    public WiFiDirectManet(Context context, ManetListener listener) { super(context,listener); }

    @Override
    public ManetType getType() { return ManetType.WIFI_DIRECT; }

    @Override
    public boolean isSupported(Context context) {
        boolean hasWiFi = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI);
        return hasWiFi; //TODO check for prereqs if any
    }

    @Override
    public String getName() { return "WiFi Direct®"; }

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
