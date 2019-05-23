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
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.common.sockets.client.SocketTransceiver;
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
    private SocketTransceiver datalink = null;
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
                Log.d(TAG, "Socket channels open");
                downlinkThread = new DownlinkThread();
                downlinkThread.start();
            } catch (IOException e) {
                Log.e(TAG, "Unable to create socket streams; shutting down...");
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
        if ((clientThread != null) && clientThread.isAlive()) {
            Log.d(TAG, "ClientThread.close() called");
            if (handler != null) {
                handler.post(() -> {
                    terminateLink(true);
                });
                handler = null;
            }
            clientThread.quitSafely();
            clientThread = null;
        } else
            Log.d(TAG, "Duplicate call to ClientThread"+id+".close() ignored");
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
                /*if ((uplink != null) && uplink.isConnected()) {
                    if (datalink != null) {
                        try {
                            datalink.queue(packet, uplink,listener);
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                        }
                    } else {
                        if ((uplink == null) || (datalink == null) || !uplink.isConnected()) {
                            //TODO rebuild the connection
                        }
                        Log.d(TAG, "Not sending burst; datalink is null");
                    }
                } else {
                    if (System.currentTimeMillis() > linkStartTime + TIME_TO_WAIT_FOR_LINK_TO_INITIATE) {
                        Log.d(TAG, "Tried to send a burst over an unprepared uplink - trying to build the sockets again");
                        terminateLink(false);
                        //TODO rebuild the connection
                    } else
                        Log.d(TAG, "Tried to send a burst, but the link is still initializing");
                }*/
            });
        }
        return true;
    }

    private class DownlinkThread extends Thread {
        private boolean keepRunning = true;

        public void stopLink() {
            keepRunning = false;
        }

        @Override
        public void run() {
            while (keepRunning) {
                try {
                    //if ((datalink != null) && (uplink != null) && (downlink != null))
                    //    datalink.read(uplink, downlink);
                } catch (Exception e) {
                    if (keepRunning) {
                        Log.e(TAG, "DownlinkThread.run error: " + e.getMessage());
                        //TODO restartClient();
                    }
                    /*if (health != LinkHealth.ERROR) {
                        health = LinkHealth.ERROR;
                        MdxService.log.log(MissionLogging.Category.COMMS,((config == null)?DEFAULT_LINK_NAME:config.getIp())+" downlink error");
                        if (linkHealthListener != null)
                            linkHealthListener.onLinkHealthChange(health);
                    }*/
                }
            }
        }
    }

    private void sendHangup() {
        /*if (datalink != null) {
            try {
                DisconnectingPacket packet = new DisconnectingPacket(Config.getThisDevice().getUUID());
                datalink.queue(packet, uplink, listener);
            } catch (Exception ignore) {
            }
        }*/
    }

    private void terminateLink(boolean sendHangup) {
        Log.d(TAG,"terminating socket link");
        if (sendHangup)
            sendHangup();
        if (downlinkThread != null) {
            downlinkThread.stopLink();
            downlinkThread = null;
        }
        try {
            if (inputStream!=null)
                inputStream.close();
            inputStream = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (outputStream!=null)
                outputStream.close();
            outputStream = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (datalink != null) {
            datalink.closeAll();
            datalink = null;
        }
    }
}
