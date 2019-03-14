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

import javax.crypto.Mac;

public class Config {
    public final static String TAG = "SqAN";
    private final static String PREFS_START_ON_REBOOT = "startonreboot";
    private final static String PREFS_AUTO_START = "autostart";
    private final static String PREFS_ALLOW_ASK_BATTERY_OPTIMIZATION = "allowbatask";
    private final static String PREFS_DEBUG_MODE = "debugmode";
    private final static String PREFS_DEBUG_CONNECTION_MODE = "debugmx";
    private final static String PREFS_ALLOW_IPC_COMMS = "ipccomms";
    private final static String PREFS_ALLOW_SA_BROADCAST = "sa";
    private final static String PREFS_UUID_EXTENDED = "uuid_extended";
    private final static String PREFS_UUID = "uuid";
    private final static String PREFS_CALLSIGN = "callsign";
    public final static String PREFS_MANET_ENGINE = "manetType";
    public final static String PREF_CLEAR_TEAM = "clearteam";
    private final static String PREFS_SAVED_TEAM = "savedteam";
    public final static String PREFS_VPN_MODE = "vpnmode";
    private final static String PREFS_VPN_LANDING_PAGE = "vpn404";
    private static boolean debugMode = false;
    private static boolean allowIpcComms = true;
    private static boolean broadcastSa = true;
    private static boolean includeConnections = false;
    private static boolean vpnMode = true;
    private static boolean vpnLandingPage = true;
    private static SqAnDevice thisDevice = null;
    private static ArrayList<SavedTeammate> savedTeammates;

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        recheckPreferences(context);

        int uuid = prefs.getInt(PREFS_UUID,UuidUtil.getNewUUID());
        String uuidExtended = prefs.getString(PREFS_UUID_EXTENDED,UuidUtil.getNewExtendedUUID());
        String callsign = prefs.getString(PREFS_CALLSIGN,UuidUtil.getRandomCallsign());
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(PREFS_UUID,uuid);
        edit.putString(PREFS_UUID_EXTENDED,uuidExtended);
        edit.putString(PREFS_CALLSIGN,callsign);
        edit.apply();
        thisDevice = new SqAnDevice();
        thisDevice.setUUID(uuid);
        SqAnDevice.remove(thisDevice); //dont list this device in the list of other devices
        thisDevice.setUuidExtended(uuidExtended);
        thisDevice.setCallsign(callsign);
        String rawTeam = prefs.getString(PREFS_SAVED_TEAM,null);
        if (rawTeam != null) {
            try {
                JSONArray array = new JSONArray(rawTeam);
                savedTeammates = new ArrayList<>();
                for (int i=0;i<array.length();i++) {
                    JSONObject jsonTeammate = array.getJSONObject(i);
                    if (jsonTeammate != null) {
                        SavedTeammate teammate = new SavedTeammate(jsonTeammate);
                        if (teammate.getSqAnAddress() == thisDevice.getUUID())
                            continue;
                        savedTeammates.add(new SavedTeammate(jsonTeammate));
                    }
                }
            } catch (JSONException e) {
                savedTeammates = null;
            }
        } else
            savedTeammates = null;
    }

    public static void recheckPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        debugMode = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        allowIpcComms = prefs.getBoolean(PREFS_ALLOW_IPC_COMMS,true);
        broadcastSa = prefs.getBoolean(PREFS_ALLOW_SA_BROADCAST,true);
        includeConnections = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        vpnMode = prefs.getBoolean(PREFS_VPN_MODE,true);
        vpnLandingPage = prefs.getBoolean(PREFS_VPN_MODE,true);
    }

    public static boolean isVpnEnabled() {
        return vpnMode;
    }

    public static boolean isVpnHostLandingPage() {
        return vpnLandingPage;
    }

    public static void savePrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        updateSavedTeammates();
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

    public static void updateSavedTeammates() {
        ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
        if (devices != null) {
            for (SqAnDevice device:devices) {
                if (device.getUUID() > 0) {
                    SavedTeammate teammate = getTeammate(device.getUUID());
                    if (teammate == null) {
                        teammate = new SavedTeammate(device.getUUID(),device.getNetworkId());
                        teammate.setCallsign(device.getCallsign());
                        teammate.setBluetoothMac(device.getBluetoothMac());
                        teammate.setLastContact(device.getLastConnect());
                        savedTeammates.add(teammate);
                    } else {
                        if (device.getCallsign() != null)
                            teammate.setCallsign(device.getCallsign());
                        if (device.getBluetoothMac() != null)
                            teammate.setBluetoothMac(device.getBluetoothMac());
                        if (device.getNetworkId() != null)
                            teammate.setNetID(device.getNetworkId());
                        if (device.getLastConnect() > teammate.getLastContact())
                            teammate.setLastContact(device.getLastConnect());
                    }
                }
            }
        }
    }

    public static boolean isAllowIpcComms() { return allowIpcComms; }
    public static boolean isBroadcastSa() { return broadcastSa; }
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
        if ((sqAnAddress < 0) || (sqAnAddress == PacketHeader.BROADCAST_ADDRESS))
            return null;
        if (savedTeammates != null) {
            for (SavedTeammate teammate:savedTeammates) {
                if (sqAnAddress == teammate.sqAnAddress)
                    return teammate;
            }
        }
        return null;
    }

    public static SavedTeammate getTeammateByBtMac(MacAddress mac) {
        if (mac != null) {
            if (savedTeammates != null) {
                for (SavedTeammate teammate : savedTeammates) {
                    if (mac.isEqual(teammate.bluetoothMac))
                        return teammate;
                }
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

    public static SavedTeammate saveTeammate(int sqAnAddress, String netID, String callsign) {
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
        return savedTeammate;
    }

    public static int getNumberOfSavedTeammates() {
        if (savedTeammates == null)
            return 0;
        return savedTeammates.size();
    }

    public static ArrayList<SavedTeammate> getSavedTeammates() { return savedTeammates; }

    public static void removeTeammate(SavedTeammate teammate) {
        if ((teammate != null) && (savedTeammates != null)) {
            int uuid = teammate.getSqAnAddress();
            savedTeammates.remove(teammate);
            if (uuid > 0) {
                SqAnDevice device = SqAnDevice.findByUUID(uuid);
                if (device != null)
                    SqAnDevice.remove(device);
            }
        }
    }

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

        public void update(SavedTeammate other) {
            if (other != null)
                update(other.callsign, other.lastContact);
        }

        public void update() { lastContact = System.currentTimeMillis(); }

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
                callsign = obj.optString("callsign",null);
                netID = obj.optString("netID");
                sqAnAddress = obj.optInt("sqAnAddress",PacketHeader.BROADCAST_ADDRESS);
                lastContact = obj.optLong("lastContact",Long.MIN_VALUE);
                String bluetoothMacString = obj.optString("btMac",null);
                bluetoothMac = MacAddress.build(bluetoothMacString);
                if ((callsign != null) && (callsign.length() == 0))
                    callsign = null;
            }
        }

        public String getCallsign() { return callsign; }
        public int getSqAnAddress() { return sqAnAddress; }
        public long getLastContact() { return lastContact; }
        public MacAddress getBluetoothMac() { return bluetoothMac; }
        public String getNetID() { return netID; }
        public void setCallsign(String callsign) { this.callsign = callsign; }
        public void setBluetoothMac(MacAddress mac) { this.bluetoothMac = mac; }
        public void setNetID(String networkId) { this.netID = networkId; }
        public void setLastContact(long time) { this.lastContact = time; }
    }
}
