package org.sofwerx.sqan.manet.wifiaware.client;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.wifiaware.AbstractConnection;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientConnection extends AbstractConnection {
    private String TAG = Config.TAG+".Client";
    private AwareSocketTransceiver datalink = null;
    private DownlinkThread downlinkThread;
    private HandlerThread clientThread;
    private Handler handler;
    private long linkStartTime = Long.MIN_VALUE;
    private final static long TIME_TO_WAIT_FOR_LINK_TO_INITIATE = 1000l * 10l;
    private final static long RESTART_DELAY = 1000l*2l; //time to wait to restart client on failure
    private final PacketParser parser;
    private final ManetListener listener;
    private Socket socket;
    private InputStream inputStream;
    private OutputStream outputStream;
    private int id;
    private static AtomicInteger counter;

    public ClientConnection(@NonNull AbstractManet manet, Socket socket) {
        if (counter == null)
            counter = new AtomicInteger(0);
        id = counter.incrementAndGet();
        TAG = Config.TAG+".Client"+id;
        Log.d(TAG,"Starting ClientConnection...");
        parser = manet.getParser();
        listener = manet.getListener();
        this.socket = socket;
        clientThread = new HandlerThread("Client "+id) {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(clientThread.getLooper());
                init();
            }
        };
        clientThread.start();
    }

    private void init() {
        handler.post(() -> {
            try {
                inputStream = socket.getInputStream();
                outputStream = socket.getOutputStream();
                datalink = new AwareSocketTransceiver(parser);
                CommsLog.log(CommsLog.Entry.Category.CONNECTION, "Client #"+id+" socket open");
                downlinkThread = new DownlinkThread();
                downlinkThread.start();
            } catch (IOException e) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Client #"+id+" unable to create socket streams; shutting down...");
                close();
            }
        });
    }

    @Override
    public boolean isConnected() {
        if (datalink == null)
            return false;
        return datalink.isReadyToWrite();
    }

    @Override
    public void close() {
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
        Log.d(TAG, "ClientThread.close() called");
        if (handler != null) {
            handler.post(() -> {
                terminateLink(true);
            });
        }
    }

    @Override
    public boolean burst(final AbstractPacket packet) {
        return burst(packet,false);
    }

    public boolean burst(final AbstractPacket packet, boolean tryEvenIfLinkInErrorState) {
        Log.d(TAG,"burst");
        if ((clientThread == null) || !clientThread.isAlive())
            return false;
        if ((handler != null) && (packet != null)) {
            handler.post(() -> {
                Log.d(TAG,"burst("+packet.getClass().getSimpleName()+") called");
                if (outputStream != null) {
                    if (datalink != null) {
                        try {
                            datalink.queue(packet, outputStream, listener);
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    } else {
                        //TODO rebuild the connection
                        Log.d(TAG, "Not sending burst; datalink is null");
                    }
                } else {
                    if (System.currentTimeMillis() > linkStartTime + TIME_TO_WAIT_FOR_LINK_TO_INITIATE) {
                        Log.d(TAG, "Tried to send a burst over an unprepared uplink - trying to build the sockets again");
                        terminateLink(false);
                        //TODO rebuild the connection
                    } else
                        Log.d(TAG, "Tried to send a burst, but the link is still initializing");
                }
            });
        }
        return true;
    }

    private class DownlinkThread extends Thread {
        private boolean keepRunning = true;

        public void stopLink() {
            keepRunning = false;
            this.interrupt();
        }

        @Override
        public void run() {
            while (keepRunning) {
                try {
                    if ((datalink != null) && (inputStream != null) && (outputStream != null))
                        datalink.read(inputStream, outputStream);
                    //try {
                    //    sleep(100);
                    //} catch (InterruptedException ignore) {
                    //}
                } catch (Exception e) {
                    if (keepRunning) {
                        Log.e(TAG, "DownlinkThread.run error: " + e.getMessage());
                        restartClient();
                        keepRunning = false;
                    }
                }
            }
        }
    }

    private void restartClient() {
        clearStreams();
        if (handler != null) {
            handler.postDelayed(() -> {
                CommsLog.log(CommsLog.Entry.Category.STATUS, "Restarting Client");
                init();
            }, RESTART_DELAY);
        } else {
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Restarting Client (with null handler - something is significantly broken...)");
            init();
        }
    }

    private void sendHangup() {
        if (handler != null) {
            handler.post(() -> {
                if (datalink != null) {
                    try {
                        DisconnectingPacket packet = new DisconnectingPacket(Config.getThisDevice().getUUID());
                        datalink.queue(packet, outputStream, listener);
                    } catch (Exception ignore) {
                    }
                }
            });
        }
    }

    private void clearStreams() {
        Log.d(TAG, "Clearing streams for Client #"+id);
        if (downlinkThread != null) {
            downlinkThread.stopLink();
            downlinkThread = null;
        }
        try {
            if (inputStream != null)
                inputStream.close();
            inputStream = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (outputStream != null)
                outputStream.close();
            outputStream = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (datalink != null) {
            datalink = null;
        }
    }

    private void stop() {
        clearStreams();
        Log.d(TAG, "Stopping Client #"+id);
        if (clientThread != null) {
            if (clientThread.isAlive())
                clientThread.quitSafely();
            clientThread = null;
        }
    }

    private void terminateLink(final boolean sendHangup) {
        if (sendHangup)
            sendHangup();
        if (handler != null) {
            handler.post(() -> {
                stop();
            });
            handler = null;
        } else
            stop();
    }
}
