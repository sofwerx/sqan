package org.sofwerx.sqan.manet.common.sockets;

public class SocketChannelConfig {
    private int port;
    private String ip;

    public SocketChannelConfig(String ip, int port) {
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
}
