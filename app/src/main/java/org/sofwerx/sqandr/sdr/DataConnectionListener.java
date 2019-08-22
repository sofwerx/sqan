package org.sofwerx.sqandr.sdr;

public interface DataConnectionListener {
    void onConnect();
    void onDisconnect();
    void onReceiveDataLinkData(byte[] data);
    void onReceiveCommandData(byte[] data);
    void onConnectionError(String message);
    void onPacketDropped();
    void onOperational();
    void onHighNoise(float snr);
}
