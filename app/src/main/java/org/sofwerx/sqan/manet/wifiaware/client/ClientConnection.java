package org.sofwerx.sqan.manet.wifiaware.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.manet.common.sockets.client.Client;
import org.sofwerx.sqan.manet.common.sockets.client.SocketTransceiver;
import org.sofwerx.sqan.manet.wifiaware.AbstractConnection;

import java.nio.channels.SocketChannel;

public class ClientConnection extends AbstractConnection {
    private final static String TAG = Config.TAG+".Client";
    private DownlinkThread downlinkThread;
    private SocketChannel uplink;
    private SocketChannel downlink;
    private SocketTransceiver datalink = null;
    private static Handler handler;
    private static Looper looper;
    private long linkStartTime = Long.MIN_VALUE;
    private final static long TIME_TO_WAIT_FOR_LINK_TO_INITIATE = 1000l * 10l;
    private final static long RESTART_DELAY = 1000l*2l; //time to wait to restart client on failure
    private final PacketParser parser;
    private final ManetListener listener;

    public ClientConnection(@NonNull AbstractManet manet) {
        parser = manet.getParser();
        listener = manet.getListener();
    }

    @Override
    public boolean isConnected() {
        return false;
    }

    @Override
    public void close() {

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
                    if ((datalink != null) && (uplink != null) && (downlink != null))
                        datalink.read(uplink, downlink);
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
}
