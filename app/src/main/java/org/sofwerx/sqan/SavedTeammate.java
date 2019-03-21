package org.sofwerx.sqan;

import org.json.JSONException;
import org.json.JSONObject;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;

public class SavedTeammate {
    private String callsign;
    private int sqAnAddress;
    private String netID;
    private long lastContact;
    private MacAddress bluetoothMac;
    private PairingStatus btPaired = PairingStatus.UNKNOWN;
    private enum PairingStatus {PAIRED,NOT_PAIRED,UNKNOWN}

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
        if (other.netID != null)
            netID = other.netID;
        if (other.bluetoothMac != null)
            bluetoothMac = other.bluetoothMac;
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
            sqAnAddress = obj.optInt("sqAnAddress", PacketHeader.BROADCAST_ADDRESS);
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
    public void setBluetoothMac(MacAddress mac) {
        if ((this.bluetoothMac != null) && !this.bluetoothMac.isEqual(mac))
            btPaired = PairingStatus.UNKNOWN;
        this.bluetoothMac = mac;
    }
    public void setNetID(String networkId) {
        this.netID = networkId;
        if ((this.netID != null) && (this.netID.length() < 1))
            this.netID = null;
    }
    public void setLastContact(long time) { this.lastContact = time; }

    public boolean isUseful() { return (sqAnAddress > 0) || (bluetoothMac != null) || (netID != null); }

    public boolean isLikelySame(SavedTeammate other) {
        if (other == null)
            return false;
        boolean same = false;
        if ((other.sqAnAddress > 0) && (sqAnAddress > 0))
                same = (other.sqAnAddress == sqAnAddress);
        if ((other.bluetoothMac != null) && (bluetoothMac != null))
            same = same || other.bluetoothMac.isEqual(bluetoothMac);
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

    public void setBtPaired(boolean paired) {
        if (paired)
            btPaired = PairingStatus.PAIRED;
        else
            btPaired = PairingStatus.NOT_PAIRED;
    }
}
