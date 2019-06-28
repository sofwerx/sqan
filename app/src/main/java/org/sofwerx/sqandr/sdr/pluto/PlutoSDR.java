package org.sofwerx.sqandr.sdr.pluto;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.sdr.AbstractSdr;

public class PlutoSDR extends AbstractSdr {
    private final static String TAG = Config.TAG+".SDR";
    private final static String TERMINAL_USERNAME = "root";
    private final static String TERMINAL_PASSWORD = "analog";
    public final static int VENDOR_ID = 1110;

    @Override
    protected boolean useSerialConnection() { return true; }

    @Override
    public boolean useMassStorage() { return true; }

    @Override
    protected String getTerminalUsername() { return TERMINAL_USERNAME; }

    @Override
    protected String getTerminalPassword() { return TERMINAL_PASSWORD; }

    @Override
    public void onConnect() {
        super.onConnect();
        if ((serialConnection != null) && serialConnection.isActive()) {
            dataConnection = serialConnection;
            commandConnection = serialConnection;
        }
    }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        if (dataConnection != null)
            dataConnection.setPeripheralStatusListener(listener);
    }
}
