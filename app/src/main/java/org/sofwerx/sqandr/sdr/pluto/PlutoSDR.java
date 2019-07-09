package org.sofwerx.sqandr.sdr.pluto;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.util.CommsLog;
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
        Log.d(TAG,"setPeripheralStatusListener("+((listener==null)?"null":"")+")");
        if (dataConnection != null)
            dataConnection.setPeripheralStatusListener(listener);
        if (serialConnection != null)
            serialConnection.setPeripheralStatusListener(listener);
        else
            CommsLog.log(CommsLog.Entry.Category.SDR,"Unable to set Peripheral Listener as the Serial Connection has not been assigned yet");
    }
}
