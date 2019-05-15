package org.sofwerx.sqan.manet.common.sockets;

import java.net.InetAddress;

public class SocketChannelConfig {
    private int port;
    private String ip;
    private InetAddress inetAddress;

    public SocketChannelConfig(InetAddress inetAddress, int port) {
        this.inetAddress = inetAddress;
        this.ip = null;
        this.port = port;
    }

    public SocketChannelConfig(String ip, int port) {
        this.inetAddress = null;
        this.ip = ip;
        this.port = port;
    }

    public int getPort() {
        return port;
    }
    public void setPort(int port) {
        this.port = port;
    }
    public String getIp() {
        return ip;
    }
    public void setIp(String ip) {
        this.ip = ip;
    }
    public InetAddress getInetAddress() { return inetAddress; }
}
