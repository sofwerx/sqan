package org.sofwerx.sqan.listeners;

public interface PeripheralStatusListener {
    void onPeripheralMessage(String message);
    void onPeripheralReady();
    void onPeripheralError(String message);
    void onHighNoise(float snr);
}
