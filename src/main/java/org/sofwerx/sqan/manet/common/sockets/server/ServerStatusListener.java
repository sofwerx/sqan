package org.sofwerx.sqan.manet.common.sockets.server;

import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.net.InetAddress;

public interface ServerStatusListener {
    void onServerBlacklistClient(InetAddress address);

    /**
     * A recovereable error was encountered
     * @param error
     */
    void onServerError(String error);

    /**
     * An unrecoverable error was encountered and the server is no longer operational
     */
    void onServerFatalError();

    /**
     * Called when the server has completed shutdown
     */
    void onServerClosed();

    void onServerClientDisconnected(InetAddress address);

    /**
     * Called when a new client has passed authentication
     */
    void onNewClient(SqAnDevice sqAnDevice);
}
