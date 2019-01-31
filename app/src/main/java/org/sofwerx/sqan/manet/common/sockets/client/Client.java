package org.sofwerx.sqan.manet.common.sockets.client;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.DisconnectingPacket;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.SocketChannel;

/**
 * The Client to connect with the Server over TCP/IP
 */
public class Client extends Thread {
    //private final static String DEFAULT_LINK_NAME = "TCP/IP datalink";
    private DownlinkThread downlinkThread;
    private SocketChannelConfig config;
    private SocketChannel uplink;
    private SocketChannel downlink;
    private SocketTransceiver datalink = null;
    //private LinkHealthListener linkHealthListener = null;
    //private MdxPacketListener packetListener = null;
    //private LinkHealth health = LinkHealth.UNKNOWN;
    private static Handler handler;
    private static Looper looper;
    private long linkStartTime = Long.MIN_VALUE;
    private final static long TIME_TO_WAIT_FOR_LINK_TO_INITIATE = 1000l * 10l;
    private final PacketParser parser;

    public Client(SocketChannelConfig config, PacketParser parser) {
        super("SocketClientThread");
        this.parser = parser;
        setConfig(config);
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
            Log.d(Config.TAG,"Client not configured completely; terminating");
            close();
        } else {
            Looper.prepare();
            looper = Looper.myLooper();
            handler = new Handler(looper);
            Log.d(Config.TAG,"Client starting...");
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
                    if (keepRunning)
                        Log.e(Config.TAG,"Client.DownlinkThread.run error: "+e.getMessage());
                    /*if (health != LinkHealth.ERROR) {
                        health = LinkHealth.ERROR;
                        MdxService.log.log(MissionLogging.Category.COMMS,((config == null)?DEFAULT_LINK_NAME:config.getIp())+" downlink error");
                        if (linkHealthListener != null)
                            linkHealthListener.onLinkHealthChange(health);
                    }*/
                    e.printStackTrace();
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
        Log.d(Config.TAG,"burst");
        if (!isAlive())
            return false;
        if ((handler != null) && (packet != null)) {
            handler.post(() -> {
                if ((uplink != null) && uplink.isConnected()) {
                    //if ((datalink != null) && (tryEvenIfLinkInErrorState || ((datalink.getHealth() == LinkHealth.IDLE) || (datalink.getHealth() == LinkHealth.ACTIVE)))) {
                    if (datalink != null) {
                        try {
                            datalink.queue(packet, uplink);
                            /*if (health != LinkHealth.ACTIVE) {
                                if (health != LinkHealth.IDLE)
                                    MdxService.log.log(MissionLogging.Category.COMMS, "Burst sent to " + ((config == null) ? DEFAULT_LINK_NAME : config.getIp()) + " - link health changed to ACTIVE");
                                health = LinkHealth.ACTIVE;

                                if (linkHealthListener != null)
                                    linkHealthListener.onLinkHealthChange(health);
                            }*/
                        } catch (Exception e) {
                            Log.e(Config.TAG, e.getMessage());
                            /*if (health != LinkHealth.ERROR) {
                                health = LinkHealth.ERROR;
                                MdxService.log.log(MissionLogging.Category.COMMS, "Unable to send burst to " + ((config == null) ? DEFAULT_LINK_NAME : config.getIp()) + " - link error");
                                if (linkHealthListener != null)
                                    linkHealthListener.onLinkHealthChange(health);
                            } else {
                                if (linkHealthListener != null)
                                    linkHealthListener.onCommsFailure();
                            }*/
                        }
                    } else {
                        if ((uplink == null) || (datalink == null) || !uplink.isConnected()) {
                            /*if (health != LinkHealth.ERROR) {
                                health = LinkHealth.ERROR;
                                MdxService.log.log(MissionLogging.Category.COMMS, "Unable to send burst to " + ((config == null) ? DEFAULT_LINK_NAME : config.getIp()) + " - link error");
                                if (linkHealthListener != null)
                                    linkHealthListener.onLinkHealthChange(health);
                            } else {
                                if (linkHealthListener != null)
                                    linkHealthListener.onCommsFailure();
                            }*/
                        }
                        Log.d(Config.TAG, "Not sending burst; datalink is null");
                        //Log.d(Config.TAG, "Not sending burst; health is " + LinkHealth.getString(health));
                    }
                } else {
                    if (System.currentTimeMillis() > linkStartTime + TIME_TO_WAIT_FOR_LINK_TO_INITIATE) {
                        Log.d(Config.TAG, "Tried to send a burst over an unprepared uplink - trying to build the sockets again");
                        terminateLink(false);
                        setConfig(config);
                        buildSocket();
                    } else
                        Log.d(Config.TAG, "Tried to send a burst, but the link is still initializing");
                }
            });
        }
        return true;
    }

