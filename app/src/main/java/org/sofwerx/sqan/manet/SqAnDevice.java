package org.sofwerx.sqan.manet;

import java.util.ArrayList;
import java.util.List;

public class SqAnDevice {
    private final static long TIME_TO_STALE = 1000l * 60l;
    private static ArrayList<SqAnDevice> devices;
    private String uuid;
    private String callsign;
    private long lastConnect = Long.MIN_VALUE;

    public SqAnDevice(String uuid) {
        this.uuid = uuid;
    }

    public SqAnDevice(String uuid, String callsign) {
        this(uuid);
        this.callsign = callsign;
    }

    /**
     * Generates a list of UUIDs of currently active devices
     * @return
     */
    public static List<String> getActiveDevicesUuid() {
        if ((devices == null) || devices.isEmpty())
            return null;
        ArrayList<String> active = new ArrayList<>();
        for (SqAnDevice device:devices) {
            if (device.isActive())
                active.add(device.uuid);
        }
        if (active.isEmpty())
            active = null;
        return active;
    }

    public String getUUID() {
        return uuid;
    }

    public void update(SqAnDevice other) {
        if (other == null)
            return;
        if (other.callsign != null)
            callsign = other.callsign;
    }

    public static int getActiveConnections() {
        if ((devices == null) || devices.isEmpty())
            return 0;
        int sum = 0;

        for (SqAnDevice device:devices) {
            if (device.isActive())
                sum++;
        }

        return sum;
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

    /**
     * Is this device identifiable with this UUID
     * @param uuid
     * @return true == this is the same device
     */
    public boolean isSame(String uuid) {
        if (uuid == null)
            return false;
        return uuid.equalsIgnoreCase(this.uuid);
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

    /**
     * Finds a device in the list of devices based on UUID
     * @param other
     * @return the device (or null if the device is not found)
     */
    public static SqAnDevice find(SqAnDevice other) {
        if ((other != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (device.isSame(other))
                    return device;
            }
        }
        return null;
    }

    /**
     * Finds a device in the list of devices based on UUID
     * @param uuid
     * @return the device (or null if UUID is not found)
     */
    public static SqAnDevice find(String uuid) {
        if ((uuid != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (device.isSame(uuid))
                    return device;
            }
        }
        return null;
    }

    public String getCallsign() {
        return callsign;
    }

    public void setConnected() {
        setLastConnect(System.currentTimeMillis());
    }

    public void setLastConnect(long time) {
        lastConnect = time;
    }

    /**
     * A last connected time less than 0 is used to signal that the device is disconnected
     * @return
     */
    public boolean isDisconnected() {
        return (lastConnect < 0l);
    }

    public boolean isActive() {return !isDeviceStale(); }

    public void setDisconnected() { lastConnect = Long.MIN_VALUE; }

    /**
     * Has this device not provided any data in a while and should be considered as a stale connection
     * @return
     */
    public boolean isDeviceStale() {
        if (isDisconnected())
            return true;
        return (System.currentTimeMillis() > (lastConnect + TIME_TO_STALE));
    }
}
