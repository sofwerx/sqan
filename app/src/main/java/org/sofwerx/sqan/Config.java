package org.sofwerx.sqan;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class Config {
    public final static String TAG = "SqAN";
    private final static String PREFS_START_ON_REBOOT = "startonreboot";
    private final static String PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION = "allowbatask";
    private final static String PREFS_DEBUG_MODE = "debugmode";
    private final static String PREFS_DEBUG_CONNECTION_MODE = "debugmx";
    private static boolean debugMode = false;
    private static boolean includeConnections = false;

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        debugMode = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        includeConnections = prefs.getBoolean(PREFS_DEBUG_MODE,true);
    }

    public static boolean isDebugMode() { return debugMode; }
    public static boolean isDebugConnections() { return includeConnections; }

    public static boolean isStartOnReboot(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_START_ON_REBOOT,false);
    }

    public static boolean isAllowAskAboutBattery(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION,true);
    }

    public static void setNeverAskBatteryOptimize(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION,false).apply();
    }

    public static void setDebugConnectionMode(Context context, boolean active) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if (active == false)
            edit.putBoolean(PREFS_DEBUG_MODE,false);
        edit.putBoolean(PREFS_DEBUG_CONNECTION_MODE,active);
    }
}
