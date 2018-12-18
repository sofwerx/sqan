package org.sofwerx.sqan;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.sofwerx.sqan.util.UuidUtil;

public class Config {
    public final static String TAG = "SqAN";
    private final static String PREFS_START_ON_REBOOT = "startonreboot";
    private final static String PREFS_AUTO_START = "autostart";
    private final static String PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION = "allowbatask";
    private final static String PREFS_DEBUG_MODE = "debugmode";
    private final static String PREFS_DEBUG_CONNECTION_MODE = "debugmx";
    private final static String PREFS_CALLSIGN = "callsign";
    private static boolean debugMode = false;
    private static boolean includeConnections = false;
    private static String callsign = null;

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        debugMode = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        includeConnections = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        callsign = prefs.getString(PREFS_CALLSIGN,null);
        if (callsign == null) {
            callsign = UuidUtil.getRandomCallsign();
            setCallsign(context,callsign);
        }
    }

    public static boolean isDebugMode() { return debugMode; }
    public static boolean isDebugConnections() { return includeConnections; }

    public static boolean isStartOnReboot(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_START_ON_REBOOT,false);
    }

    public static boolean isAutoStart(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_AUTO_START,false);
    }

    public static void setAutoStart(Context context, boolean autoStart) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREFS_AUTO_START,autoStart).apply();
    }

    public static boolean isAllowAskAboutBattery(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION,true);
    }

    public static void setNeverAskBatteryOptimize(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION,false).apply();
    }

    public static void setCallsign(Context context, String callsign) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (callsign == null)
            prefs.edit().remove(PREFS_CALLSIGN).apply();
        else
            prefs.edit().putString(PREFS_CALLSIGN,callsign).apply();
    }

    public static void setDebugConnectionMode(Context context, boolean active) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if (active == false)
            edit.putBoolean(PREFS_DEBUG_MODE,false);
        edit.putBoolean(PREFS_DEBUG_CONNECTION_MODE,active);
    }

    public static String getCallsign() {
        return callsign;
    }
}
