package org.sofwerx.sqan.manet.wifiaware.server;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.server.Server;
import org.sofwerx.sqan.manet.common.sockets.server.ServerStatusListener;
import org.sofwerx.sqan.manet.wifiaware.AbstractConnection;

import java.util.ArrayList;

public class ServerConnection extends AbstractConnection {
    private static Server server;
    private static ArrayList<ServerConnection> serverConnections = new ArrayList<>();
    private static ServerStatusListener listener;

    public ServerConnection(@NonNull AbstractManet manet) {
        if (manet instanceof ServerStatusListener)
            listener = (ServerStatusListener)manet;
        serverConnections.add(this);
        if (server == null) {
            server = new Server(new SocketChannelConfig((String) null, AbstractManet.SQAN_PORT),manet.getParser(),listener);
            server.start();
        }
    }

    @Override
    public boolean isConnected() {
        if (server == null)
            return false;

        return server.getActiveConnectionCount() > 0;
    }

    @Override
    public void close() {
        serverConnections.remove(this);
        if (serverConnections.isEmpty() && (server != null))
            server.close(false);
    }

    @Override
    public boolean burst(AbstractPacket packet) {
        //FIXME stopped here - do something that makes the packet only be sent to this connection's device also check to see if there's some issue when bursting a packet to broadcast so its not sent multiple times
        return false;
    }

    public static void closeAll() {
        serverConnections = null;
        if (server != null) {
            server.close(true);
            server = null;
        }
    }
}