package org.sofwerx.sqan.manet;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

/**
 * Abstract class that handles all broad MANET activity. This abstracts away any MANET specific
 * implementation issues and lets SqAN deal with all MANETs in a uniform manner.
 */
public abstract class AbstractManet {
    private Status status = Status.OFF;
    private ManetListener listener = null;

    public AbstractManet() {}

    public abstract String getName();

    public void setListener(ManetListener listener) { this.listener = listener; }

    /**
     * Do any initialization required to move the MANET into a pause state. This may include initial
     * interactions with other nodes in the network.
     */
    public abstract void init() throws ManetException;

    /**
     * Send a pack over the MANET
     * @param packet
     */
    public abstract void burst(AbstractPacket packet) throws ManetException;

    /**
     * Connect to the MANET (i.e. start communicating with other nodes on the network)
     */
    public abstract void connect() throws ManetException;

    /**
     * Pause communication with the MANET (i.e. stop communicating with other nodes on the network but
     * keep the connection warm)
     */
    public abstract void pause() throws ManetException;

    /**
     * Move from a paused state back to a connected state (i.e. resume communicating with the MANET)
     */
    public abstract void resume() throws ManetException;

    /**
     * Disconnect from the MANET (i.e. stop/shutdown - release any resources needed to connect with
     * the MANET)
     */
    public abstract void disconnect() throws ManetException;

    protected void onReceived(AbstractPacket packet) throws ManetException {
        if (packet == null)
            throw new ManetException("Empty packet received over "+getClass());
    }
}