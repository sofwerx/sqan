package org.sofwerx.sqan;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.UuidUtil;
import org.sofwerx.sqandr.sdr.SdrConfig;

import java.util.ArrayList;

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
    private final static String PREFS_PASSCODE = "passcode";
    public final static String PREFS_MANET_ENGINE = "manetType";
    public final static String PREF_CLEAR_TEAM = "clearteam";
    private final static String PREFS_SAVED_TEAM = "savedteam";
    public final static String PREFS_VPN_MODE = "vpnmode";
    public final static String PREFS_VPN_MULTICAST = "multicast";
    public final static String PREFS_VPN_EDIT_FORWARDS = "vpnfwdsettings";
    public final static String PREFS_IGNORE_0_0_0_0 = "no0000";
    public final static String PREFS_LARGE_DATA_WIFI_ONLY = "bigpipesonly";
    private final static String PREFS_VPN_LANDING_PAGE = "vpn404";
    public final static String PREFS_VPN_MTU = "mtu";
    public final static String PREFS_VPN_FORWARD = "vpnfwd";
    public final static String PREFS_VPN_AUTO_ADD = "vpnautoadd";
    public final static String PREFS_VPN_FORWARDED_IPS = "vpnfwdips";
    public final static String PREFS_WRITE_LOG = "log";
    public final static String PREFS_WARN_INCOMPLETE = "incomplete";
    public final static String PREFS_SDR_SETTINGS = "sdrsettings";
    public final static String PREFS_SDR_LISTEN_ONLY = "silent";
    private final static String DEFAULT_PASSCODE = "SwxTest";
    private static boolean debugMode = false;
    private static boolean allowIpcComms = true;
    private static boolean broadcastSa = true;
    private static boolean includeConnections = false;
    private static boolean vpnMode = true;
    private static boolean vpnForward = true;
    private static boolean vpnAutoAdd = true;
    private static boolean multicast = true;
    private static boolean vpnLandingPage = true;
    private static boolean writeLog = true;
    private static boolean warnIncomplete = true;
    private static boolean ignore0000 = true;
    private static boolean largeDataWiFiOnly = true;
    private static boolean silent = false;
    private static int mtuSize = 1500;
    private static SqAnDevice thisDevice = null;
    private static ArrayList<SavedTeammate> savedTeammates;
    private static String passcode;

    public static void init(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        recheckPreferences(context);

        int uuid = prefs.getInt(PREFS_UUID,UuidUtil.getNewUUID());
        String uuidExtended = prefs.getString(PREFS_UUID_EXTENDED,UuidUtil.getNewExtendedUUID());
        String callsign = prefs.getString(PREFS_CALLSIGN,UuidUtil.getRandomCallsign());
        passcode = prefs.getString(PREFS_PASSCODE,DEFAULT_PASSCODE);
        //TODO replace this with a negotiated passcode
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
        loadVpnForwardingIps(context);
        String rawTeam = prefs.getString(PREFS_SAVED_TEAM,null);
        if (rawTeam != null) {
            try {
                JSONArray array = new JSONArray(rawTeam);
                savedTeammates = new ArrayList<>();
                synchronized (savedTeammates) {
                    int unassignedBlockIndex = 0;
                    for (int i = 0; i < array.length(); i++) {
                        JSONObject jsonTeammate = array.getJSONObject(i);
                        if (jsonTeammate != null) {
                            SavedTeammate teammate = new SavedTeammate(jsonTeammate);
                            if (teammate.getSqAnAddress() == thisDevice.getUUID())
                                continue;
                            if (teammate.getSqAnAddress() < unassignedBlockIndex) {
                                if (teammate.getSqAnAddress() > -1000) //ignore cases where this is a very large negative
                                    unassignedBlockIndex = teammate.getSqAnAddress();
                            }
                            savedTeammates.add(teammate);
                        }
                    }
                    if (unassignedBlockIndex < 0)
                        SqAnDevice.setUnassignedBlockIndex(unassignedBlockIndex-1);
                    if (!savedTeammates.isEmpty()) {
                        for (SavedTeammate teammate:savedTeammates) {
                            if (teammate.isEnabled() && teammate.isUseful() && (teammate.getSqAnAddress() != SqAnDevice.UNASSIGNED_UUID))
                                new SqAnDevice(teammate);
                        }
                    }
                }
            } catch (JSONException e) {
                savedTeammates = null;
            }
        } else
            savedTeammates = null;
        if ((savedTeammates == null) || savedTeammates.isEmpty())
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"No saved teammates loaded");
        else
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,savedTeammates.size()+" teammate"+((savedTeammates.size()==1)?"":"s")+" loaded");
    }

    public static boolean isWarnIncompleteEnabled() { return warnIncomplete; }
    public static void setWarnIncompleteEnabled(boolean enabled) { warnIncomplete = enabled; }

    public static String getPasscode() { return passcode; }

    public static boolean isLoggingEnabled() { return writeLog; }

    public static String getCallsign(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(PREFS_CALLSIGN,null);
    }

    public static ArrayList<VpnForwardValue> getStoredVpnForwarding(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String values = prefs.getString(PREFS_VPN_FORWARDED_IPS,null);
        ArrayList<VpnForwardValue> out = null;
        if (values != null) {
            try {
                JSONArray array = new JSONArray(values);
                for (int i=0;i<array.length();i++) {
                    JSONObject obj = array.getJSONObject(i);
                    int trueIp = obj.optInt("trueip",0);
                    int forwaredAs = obj.optInt("fwdas",0);
                    if (trueIp !=0 ) {
                        if (out == null)
                            out = new ArrayList<>();
                        VpnForwardValue value = new VpnForwardValue((byte) (forwaredAs & 0xFF), trueIp);
                        out.add(value);
                        thisDevice.addVpnForwardValue(value);
                    }
                }
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return out;
    }

    public static void loadVpnForwardingIps(Context context) {
        if (thisDevice == null)
            return;
        ArrayList<VpnForwardValue> values = getStoredVpnForwarding(context);
        if (values != null) {
            for (VpnForwardValue value:values)
                thisDevice.addVpnForwardValue(value);
        }
    }

    public static void saveVpnForwardingIps(Context context, ArrayList<VpnForwardValue> values) {
        if (values == null)
            return;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        if (values.isEmpty())
            edit.remove(PREFS_VPN_FORWARDED_IPS);
        else {
            JSONArray array = new JSONArray();
            for (VpnForwardValue value : values) {
                try {
                    JSONObject obj = new JSONObject();
                    obj.put("trueip", value.getAddress());
                    obj.put("fwdas", (int) value.getForwardIndex());
                    array.put(obj);
                } catch (JSONException e) {
                    Log.d(TAG, "Unable to save VPN forwarding IPs: " + e.getMessage());
                }
            }
            edit.putString(PREFS_VPN_FORWARDED_IPS, array.toString());
        }
        edit.apply();
    }

    public static void recheckPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        debugMode = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        allowIpcComms = prefs.getBoolean(PREFS_ALLOW_IPC_COMMS,true);
        broadcastSa = prefs.getBoolean(PREFS_ALLOW_SA_BROADCAST,true);
        includeConnections = prefs.getBoolean(PREFS_DEBUG_MODE,true);
        vpnMode = prefs.getBoolean(PREFS_VPN_MODE,true);
        vpnForward = prefs.getBoolean(PREFS_VPN_FORWARD, true);
        vpnAutoAdd = prefs.getBoolean(PREFS_VPN_AUTO_ADD,true);
        multicast = prefs.getBoolean(PREFS_VPN_MULTICAST,true);
        vpnLandingPage = prefs.getBoolean(PREFS_VPN_MODE,true);
        writeLog = prefs.getBoolean(PREFS_WRITE_LOG,true);
        warnIncomplete = prefs.getBoolean(PREFS_WARN_INCOMPLETE,true);
        ignore0000 = prefs.getBoolean(PREFS_IGNORE_0_0_0_0,true);
        silent = prefs.getBoolean(PREFS_SDR_LISTEN_ONLY,false);
        largeDataWiFiOnly = prefs.getBoolean(PREFS_LARGE_DATA_WIFI_ONLY,true);
        try {
            mtuSize = Integer.parseInt(prefs.getString(PREFS_VPN_MTU, "1500"));
        } catch (NumberFormatException e) {
            mtuSize = 1500;
        }
        SdrConfig.init(context);
    }

    public static boolean isVpnEnabled() {
        return vpnMode;
    }

    public static boolean isVpnForwardIps() {
        //return vpnForward; //TODO uncomment this to enable VpnIpForwarding
        return false; //TODO comment this out if the above line is uncommented
    }

    public static boolean isVpnAutoAdd() { return vpnAutoAdd; }

    public static boolean isVpnHostLandingPage() {
        return vpnLandingPage;
    }

    public static void savePrefs(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        SdrConfig.saveToPrefs(context);
        updateSavedTeammates();
        if (vpnMode)
            edit.putBoolean(PREFS_VPN_MODE,true);
        edit.putBoolean(PREFS_WARN_INCOMPLETE,warnIncomplete);
        if ((savedTeammates != null) && !savedTeammates.isEmpty()) {
            synchronized (savedTeammates) {
                JSONArray rawTeammates = new JSONArray();
                for (SavedTeammate teammate : savedTeammates) {
                    rawTeammates.put(teammate.toJSON());
                }
                edit.putString(PREFS_SAVED_TEAM, rawTeammates.toString());
            }
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
                        teammate = new SavedTeammate(device.getUUID());
                        teammate.setNetID(device.getNetworkId());
                        teammate.setCallsign(device.getCallsign());
                        teammate.setBluetoothMac(device.getBluetoothMac());
                        teammate.setLastContact(device.getLastConnect());
                        if (teammate.isUseful()) {
                            if (savedTeammates == null)
                                savedTeammates = new ArrayList<>();
                            savedTeammates.add(teammate);
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "New teammate saved: " + teammate.getLabel() + " (" + savedTeammates.size() + " total)");
                        } else
                            Log.d(TAG,"Device "+device.getLabel()+" did not have any useful information to save as a teammate");
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
        cleanUpTeammates();
        dedupTeammates();
    }

    /**
     * Removes any teammate that does not have enough info to be reasonably useful
     */
    private static void cleanUpTeammates() {
        if ((savedTeammates != null) && !savedTeammates.isEmpty()) {
            synchronized (savedTeammates) {
                int i = 0;
                while (i < savedTeammates.size()) {
                    if ((savedTeammates.get(i) == null) || !savedTeammates.get(i).isUseful()) {
                        if (savedTeammates.get(i) != null)
                            CommsLog.log(CommsLog.Entry.Category.CONNECTION, "Saved teammate "+savedTeammates.get(i).getLabel()+" without sufficiently useful info found and removed");
                        else
                            Log.d(Config.TAG,"Null saved teammate removed during cleanUpTeammates");
                        savedTeammates.remove(i);
                    } else
                        i++;
                }
            }
        }
    }

    /**
     * Look for and merge any likely duplicate teammates
     * @return the teammate that absorbed a duplicate teammate
     */
    private static SavedTeammate dedupTeammates() {
        if ((savedTeammates == null) || (savedTeammates.size() < 2))
            return null;
        SavedTeammate merged = null;

        boolean scanNeeded = true;
        int inspectingIndex = 0;
        while ((merged == null) && (inspectingIndex < savedTeammates.size()) && scanNeeded) {
            SavedTeammate inspecting = savedTeammates.get(inspectingIndex);
            for (int i=0;i<savedTeammates.size();i++) {
                if ((merged == null) && (i != inspectingIndex)) {
                    SavedTeammate other = savedTeammates.get(i);
                    if ((other != null) && other.isLikelySame(inspecting)) {
                        if (((inspecting.getSqAnAddress() <= 0) && (other.getSqAnAddress() > 0)) || //if other has a valid UUID when inspecting does not
                                (!inspecting.isUseful() && other.isUseful()) ||        //or if other has useful info while inspecting does not
                                ((inspecting.getSqAnAddress() > 0) && (other.getSqAnAddress() > 0) && (other.getLastContact() > inspecting.getLastContact()))) { //or if other is more recent
                            merged = other;
                            if (merged != null)
                                merged.update(savedTeammates.get(inspectingIndex));
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Duplicate teammate detected; " + inspecting.getLabel() + " merged into " + other.getLabel());
                            savedTeammates.remove(inspectingIndex);
                        } else {
                            merged = inspecting;
                            if (merged != null)
                                merged.update(savedTeammates.get(i));
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Duplicate teammate detected; " + other.getLabel() + " merged into " + inspecting.getLabel());
                            savedTeammates.remove(i);
                        }
                    }
                }
            }
            inspectingIndex++;
        }

        return merged;
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
                if (sqAnAddress == teammate.getSqAnAddress())
                    return teammate;
            }
        }
        return null;
    }

    public static SavedTeammate getTeammateByBtMac(MacAddress mac) {
        if (mac != null) {
            if (savedTeammates != null) {
                synchronized (savedTeammates) {
                    for (SavedTeammate teammate : savedTeammates) {
                        if (mac.isEqual(teammate.getBluetoothMac()))
                            return teammate;
                    }
                }
            }
        }
        return null;
    }

    public static SavedTeammate getTeammateByWiFiMac(MacAddress mac) {
        if (mac != null) {
            if (savedTeammates != null) {
                synchronized (savedTeammates) {
                    for (SavedTeammate teammate : savedTeammates) {
                        if (mac.isEqual(teammate.getWiFiDirectMac()))
                            return teammate;
                    }
                }
            }
        }
        return null;
    }

    public static SavedTeammate getTeammate(String netID) {
        if (netID == null)
            return null;
        if (savedTeammates != null) {
            synchronized (savedTeammates) {
                for (SavedTeammate teammate : savedTeammates) {
                    if (netID.equalsIgnoreCase(teammate.getNetID()))
                        return teammate;
                }
            }
        }
        return null;
    }

    public static void clearTeammates() {
        savedTeammates = null;
        SqAnDevice.clearAllDevices(null);
    }

    public static SavedTeammate saveTeammate(SavedTeammate teammate) {
        if ((teammate != null) && teammate.isUseful()) {
            SavedTeammate old = getTeammate(teammate.getSqAnAddress());
            if (old == null)
                old = getTeammate(teammate.getNetID());
            if (old == null)
                old = getTeammateByBtMac(teammate.getBluetoothMac());
            if (old == null) {
                if (savedTeammates == null)
                    savedTeammates = new ArrayList<>();
                savedTeammates.add(teammate);
                CommsLog.log(CommsLog.Entry.Category.STATUS, "New teammate saved: " + teammate.getLabel() + " (" + savedTeammates.size() + " total)");
                old = teammate;
            } else
                old.update(teammate);
            return old;
        }
        return null;
    }

    public static SavedTeammate saveTeammate(int sqAnAddress, String netId, String callsign, MacAddress btMAC) {
        SavedTeammate savedTeammate = new SavedTeammate(sqAnAddress);
        savedTeammate.setNetID(netId);
        savedTeammate.setCallsign(callsign);
        savedTeammate.setBluetoothMac(btMAC);
        return saveTeammate(savedTeammate);
    }

    public static int getNumberOfSavedTeammates() {
        if (savedTeammates == null)
            return 0;
        return savedTeammates.size();
    }

    public static ArrayList<SavedTeammate> getSavedTeammates() { return savedTeammates; }

    public static void removeTeammate(SavedTeammate teammate) {
        if ((teammate != null) && (savedTeammates != null)) {
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Removing saved teammate "+teammate.getLabel());
            synchronized (savedTeammates) {
                int uuid = teammate.getSqAnAddress();
                savedTeammates.remove(teammate);
                if (uuid > 0) {
                    SqAnDevice device = SqAnDevice.findByUUID(uuid);
                    if (device != null)
                        SqAnDevice.remove(device);
                }
            }
        }
    }

    public static void setVpnEnabled(boolean b) {
        vpnMode = true;
    }
    public static boolean isIgnoringPacketsTo0000() { return ignore0000; }
    public static boolean isLargeDataWiFiOnly() { return largeDataWiFiOnly; }
    public static boolean isMulticastEnabled() { return multicast; }
    public static int getMtuSize() { return mtuSize; }
    public static boolean isListenOnyMode() { return silent; }
    //public static boolean portForwardingEnabled() { return true; /*TODO*/}
}
