package org.sofwerx.sqan.manet;

import java.util.ArrayList;

public class SqAnDevice {
    private static ArrayList<SqAnDevice> devices;
    private String uuid;
    private String callsign;

    public SqAnDevice(String uuid) {
        this.uuid = uuid;
    }

    public SqAnDevice(String uuid, String callsign) {
        this(uuid);
        this.callsign = callsign;
    }

    public void update(SqAnDevice other) {
        if (other == null)
            return;
        if (other.callsign != null)
            callsign = other.callsign;
    }

    /**
     * Are these two SqAnDevices actually the same device (can have different settings, but resolve to the same unique device)
     * @param other
     * @return
     */
    public boolean isSame(SqAnDevice other) {
        if (other == null)
            return false;
        if (uuid == null)
            return false;
        return uuid.equalsIgnoreCase(other.uuid);
    }

    public static ArrayList<SqAnDevice> getDevices() {
        if (devices == null)
            return null;
        if (devices.isEmpty())
            devices = null;
        return devices;
    }

    /**
     * Add a new device to the list of devices
     * @param device
     * @return true == a new devices was added; false == this is a null or existing device
     */
    public static boolean add(SqAnDevice device) {
        if (device == null)
            return false;
        if (devices == null) {
            devices = new ArrayList<>();
            devices.add(device);
            return true;
        }
        SqAnDevice existing = find(device);
        if (existing == null) {
            devices.add(device);
            return true;
        } else
            existing.update(device);
        return false;
    }

    public static void clearAllDevices(ManetType type) {
        //TODO ignoring the type for now
        devices = null;
    }

    public static SqAnDevice find(SqAnDevice other) {
        if ((other != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (device.isSame(other))
                    return device;
            }
        }
        return null;
    }

    public String getCallsign() {
        return callsign;
    }
}
