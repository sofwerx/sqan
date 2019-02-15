package org.sofwerx.sqan.manet.common.sockets.server;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.util.CommsLog;

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

//FIXME establishing a connection with a client, then shutting down the app, then resstarting the app leads to a problem with the port not being released and the server restart not fully taking

/**
 * The Server to host Clients over TCP/IP
 */
public class Server {
    private final static int MAX_SOCKETS_ACCEPTED = 24;
    private SocketChannelConfig config;
    private boolean restart;
    private Selector selector;
    private ServerSocketChannel server;
    private final PacketParser parser;
    private final ServerStatusListener listener;
    private boolean keepRunning = false;
    private HandlerThread serverThread;
    private Handler handler;
    private final ManetListener manetListener;

    public Server(SocketChannelConfig config, PacketParser parser, ServerStatusListener listener) {
        this.config = config;
        this.parser = parser;
        this.listener = listener;
        if ((parser != null) && (parser.getManet() != null))
            manetListener = parser.getManet().getListener();
        else
            manetListener = null;
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.HUB);
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
                server.bind(address, MAX_SOCKETS_ACCEPTED);
                bindComplete = true;
                break;
            } catch (BindException ex) {
                Log.e(Config.TAG, "Attempting to bind to "+address.getHostString()+"; "+ex.getMessage());
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
        CommsLog.log(CommsLog.Entry.Category.STATUS, "Server started port: " + address.getPort());
    }

    private void readAndProcess() throws IOException {
        long secondTime = 0l;
        int acceptCount = 0;
        while (keepRunning) {
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

    public void start() {
        serverThread = new HandlerThread("WiFiServer") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(serverThread.getLooper());
                keepRunning = true;
                restart = true;
                while (keepRunning) {
                    try {
                        buildServer();
                        readAndProcess();
                    } catch (Throwable t) {
                        if (restart) {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Could not start server; shutting server down...");
                            Log.w(Config.TAG, t.getMessage());
                            restart = false;
                            try {
                                if (selector != null) {
                                    selector.close();
                                    selector = null;
                                }
                            } catch (IOException ignore) {
                            }
                            try {
                                server.close();
                                server = null;
                            } catch (IOException ignore) {
                            }
                        } else {
                            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Severe processing error while running in Server mode");
                            close();
                            break; //TODO maybe don't fall out when this fails...
                        }
                    }
                }
            }
        };
        serverThread.start();
    }

    /**
     * Send a packet to a specific client (or all clients)
     * @param packet
     * @param address the client SqAnAddress (or PacketHeader.BROADCAST_ADDRESS for all clients)
     */
    public void burst(AbstractPacket packet, int address) {
        if (handler == null) {
            Log.d(Config.TAG, "Burst requested, but Handler is not ready yet");
            return;
        }
        if (packet != null) {
            Log.d(Config.TAG,"Server bursting packet");
            byte[] bytes = packet.toByteArray();
            ByteBuffer out = ByteBuffer.allocate(4 + bytes.length);
            out.putInt(bytes.length);
            out.put(bytes);
            ClientHandler.addToWriteQue(out,address);
            if (manetListener != null)
                manetListener.onTx(packet);
            ManetOps.addBytesToTransmittedTally(bytes.length);
        }
    }

    public void close() {
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
        if (handler != null) {
            handler.removeCallbacks(null);
            handler.post(() -> {
                burst(new DisconnectingPacket(Config.getThisDevice().getUUID()), PacketHeader.BROADCAST_ADDRESS);
                Log.d(Config.TAG, "Server shutting down...");
                keepRunning = false;
                if (selector != null) {
                    try {
                        selector.close();
                        Log.d(Config.TAG, "Server selector.close() complete");
                    } catch (IOException e) {
                        Log.e(Config.TAG, "Server selector.close() error: " + e.getMessage());
                    }
                }
                if (server != null) {
                    try {
                        server.close();
                        Log.d(Config.TAG, "Server server.close() complete");
                    } catch (IOException e) {
                        Log.e(Config.TAG, "Server server.close() error: " + e.getMessage());
                    }
                }
                if (serverThread != null)
                    serverThread.quitSafely();
            });
        } else {
            keepRunning = false;
            Log.d(Config.TAG, "Handler is null but Server shutting down anyway...");
            if (selector != null) {
                try {
                    selector.close();
                    Log.d(Config.TAG, "Handler is null but Server selector.close() complete");
                } catch (IOException e) {
                    Log.e(Config.TAG, "Handler is null and Server selector.close() error: " + e.getMessage());
                }
            }
            if (server != null) {
                try {
                    server.close();
                    Log.d(Config.TAG, "Handler is null but Server server.close() complete");
                } catch (IOException e) {
                    Log.e(Config.TAG, "Handler is null and Server server.close() error: " + e.getMessage());
                }
            }
            if (serverThread != null)
                serverThread.quit();
        }
    }
}
