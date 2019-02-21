package org.sofwerx.sqan;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.UuidUtil;

import java.util.ArrayList;

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
    public final static String PREFS_MANET_ENGINE = "manetType";
    public final static String PREF_CLEAR_TEAM = "clearteam";
    private final static String PREFS_SAVED_TEAM = "savedteam";
    private static boolean debugMode = false;
    private static boolean allowIpcComms = true;
    private static boolean includeConnections = false;
    private static SqAnDevice thisDevice = null;
    private static ArrayList<SavedTeammate> savedTeammates;

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
        String rawTeam = prefs.getString(PREFS_SAVED_TEAM,null);
        if (rawTeam != null) {
            try {
                JSONArray array = new JSONArray(rawTeam);
                savedTeammates = new ArrayList<>();
                for (int i=0;i<array.length();i++) {
                    JSONObject jsonTeammate = array.getJSONObject(i);
                    if (jsonTeammate != null)
                        savedTeammates.add(new SavedTeammate(jsonTeammate));
                }
            } catch (JSONException e) {
                savedTeammates = null;
            }
        } else
            savedTeammates = null;
    }

    public static void savePrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if ((savedTeammates != null) && !savedTeammates.isEmpty()) {
            JSONArray rawTeammates = new JSONArray();
            for (SavedTeammate teammate:savedTeammates) {
                rawTeammates.put(teammate.toJSON());
            }
            edit.putString(PREFS_SAVED_TEAM, rawTeammates.toString());
        } else
            edit.remove(PREFS_SAVED_TEAM);
        edit.apply();
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

    public static SavedTeammate getTeammate(int sqAnAddress) {
        if (sqAnAddress == PacketHeader.BROADCAST_ADDRESS)
            return null;
        if (savedTeammates != null) {
            for (SavedTeammate teammate:savedTeammates) {
                if (sqAnAddress == teammate.sqAnAddress)
                    return teammate;
            }
        }
        return null;
    }

    public static SavedTeammate getTeammate(String netID) {
        if (netID == null)
            return null;
        if (savedTeammates != null) {
            for (SavedTeammate teammate:savedTeammates) {
                if (netID.equalsIgnoreCase(teammate.netID))
                    return teammate;
            }
        }
        return null;
    }

    public static void clearTeammates() {
        savedTeammates = null;
    }

    public static void saveTeammate(int sqAnAddress, String netID, String callsign) {
        SavedTeammate savedTeammate = getTeammate(sqAnAddress);
        if (savedTeammate == null) {
            savedTeammate = new SavedTeammate(sqAnAddress, netID);
            savedTeammate.setCallsign(callsign);
            if (savedTeammates == null)
                savedTeammates = new ArrayList<>();
            savedTeammates.add(savedTeammate);
            CommsLog.log(CommsLog.Entry.Category.COMMS,((callsign == null)?sqAnAddress:(callsign+"("+sqAnAddress+")"))+" saved as a teammate");
        } else
            savedTeammate.update(callsign,System.currentTimeMillis());
    }

    public static int getNumberOfSavedTeammates() {
        if (savedTeammates == null)
            return 0;
        return savedTeammates.size();
    }

    public static ArrayList<SavedTeammate> getSavedTeammates() { return savedTeammates; }

    public static class SavedTeammate {
        private String callsign;
        private int sqAnAddress;
        private String netID;
        private long lastContact;
        private MacAddress bluetoothMac;

        public SavedTeammate(JSONObject obj) {
            parseJSON(obj);
        }

        public SavedTeammate(int sqAnAddress, String netID) {
            this.callsign = null;
            this.netID = netID;
            this.sqAnAddress = sqAnAddress;
            this.lastContact = System.currentTimeMillis();
        }

        public void setCallsign(String callsign) { this.callsign = callsign; }
        public void setBluetoothMac(MacAddress mac) { this.bluetoothMac = mac; }

        public void update(SavedTeammate other) {
            if (other != null)
                update(other.callsign, other.lastContact);
        }

        public void update(String callsign, long lastContact) {
            if (callsign != null)
                this.callsign = callsign;
            if (lastContact > this.lastContact)
                this.lastContact = lastContact;
        }

        public JSONObject toJSON() {
            JSONObject obj = new JSONObject();
            try {
                obj.putOpt("callsign",callsign);
                obj.putOpt("netID",netID);
                obj.put("sqAnAddress",sqAnAddress);
                obj.put("lastContact",lastContact);
                if (bluetoothMac != null)
                    obj.put("btMac",bluetoothMac.toString());
            } catch (JSONException e) {
                e.printStackTrace();
            }
            return obj;
        }

        public void parseJSON(JSONObject obj) {
            if (obj != null) {
                callsign = obj.optString("callsign");
                netID = obj.optString("netID");
                sqAnAddress = obj.optInt("sqAnAddress",PacketHeader.BROADCAST_ADDRESS);
                lastContact = obj.optLong("lastContact",Long.MIN_VALUE);
                String bluetoothMacString = obj.optString("btMac",null);
                bluetoothMac = MacAddress.build(bluetoothMacString);
            }
        }

        public String getCallsign() { return callsign; }
        public int getSqAnAddress() { return sqAnAddress; }
        public long getLastContact() { return lastContact; }
        public MacAddress getBluetoothMac() { return bluetoothMac; }
        public String getNetID() { return netID; }
    }
}
