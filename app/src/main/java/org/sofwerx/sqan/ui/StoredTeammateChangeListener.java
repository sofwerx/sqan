package org.sofwerx.sqan.ui;

import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.common.MacAddress;

public interface StoredTeammateChangeListener {
    void onTeammateChanged(SavedTeammate teammate);
    void onDiscoveryNeeded();
    void onPairingNeeded(MacAddress bluetoothMac);
}
