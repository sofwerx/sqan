package org.sofwerx.sqandr.sdr.lime;

import android.content.Context;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.AbstractSdr;

import java.io.InputStream;
import java.io.OutputStream;

public class LimeSDR extends AbstractSdr {
    private final static String TAG = Config.TAG+".SDR";
    public final static int VENDOR_ID = 1027;

    @Override
    public String getInfo(Context context) {
        return super.getInfo(context);
    }

    @Override
    protected boolean useSerialConnection() { return false; }

    @Override
    public boolean useMassStorage() { return false; }

    @Override
    protected String getTerminalUsername() { return null; }

    @Override
    protected String getTerminalPassword() { return null; }
}