    private void buildSocket() {
        Log.d(Config.TAG,"buildSocket()");
        if (config != null) {
            String host = config.getIp();
            int port = config.getPort();
            InetSocketAddress address = new InetSocketAddress(host, port);
            uplink = null;
            try {
                uplink = SocketChannel.open(address);
                downlink = uplink;
                /*if (health != LinkHealth.IDLE) {
                    health = LinkHealth.IDLE;
                    MdxService.log.log(MissionLogging.Category.COMMS,"Link to "+config.getIp()+" established");
                    if (linkHealthListener != null)
                        linkHealthListener.onLinkHealthChange(health);
                }*/
            } catch (IOException e) {
                Log.e(Config.TAG,"Error initiating uplink: "+e.getMessage());
                /*if (health != LinkHealth.ERROR) {
                    health = LinkHealth.ERROR;
                    MdxService.log.log(MissionLogging.Category.COMMS,"Unable to initiate link to "+config.getIp());
                    if (linkHealthListener != null)
                        linkHealthListener.onLinkHealthChange(health);
                }*/
                try {
                    uplink.close();
                    /*if (health != LinkHealth.OFF) {
                        health = LinkHealth.OFF;
                        if (linkHealthListener != null)
                            linkHealthListener.onLinkHealthChange(health);
                    }*/
                } catch (Throwable t) {
                }
                close();
            } catch (Exception e) {
                /*health = LinkHealth.ERROR;
                MdxService.log.log(MissionLogging.Category.COMMS,"Error initiating uplink to "+config.getIp()+": "+e.getMessage());
                if (linkHealthListener != null)
                    linkHealthListener.onLinkHealthChange(health);*/
                e.printStackTrace();
                close();
            }
        }
    }

    public void close() {
        close(false);
    }

    public void close(boolean forceful) {
        if (isAlive()) {
            Log.d(Config.TAG, "SocketRelayThread.close() called");
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
            Log.d(Config.TAG, "Duplicate call to SocketRelayThread.close() ignored");
    }

    private void sendHangup() {
        if (datalink != null) {
            try {
                DisconnectingPacket packet = new DisconnectingPacket(Config.getThisDevice().getUUID());
                /*Device device = Device.getHealthForHangup();
                MdxPacket packet = MdxPacket.getHangupPacket(device.toProtoBuf().toByteArray());
                packet.getHeader().setNets(Config.getOrgNet(),Config.getTeamNet());
                if (!SpecificConfig.isSensor())
                    packet.getHeader().setAsUpstream();*/
                datalink.queue(packet, uplink);
                //MdxService.log.log(MissionLogging.Category.COMMS,"Notifying "+((config == null)?DEFAULT_LINK_NAME:config.getIp())+" that device is leaving");
            } catch (Exception ignore) {
            }
        }
    }

    private void terminateLink(boolean sendHangup) {
        Log.d(Config.TAG,"terminating socket link");
        if (sendHangup)
            sendHangup();
        /*if (health != LinkHealth.OFF) {
            health = LinkHealth.OFF;
            if (linkHealthListener != null) {
                linkHealthListener.onLinkHealthChange(health);
            }
        }
        linkHealthListener = null;*/
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
            //MdxService.log.log(MissionLogging.Category.COMMS,"Link to "+((config == null)?DEFAULT_LINK_NAME:config.getIp())+" terminated");
        }
        config = null;
    }
}