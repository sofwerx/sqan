package org.sofwerx.sqan.manet.common.sockets.server;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

/**
 * The Server to host Clients over TCP/IP
 */
public class Server {
    private SocketChannelConfig config;
    private boolean restart;
    private Selector selector;
    private ServerSocketChannel server;
    private final PacketParser parser;
    private final ServerStatusListener listener;

    public Server(SocketChannelConfig config, PacketParser parser, ServerStatusListener listener) {
        this.config = config;
        this.parser = parser;
        this.listener = listener;
    }

    private int acceptClients(int acceptCount) throws IOException {
        SocketChannel client;
        while ((client = server.accept()) != null) {
            if (++acceptCount > 100) {
                client.close();
                Log.w(Config.TAG, "Refused connection - possible DOS attack");
            } else {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY,Boolean.TRUE);
                try {
                    ClientHandler handler = new ClientHandler(client,parser,listener);
                    client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, handler);
                    Log.i(Config.TAG, "Accepted client #" + handler.getId());
                } catch (Throwable t) {
                    String msg = "Error defining/registering new client";
                    Log.e(Config.TAG, msg);
                }
            }
        }
        return acceptCount;
    }

    /**
     * Create the server socket. Save it in an instance attribute.
     *
     * @throws IOException
     */
    private void buildServer() throws IOException {
        final InetSocketAddress address = new InetSocketAddress(config.getPort());
        this.server = ServerSocketChannel.open();
        server.configureBlocking(false);
        boolean bindComplete = false;
        for (int i = 0; i < 3; ++i) {
            try {
                server.bind(address, 50);
                bindComplete = true;
                break;
            } catch (BindException ex) {
                Log.d(Config.TAG, "Attempting to bind to "+address.getHostString());
                Log.e(Config.TAG, ex.getMessage());
                try {
                    Thread.sleep(1000l);
                } catch (Throwable ignore) {
                }
            }
        }
        if (!bindComplete)
            throw new IOException("Could not bind to " + address);

        this.selector = Selector.open();
        // NOTE: the key for the server MUST have a null attachment
        server.register(selector, SelectionKey.OP_ACCEPT);
        Log.d(Config.TAG, "Server started:\n" + config);
    }

    private void readAndProcess() throws IOException {
        long secondTime = 0l;
        int acceptCount = 0;
        while (true) {
            boolean isReading = false;
            int selectionCount = selector.select(1000l * 5l);
            Set<SelectionKey> selected = selector.selectedKeys();
            Iterator<SelectionKey> i = selected.iterator();
            while (i.hasNext()) {
                SelectionKey s = i.next();
                if (s.isAcceptable()) {
                    long temp = System.currentTimeMillis() / 1000l;
                    if (temp != secondTime) {
                        secondTime = temp;
                        acceptCount = 0;
                    }
                    acceptCount = acceptClients(acceptCount);
                } else {
                    try {
                        if (s.isWritable()) {
                            ClientHandler handler = (ClientHandler)s.attachment();
                            handler.readyToWrite();
                        }
                    } catch (CancelledKeyException ignore) {
                    }
                    try {
                        if (s.isReadable()) {
                            isReading = true;
                            ClientHandler handler = (ClientHandler)s.attachment();
                            handler.readyToRead();
                        }
                    } catch (CancelledKeyException ignore) {
                    } catch (BlacklistException ignore) {
                        s.cancel(); // already logged; just cancel
                    }
                }
                i.remove();
            }

            ClientHandler.removeUnresponsiveConnections();

            // Make sure the operations are set correctly
            int connectionCount = selector.keys().size();
            for (SelectionKey key : selector.keys()) {
                ClientHandler handler = (ClientHandler) key.attachment();
                if (handler == null)
                    continue; // the server key
                if (handler.isClosed())
                    key.cancel();
                else if (isReading || handler.hasBacklog()) {
                    if ((key.interestOps() & SelectionKey.OP_WRITE) == 0) // Make sure the write-ready key is on
                        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
                    handler.trimQueue(connectionCount);
                } else if (selectionCount > 0) {
                    // Make sure we don't get spurious write-ready selections,
                    // but don't turn off op-write if we simply timed out
                    if ((key.interestOps() & SelectionKey.OP_WRITE) != 0)
                        key.interestOps(SelectionKey.OP_READ);
                }
            }
        }
    }

    public void restart(SocketChannelConfig config) throws IOException {
        this.config = config;
        this.restart = true;
        this.selector.close();
    }

    public void start() {
        this.restart = true;
        while (true) {
            try {
                buildServer();
                readAndProcess();
            } catch (Throwable t) {
                if (restart) {
                    Log.d(Config.TAG, "Restarting server");
                    Log.w(Config.TAG, t.getMessage());
                    restart = false;
                    try {
                        this.selector.close();
                    } catch (IOException ignore) {
                    }
                    try {
                        this.server.close();
                    } catch (IOException ignore) {
                    }
                } else {
                    Log.e(Config.TAG, "Severe processing error (exiting)");
                    //TODO
                }
            }
        }
    }

    /**
     * Send a packet to a specific client (or all clients)
     * @param packet
     * @param address the client SqAnAddress (or PacketHeader.BROADCAST_ADDRESS for all clients)
     */
    public void burst(AbstractPacket packet, int address) {
        if (packet != null) {
            ByteBuffer out = ByteBuffer.wrap(packet.toByteArray());
            ClientHandler.addToWriteQue(out,address);
        }
    }
}
