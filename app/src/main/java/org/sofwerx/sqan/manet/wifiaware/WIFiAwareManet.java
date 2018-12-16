package org.sofwerx.sqan.manet.wifiaware;

import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * MANET built over the Wi-Fi Aware™ (Neighbor Awareness Networking) capabilities
 * found on Android 8.0 (API level 26) and higher
 *  (https://developer.android.com/guide/topics/connectivity/wifi-aware)
 */
public class WIFiAwareManet extends AbstractManet {
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
}
