package org.sofwerx.notdroid.net.wifi;

public class WifiP2pDevice {

    public static final int AVAILABLE = 0;
    public static final int FAILED = 1;
    public static final int INVITED = 2;
    public static final int CONNECTED = 3;
    public static final int UNAVAILABLE = 4;

    public String deviceName;

    public String deviceAddress;

    public boolean isGroupOwner() { return false; }

}
