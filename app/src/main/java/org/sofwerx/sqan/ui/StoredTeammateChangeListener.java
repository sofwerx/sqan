package org.sofwerx.sqan.ui;

import org.sofwerx.sqan.Config;

public interface StoredTeammateChangeListener {
    void onTeammateChanged(Config.SavedTeammate teammate);
    void onDiscoveryNeeded();
}
