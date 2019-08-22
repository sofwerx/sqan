package org.sofwerx.sqandr;

public interface SqANDRListener {
    void onSdrError(String message);
    void onSdrReady(boolean isReady);
    void onSdrMessage(String message);
    void onPacketReceived(byte[] data);
    void onPacketDropped();
    void onHighNoise(float snr);
}
