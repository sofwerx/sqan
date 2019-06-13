package org.sofwerx.sqandr.sdr;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class SdrConfig {
    private final static String PREFS_SDR_MODE = "sdrmode";
    private final static String PREFS_SDR_TX_FREQ = "sdrtx";
    private final static String PREFS_SDR_RX_FREQ = "sdrrx";

    private static SdrMode mode;
    private static float txFreq;
    private static float rxFreq;
    private final static float DEFAULT_FREQ = 2500f; //MHz

    public static SdrMode getMode() { return mode; }
    public static void setMode(SdrMode mode) { SdrConfig.mode = mode; }
    public static float getTxFreq() { return txFreq; }
    public static void setTxFreq(float txFreq) { SdrConfig.txFreq = txFreq; }
    public static float getRxFreq() { return rxFreq; }
    public static void setRxFreq(float rxFreq) { SdrConfig.rxFreq = rxFreq; }

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        switch (prefs.getInt(PREFS_SDR_MODE,SdrMode.P2P.ordinal())) {
            case 0:
                mode = SdrMode.P2P;
                break;
        }
        txFreq = prefs.getFloat(PREFS_SDR_TX_FREQ,DEFAULT_FREQ);
        rxFreq = prefs.getFloat(PREFS_SDR_RX_FREQ,DEFAULT_FREQ);
    }

    public static void saveToPrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();

        edit.putInt(PREFS_SDR_MODE,mode.ordinal());
        edit.putFloat(PREFS_SDR_TX_FREQ,txFreq);
        edit.putFloat(PREFS_SDR_RX_FREQ,rxFreq);

        edit.apply();
    }
}
