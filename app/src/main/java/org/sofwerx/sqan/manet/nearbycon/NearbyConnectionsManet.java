package org.sofwerx.sqan.manet.nearbycon;

import org.sofwerx.sqan.manet.AbstractManet;
import org.sofwerx.sqan.manet.ManetException;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * MANET built over the Google Nearby Connections API
 *  (https://developers.google.com/nearby/connections/overview)
 */
public class NearbyConnectionsManet extends AbstractManet {
    @Override
    public String getName() { return "Nearby Connections"; }

    @Override
    public void init() throws ManetException {
        //TODO
    }

    @Override
    public void burst(AbstractPacket packlet) {
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
