package org.sofwerx.sqan.listeners;

import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.manet.packet.AbstractPacket;

public interface ManetListener {
    void onStatusChanged(Status status);
    void onRx(AbstractPacket packet);
}
