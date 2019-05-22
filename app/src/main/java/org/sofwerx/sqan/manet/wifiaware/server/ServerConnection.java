package org.sofwerx.sqan.manet.wifiaware.server;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.server.Server;
import org.sofwerx.sqan.manet.common.sockets.server.ServerStatusListener;
import org.sofwerx.sqan.manet.wifiaware.AbstractConnection;
import org.sofwerx.sqan.manet.wifiaware.Pairing;

import java.util.ArrayList;

public class ServerConnection extends AbstractConnection {
    private static Server server;
    private static ArrayList<ServerConnection> serverConnections;
    private static ServerStatusListener listener;
    private Pairing pairing;

    public ServerConnection(@NonNull AbstractManet manet, Pairing pairing) {
        if (manet instanceof ServerStatusListener)
            listener = (ServerStatusListener)manet;
        if (serverConnections == null)
            serverConnections = new ArrayList<>();
        serverConnections.add(this);
        this.pairing = pairing;
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
        if ((server == null) || !server.isRunning() || (packet == null))
            return false;
        int dest = packet.getSqAnDestination();
        if (dest == PacketHeader.BROADCAST_ADDRESS) { //limit this burst to only the device on the other end of this pairing
            if ((pairing != null) && (pairing.getDevice() != null))
                dest = pairing.getDevice().getUUID();
        }
        server.burst(packet,dest);
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