package org.sofwerx.sqan.manet.bt;

import org.sofwerx.sqan.manet.common.SqAnDevice;

@Deprecated
public interface BtSocketListener {
    void onConnectionError(String warning);
    void onBtSocketDisconnected(SqAnDevice device);
}
