package org.sofwerx.sqan;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.util.UuidUtil;

public class Config {
    public final static String TAG = "SqAN";
    private final static String PREFS_START_ON_REBOOT = "startonreboot";
    private final static String PREFS_AUTO_START = "autostart";
    private final static String PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION = "allowbatask";
    private final static String PREFS_DEBUG_MODE = "debugmode";
    private final static String PREFS_DEBUG_CONNECTION_MODE = "debugmx";
    private final static String PREFS_ALLOW_IPC_COMMS = "ipccomms";
    private final static String PREFS_UUID_EXTENDED = "uuid_extended";
    private final static String PREFS_UUID = "uuid";
    private final static String PREFS_CALLSIGN = "callsign";
    private static boolean debugMode = false;
    private static boolean allowIpcComms = true;
    private static boolean includeConnections = false;
    private static SqAnDevice thisDevice = null;

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        debugMode = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        allowIpcComms = prefs.getBoolean(PREFS_ALLOW_IPC_COMMS,true);
        includeConnections = prefs.getBoolean(PREFS_DEBUG_MODE,true);

        int uuid = prefs.getInt(PREFS_UUID,UuidUtil.getNewUUID());
        String uuidExtended = prefs.getString(PREFS_UUID_EXTENDED,UuidUtil.getNewExtendedUUID());
        String callsign = prefs.getString(PREFS_CALLSIGN,UuidUtil.getRandomCallsign());
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(PREFS_UUID,uuid);
        edit.putString(PREFS_UUID_EXTENDED,uuidExtended);
        edit.putString(PREFS_CALLSIGN,callsign);
        edit.apply();
        thisDevice = new SqAnDevice(uuid);
        SqAnDevice.remove(thisDevice); //no need to list this device in the devices
        thisDevice.setUuidExtended(uuidExtended);
        thisDevice.setCallsign(callsign);
    }

    public static boolean isAllowIpcComms() { return allowIpcComms; }
    public static boolean isDebugMode() { return debugMode; }
    public static boolean isDebugConnections() { return includeConnections; }
    public static SqAnDevice getThisDevice() { return thisDevice; }

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

    public static void setDebugConnectionMode(Context context, boolean active) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if (active == false)
            edit.putBoolean(PREFS_DEBUG_MODE,false);
        edit.putBoolean(PREFS_DEBUG_CONNECTION_MODE,active);
    }
}
