package org.sofwerx.sqan.manet.common.sockets.server;

import java.net.InetAddress;

public interface ServerStatusListener {
    void onServerBacklistClient(InetAddress address);
    void onServerError(String error);
    void onServerNumberOfConnections(int currentConnections);
    void onServerClientDisconnected(InetAddress address);
    void onServerClientConnected(int sQAnAddress);
}
