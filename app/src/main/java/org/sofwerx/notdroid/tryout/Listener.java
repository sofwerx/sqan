package org.sofwerx.notdroid.tryout;

import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqandr.SqANDRListener;

public class Listener implements SqANDRListener, ManetListener {

    @Override
    public void onSdrError(String message) {

    }

    @Override
    public void onSdrReady(boolean isReady) {

    }

    @Override
    public void onSdrMessage(String message) {

    }

    @Override
    public void onPacketReceived(byte[] data) {

    }

    @Override
    public void onPacketDropped() {

    }

    @Override
    public void onHighNoise(float snr) {

    }

    @Override
    public void onStatus(Status status) {

    }

    @Override
    public void onRx(AbstractPacket packet) {

    }

    @Override
    public void onTx(AbstractPacket packet) {

    }

    @Override
    public void onTx(byte[] payload) {

    }

    @Override
    public void onTxFailed(AbstractPacket packet) {

    }

    @Override
    public void onDevicesChanged(SqAnDevice device) {

    }

    @Override
    public void updateDeviceUi(SqAnDevice device) {

    }

    @Override
    public void onAuthenticatedOnNet() {

    }
}
