package org.sofwerx.sqan.manet.wifidirect;

import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * MANET built over Android's WiFi P2P framework which complies with WiFi Direct™
 *  (https://developer.android.com/training/connect-devices-wirelessly/wifi-direct)
 */
public class WiFiDirectManet extends AbstractManet {
    @Override
    public String getName() { return "WiFi Direct™"; }

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
