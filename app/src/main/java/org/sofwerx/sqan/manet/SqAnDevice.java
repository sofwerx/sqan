package org.sofwerx.sqan.manet;

import java.util.ArrayList;
import java.util.List;

public class SqAnDevice {
    private final static long TIME_TO_STALE = 1000l * 60l;
    private static ArrayList<SqAnDevice> devices;
    private String uuid; //this is the persistent ID for this device
    private String networkId; //this is the transient MANET ID for this device
    private long lastConnect = Long.MIN_VALUE;
    private long rxDataTally = 0l; //talley of received bytes from this node
    private Status status = Status.OFFLINE;

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     */
    public SqAnDevice(String uuid) {
        this.uuid = uuid;
        SqAnDevice.add(this);
    }

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     * @param networkId == the transient ID assigned to this device for this session on this MANET
     */
    public SqAnDevice(String uuid, String networkId) {
        this(uuid);
        this.networkId = networkId;
    }

    /**
     * ONLINE == device is visible but not ready to receive network packets
     * CONNECTED == device can receive network packets
     * STALE == device should be CONNECTED but has not checked in in a while
     * ERROR == device is having problems connecting
     * OFFLINE == device is not visible on the network
     */
    public enum Status {
        ONLINE,
        CONNECTED,
        //TODO add a CHALLENGING status to support encrypted handshakes within network
        //TODO add a COUNTERSIGNING status to support encrypted handshakes within network
        //FIXME note: encrypted internal connections are outside opf the scope of this project and will be handled sepsrately
        STALE,
        ERROR,
        OFFLINE
    }

    public void setStatus(Status status) { this.status = status; }

    public Status getStatus() {
        //a stale check is conducted on any CONNECTED device each time this is called
        if ((status == Status.CONNECTED) && (System.currentTimeMillis() > lastConnect + TIME_TO_STALE))
            status = Status.STALE;
        return status;
    }

    public boolean isActive() {
        return ((status == Status.CONNECTED) || (status == Status.STALE));
    }

    /**
     * Generates a list of UUIDs of currently active devices
     * @return
     */
    public static List<String> getActiveDevicesNetworkIds() {
        if ((devices == null) || devices.isEmpty())
            return null;
        ArrayList<String> active = new ArrayList<>();
        for (SqAnDevice device:devices) {
            if (device.isActive())
                active.add(device.networkId);
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
        if (other.networkId != null)
            networkId = other.networkId;
    }

    public static int getActiveConnections() {
        if ((devices == null) || devices.isEmpty())
            return 0;
        int sum = 0;

        for (SqAnDevice device:devices) {
            if (device.status == Status.CONNECTED)
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
    public static SqAnDevice findByUUID(String uuid) {
        if ((uuid != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (device.isSame(uuid))
                    return device;
            }
        }
        return null;
    }

    /**
     * Finds a device in the list of devices based on NetworkID
     * @param networkId
     * @return the device (or null if NetworkID is not found)
     */
    public static SqAnDevice findByNetworkID(String networkId) {
        if ((networkId != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if ((device.networkId != null) && device.networkId.equalsIgnoreCase(networkId))
                    return device;
            }
        }
        return null;
    }

    /**
     * Adds to the running tally of how many bytes have been received from this device. Intended
     * for performance metrics use
     * @param sizeInBytes
     */
    public void addToDataTally(int sizeInBytes) {
        rxDataTally += sizeInBytes;
    }

    /**
     * Gets the total tally of bytes received from this device so far
     * @return tally (in bytes)
     */
    public long getDataTally() {
        return rxDataTally;
    }
    public String getNetworkId() {
        return networkId;
    }
    public void setNetworkId(String networkId) { this.networkId = networkId; }
    public void setConnected() {
        status = Status.CONNECTED;
        setLastConnect(System.currentTimeMillis());
    }
    public void setLastConnect(long time) {
        lastConnect = time;
    }

    /*public String getSimplifiedUUID() {
        if (uuid == null)
            return null;
        String[] parts = uuid.split("-");
        if (parts == null)
            return uuid;
        return "…"+parts[parts.length-1];
    }*/
}
