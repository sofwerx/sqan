package org.sofwerx.sqan.manet.common.sockets.server;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
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

/**
 * The Server to host Clients over TCP/IP
 */
public class Server {
    private final static String TAG = Config.TAG+".Server";
    private final static int MAX_SOCKETS_ACCEPTED = 24;
    private SocketChannelConfig config;
    //private boolean restart;
    private Selector selector;
    private ServerSocketChannel server;
    private final PacketParser parser;
    private final ServerStatusListener listener;
    private boolean keepRunning = false;
    private HandlerThread serverThread;
    private Handler handler;
    private final ManetListener manetListener;
    private long lastConnection;
    private long idleTimeout = -1l;

    public Server(SocketChannelConfig config, PacketParser parser, ServerStatusListener listener) {
        this.config = config;
        this.parser = parser;
        this.listener = listener;
        if ((parser != null) && (parser.getManet() != null))
            manetListener = parser.getManet().getListener();
        else
            manetListener = null;
        //Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.HUB);
    }

    public int getActiveConnectionCount() {
        return ClientHandler.getActiveConnectionCount();
    }

    private int acceptClients(int acceptCount) throws IOException {
        SocketChannel client;
        while ((client = server.accept()) != null) {
            if (++acceptCount > 100) {
                client.close();
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, this.getClass().getSimpleName()+" refused connection - possible DOS attack");
            } else {
                client.configureBlocking(false);
                client.setOption(StandardSocketOptions.TCP_NODELAY,Boolean.TRUE);
                try {
                    ClientHandler handler = new ClientHandler(client,parser,listener);
                    client.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE, handler);
                    CommsLog.log(CommsLog.Entry.Category.CONNECTION, this.getClass().getSimpleName()+" accepted client #" + handler.getId());
                } catch (Throwable t) {
                    String msg = "Error defining/registering new client";
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, msg);
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
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Building WiFi server");
        final InetSocketAddress address = new InetSocketAddress(config.getPort());
        this.server = ServerSocketChannel.open();
        server.configureBlocking(false);
        //server.setOption(StandardSocketOptions.SO_REUSEADDR, true); //used to prevent blocking on port when rapidly cycling server on and off
        boolean bindComplete = false;
        for (int i = 0; i < 3; ++i) {
            try {
                server.bind(address, MAX_SOCKETS_ACCEPTED);
                bindComplete = true;
                break;
            } catch (BindException ex) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Attempting to bind to "+address.getHostString()+"; "+ex.getMessage());
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
        lastConnection = System.currentTimeMillis();
    }

    public boolean isRunning() {
        return keepRunning && (serverThread != null) && serverThread.isAlive();
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
            if (idleTimeout > 0l) {
                int clientCount = ClientHandler.getActiveConnectionCount();
                if (clientCount > 0) {
                    Log.d(TAG,"Server has "+clientCount+" active connection"+((clientCount==1)?"":"s"));
                    lastConnection = System.currentTimeMillis();
                } else {
                    if (lastConnection > 0l) {
                        Log.d(TAG,"Server has no active connections");
                        long elapsed = System.currentTimeMillis() - lastConnection;
                        if (elapsed > idleTimeout) {
                            Log.d(TAG, "Server has had no connections for " + Long.toString(elapsed)+"ms; shutting down");
                            close(false);
                        }
                    }
                }
            }

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
        Log.d(TAG,"IP server start()");
        serverThread = new HandlerThread("IpServer") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(serverThread.getLooper());
                keepRunning = true;
                //restart = true;
                try {
                    buildServer();
                    readAndProcess();
                } catch (Throwable t) {
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Severe processing error while running in Server mode");
                    if (listener == null)
                        close(false);
                    else
                        listener.onServerFatalError();
                }
            }
        };
        serverThread.start();
    }

    /**
     * Send a packet to a specific client (or all clients)
     * @param packet
     * @param address the client SqAnAddress (or PacketHeader.BROADCAST_ADDRESS for all clients)
     * @return true == the server is trying to send this packet
     */
    public boolean burst(AbstractPacket packet, int address) {
        if (handler == null) {
            Log.d(TAG, "Burst requested, but Handler is not ready yet");
            return false;
        }
        boolean sent = false;
        if (packet != null) {
            byte[] bytes = packet.toByteArray();
            if (address == PacketHeader.BROADCAST_ADDRESS)
                Log.d(TAG,"Server broadcasting "+bytes.length+"b packet");
            else
                Log.d(TAG,"Server bursting "+bytes.length+"b packet to "+address);
            ByteBuffer out = ByteBuffer.allocate(4 + bytes.length);
            out.putInt(bytes.length);
            out.put(bytes);
            sent = ClientHandler.addToWriteQue(out,address);
            if (sent) {
                if (manetListener != null)
                    manetListener.onTx(packet);
                ManetOps.addBytesToTransmittedTally(bytes.length);
            }
        }
        return sent;
    }

    public void close(final boolean announce) {
        keepRunning = false;
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
        if (handler != null) {
            //handler.removeCallbacks(null);
            handler.post(() -> {
                if (announce)
                    burst(new DisconnectingPacket(Config.getThisDevice().getUUID()), PacketHeader.BROADCAST_ADDRESS);
                Log.d(TAG, "Server shutting down...");
                handler.removeCallbacksAndMessages(null);
                handler = null;
                if (selector != null) {
                    try {
                        selector.close();
                        Log.d(TAG, "Server selector.close() complete");
                    } catch (IOException e) {
                        Log.e(TAG, "Server selector.close() error: " + e.getMessage());
                    }
                }
                if (server != null) {
                    try {
                        server.close();
                        Log.d(TAG, "Server server.close() complete");
                    } catch (IOException e) {
                        Log.e(TAG, "Server server.close() error: " + e.getMessage());
                    }
                }
                if (serverThread != null)
                    serverThread.quitSafely();
            });
        } else {
            keepRunning = false;
            Log.d(TAG, "Handler is null but Server shutting down anyway...");
            if (selector != null) {
                try {
                    selector.close();
                    Log.d(TAG, "Handler is null but Server selector.close() complete");
                } catch (IOException e) {
                    Log.e(TAG, "Handler is null and Server selector.close() error: " + e.getMessage());
                }
            }
            if (server != null) {
                try {
                    server.close();
                    Log.d(TAG, "Handler is null but Server server.close() complete");
                } catch (IOException e) {
                    Log.e(TAG, "Handler is null and Server server.close() error: " + e.getMessage());
                }
            }
            if (serverThread != null)
                serverThread.quit();
        }
        ClientHandler.clear();
        if (listener != null)
            listener.onServerClosed();
    }

    /**
     * Server will shutdown if it doesn't have
     * @param idleLength time in ms (negative == no timeout)
     */
    public void setIdleTimeout(long idleLength) { this.idleTimeout = idleLength; }
    public void clearIdleTimeout() { idleTimeout = -1l; }
}
