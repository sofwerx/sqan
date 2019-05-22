package org.sofwerx.sqan.manet.wifiaware.client;

import android.os.Handler;
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

import java.io.IOException;
import java.net.Socket;
import java.nio.channels.SocketChannel;

public class ClientConnection extends AbstractConnection {
    private final static String TAG = Config.TAG+".Client";
    private SocketTransceiver datalink = null;
    private DownlinkThread downlinkThread;
    private long linkStartTime = Long.MIN_VALUE;
    private final static long TIME_TO_WAIT_FOR_LINK_TO_INITIATE = 1000l * 10l;
    private final static long RESTART_DELAY = 1000l*2l; //time to wait to restart client on failure
    private final PacketParser parser;
    private final ManetListener listener;
    private Socket socket;
    private SocketChannel uplink;
    private SocketChannel downlink;

    public ClientConnection(@NonNull AbstractManet manet, Socket socket) {
        Log.d(TAG,"Starting ClientConnection...");
        parser = manet.getParser();
        listener = manet.getListener();
        this.socket = socket;
        init();
    }

    private void init() {
        uplink = socket.getChannel();
        downlink = uplink;
        Log.d(TAG,"Socket channels open");
        //TODO
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void close() {
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
        /*if (isAlive()) {
            Log.d(TAG, "SocketRelayThread.close() called");
            if ((handler != null) && !forceful) {
                handler.post(() -> {
                    terminateLink(true);
                });
            }
            if (looper != null) {
                if (forceful)
                    looper.quit();
                else
                    looper.quitSafely();
            }
        } else
            Log.d(TAG, "Duplicate call to SocketRelayThread.close() ignored");*/
    }

    @Override
    public boolean burst(AbstractPacket packet) {
        return false;
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
        if (datalink != null) {
            try {
                DisconnectingPacket packet = new DisconnectingPacket(Config.getThisDevice().getUUID());
                datalink.queue(packet, uplink, listener);
            } catch (Exception ignore) {
            }
        }
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
            if (uplink!=null)
                uplink.close();
            uplink = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        try {
            if (downlink!=null)
                downlink.close();
            downlink = null;
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (datalink != null) {
            datalink.closeAll();
            datalink = null;
        }
    }
}
