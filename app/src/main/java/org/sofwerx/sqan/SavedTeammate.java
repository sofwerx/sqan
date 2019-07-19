package org.sofwerx.sqan;

import org.json.JSONException;
import org.json.JSONObject;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.StringUtil;

public class SavedTeammate {
    private String callsign;
    private int sqAnAddress;
    private String netID; //network ID is transient and should not be persisted
    private long lastContact;
    private MacAddress bluetoothMac;
    private MacAddress wifiDirectMac;
    private String btName;
    private PairingStatus btPaired = PairingStatus.UNKNOWN;
    private boolean enabled = true;

    public boolean isIncomplete(boolean checkBt, boolean checkWiFi) {
        boolean problem = false;
        if (checkBt)
            problem = !isBtPaired();
        //TODO if (checkWiFi)
        //    problem = problem || (netID == null) || (netID.length() < 2);
        return problem;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Callsign: ");
        if (callsign == null)
            sb.append("NONE");
        else
            sb.append(callsign);
        sb.append(", SqAN ID: ");
        sb.append(Integer.toString(sqAnAddress));
        sb.append(", last contact: ");
        if (lastContact <0l)
            sb.append("NEVER");
        else
            sb.append(StringUtil.toDuration(System.currentTimeMillis() - lastContact));
        sb.append(", BT MAC: ");
        if ((bluetoothMac == null) || !bluetoothMac.isValid())
            sb.append("UNKNOWN");
        else
            sb.append(bluetoothMac.toString());
        sb.append(", WiFi Direct MAC: ");
        if ((wifiDirectMac == null) || !wifiDirectMac.isValid())
            sb.append("UNKNOWN");
        else
            sb.append(wifiDirectMac.toString());
        if (btPaired != null) {
            sb.append(",BT pairing ");
            sb.append(btPaired.name());
        }
        if (netID != null) {
            sb.append(", transient WiFi ID ");
            sb.append(netID);
        }
        if (enabled)
            sb.append(", teammate is enabled");
        else
            sb.append(", teammate is disabled");

        return sb.toString();
    }

    /**
     * Provides the best human-readable way to reference this saved teammate
     * @return
     */
    public String getLabel() {
        if (callsign != null)
            return callsign;
        if (btName != null)
            return btName;
        if (sqAnAddress > 0)
            return Integer.toString(sqAnAddress);
        if ((wifiDirectMac != null) && wifiDirectMac.isValid())
            return wifiDirectMac.toString();
        if ((bluetoothMac != null) && bluetoothMac.isValid())
            return bluetoothMac.toString();
        if ((netID != null) && (netID.length() > 1))
            return netID;
        return "unknown";
    }

    public MacAddress getWiFiDirectMac() { return wifiDirectMac; }

    public void setWiFiDirectMac(MacAddress wifiDirectMac) { this.wifiDirectMac = wifiDirectMac; }
    public void setWiFiDirectMac(String mac) { wifiDirectMac = MacAddress.build(mac); }

    private enum PairingStatus {PAIRED,NOT_PAIRED,UNKNOWN}

    public SavedTeammate(JSONObject obj) {
        parseJSON(obj);
    }

    public SavedTeammate(int sqAnAddress) {
        this.callsign = null;
        this.netID = null;
        this.sqAnAddress = sqAnAddress;
        this.lastContact = System.currentTimeMillis();
    }

    public void update(SavedTeammate other) {
        if (other != null)
            update(other.callsign, other.lastContact);
        if (other.netID != null)
            netID = other.netID;
        if (other.bluetoothMac != null)
            bluetoothMac = other.bluetoothMac;
        if (other.wifiDirectMac != null)
            wifiDirectMac = other.wifiDirectMac;
        enabled = other.enabled;
        if (other.btName != null)
            btName = other.btName;
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
            obj.put("enabled",enabled);
            obj.putOpt("btname",btName);
            if (bluetoothMac != null)
                obj.put("btMac",bluetoothMac.toString());
            if (wifiDirectMac != null)
                obj.put("wifiMac",wifiDirectMac.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return obj;
    }

    public void parseJSON(JSONObject obj) {
        if (obj != null) {
            callsign = obj.optString("callsign",null);
            netID = obj.optString("netID");
            sqAnAddress = obj.optInt("sqAnAddress", PacketHeader.BROADCAST_ADDRESS);
            lastContact = obj.optLong("lastContact",Long.MIN_VALUE);
            bluetoothMac = MacAddress.build(obj.optString("btMac",null));
            wifiDirectMac = MacAddress.build(obj.optString("wifiMac",null));
            btName = obj.optString("btname",null);
            enabled = obj.optBoolean("enabled");
            if ((callsign != null) && (callsign.length() == 0))
                callsign = null;
        }
    }

    public String getCallsign() { return callsign; }
    public int getSqAnAddress() { return sqAnAddress; }
    public long getLastContact() { return lastContact; }
    public MacAddress getBluetoothMac() { return bluetoothMac; }
    public void setCallsign(String callsign) { this.callsign = callsign; }
    public void setBluetoothMac(MacAddress mac) {
        if ((this.bluetoothMac != null) && !this.bluetoothMac.isEqual(mac))
            btPaired = PairingStatus.UNKNOWN;
        this.bluetoothMac = mac;
    }

    public String getNetID() { return netID; }
    public void setNetID(String networkId) {
        this.netID = networkId;
        if ((this.netID != null) && (this.netID.length() < 1))
            this.netID = null;
    }

    public void setLastContact(long time) { this.lastContact = time; }

    public boolean isUseful() { return (sqAnAddress > 0) || ((bluetoothMac != null) && bluetoothMac.isValid()); }

    public boolean isLikelySame(SavedTeammate other) {
        if (other == null)
            return false;
        boolean same = false;
        if ((other.sqAnAddress > 0) && (sqAnAddress > 0))
            same = (other.sqAnAddress == sqAnAddress);
        if ((other.bluetoothMac != null) && (bluetoothMac != null))
            same = same || other.bluetoothMac.isEqual(bluetoothMac);
        if ((other.wifiDirectMac != null) && (wifiDirectMac != null))
            same = same || other.wifiDirectMac.isEqual(wifiDirectMac);
        if ((other.netID != null) && (other.netID.length() > 1) && (netID != null) && (netID.length() > 1))
            same = same || other.netID.equalsIgnoreCase(netID);
        return same;
    }
    public void setSqAnId(int uuid) { sqAnAddress = uuid; }

    /**
     * Is this mac a saved pairing for Bluetooth
     * @return true == saved pairing
     */
    public boolean isBtPaired() { return btPaired == PairingStatus.PAIRED; }
    public boolean isBtPairingStatusKnown() { return btPaired != PairingStatus.UNKNOWN; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBtName() { return btName; }
    public void setBtName(String name) { this.btName = name; }

    public void setBtPaired(boolean paired) {
        if (paired)
            btPaired = PairingStatus.PAIRED;
        else
            btPaired = PairingStatus.NOT_PAIRED;
    }
}
