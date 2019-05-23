package org.sofwerx.sqan.manet.common.sockets.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * The Client to connect with the Server over TCP/IP
 */
public class Client extends Thread {
    private final static String TAG = Config.TAG+".Client";
    //private final static String DEFAULT_LINK_NAME = "TCP/IP datalink";
    private DownlinkThread downlinkThread;
    private SocketChannelConfig config;
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

    public Client(SocketChannelConfig config, PacketParser parser) {
        super("SocketClientThread");
        this.parser = parser;
        if ((parser != null) && (parser.getManet() != null))
            listener = parser.getManet().getListener();
        else
            listener = null;
        setConfig(config);
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.SPOKE);
    }

    public void setConfig(SocketChannelConfig config) {
        if (this.config == null) {
            this.config = config;
            datalink = new SocketTransceiver(config, parser);
        }
    }

    @Override
    public void run() {
        if (config == null) {
            Log.d(TAG,"Client not configured completely; terminating");
            close();
        } else {
            Looper.prepare();
            looper = Looper.myLooper();
            handler = new Handler(looper);
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Starting as Client...");
            buildSocket();
            downlinkThread = new DownlinkThread();
            downlinkThread.start();
            looper.loop();
        }
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
                        Log.e(TAG, "Client.DownlinkThread.run error: " + e.getMessage());
                        restartClient();
                    }
                }
            }
        }
    }

    public boolean isReady() {
        if (datalink == null)
            return false;
        return datalink.isReadyToWrite();
    }

    public boolean burst(final AbstractPacket packet) {
        return burst(packet,false);
    }

    public boolean burst(final AbstractPacket packet, boolean tryEvenIfLinkInErrorState) {
        Log.d(TAG,"burst");
        if (!isAlive())
            return false;
        if ((handler != null) && (packet != null)) {
            handler.post(() -> {
                if ((uplink != null) && uplink.isConnected()) {
                    if (datalink != null) {
                        try {
                            datalink.queue(packet, uplink,listener);
                        } catch (Exception e) {
                            Log.e(TAG, e.getMessage());
                            buildSocket(); //reset the connection
                        }
                    } else {
                        if ((uplink == null) || (datalink == null) || !uplink.isConnected())
                            buildSocket(); //reset the connection
                        Log.d(TAG, "Not sending burst; datalink is null");
                    }
                } else {
                    if (System.currentTimeMillis() > linkStartTime + TIME_TO_WAIT_FOR_LINK_TO_INITIATE) {
                        Log.d(TAG, "Tried to send a burst over an unprepared uplink - trying to build the sockets again");
                        terminateLink(false);
                        setConfig(config);
                        buildSocket();
                    } else
                        Log.d(TAG, "Tried to send a burst, but the link is still initializing");
                }
            });
        }
        return true;
    }

    private void buildSocket() {
        Log.d(TAG,"buildSocket()");
        if (config != null) {
            String host = config.getIp();
            int port = config.getPort();
            InetSocketAddress address;
            if (config.getIp() != null)
                address = new InetSocketAddress(host, port);
            else {
                String addressTExt = config.getInetAddress().getHostAddress();
                addressTExt = addressTExt.replace("\\","");
                address = new InetSocketAddress(addressTExt, port);
            }
            if (address.isUnresolved()) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Client is unable to resolve the address: "+address.toString());
                close();
            }
            uplink = null;
            try {
                uplink = SocketChannel.open(address);
                downlink = uplink;
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Operating as a Client");
            } catch (IOException e) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Error initiating uplink: "+e.getMessage());
                try {
                    uplink.close();
                } catch (Throwable t) {
                }
                restartClient();
                close();
            } catch (Exception e) {
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Error initiating uplink: "+e.getMessage());
                restartClient();
            }
        }
    }

    private void restartClient() {
        if (handler != null) {
            handler.removeCallbacks(null);
            handler.postDelayed(() -> {
                buildSocket();
                CommsLog.log(CommsLog.Entry.Category.STATUS, "Restarting Client");
            }, RESTART_DELAY);
        } else {
            buildSocket();
            CommsLog.log(CommsLog.Entry.Category.PROBLEM, "Restarting Client (with null handler - something is significantly broken...)");
        }
    }

    public void close() {
        close(false);
    }

    public void close(boolean forceful) {
        Config.getThisDevice().setRoleWiFi(SqAnDevice.NodeRole.OFF);
        if (isAlive()) {
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
            Log.d(TAG, "Duplicate call to SocketRelayThread.close() ignored");
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
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Terminating link as Client");
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
        config = null;
    }
}