package org.sofwerx.sqan.manet.common;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.pnt.NetworkTime;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;
import org.sofwerx.sqan.util.CommsLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SqAnDevice {
    public final static int UNASSIGNED_UUID = Integer.MIN_VALUE;
    private static AtomicInteger nextUnassignedUUID = new AtomicInteger(-1);
    private final static long TIME_TO_STALE = 1000l * 60l;
    private final static int MAX_LATENCY_HISTORY = 100; //the max number of latency records to keep
    private static ArrayList<SqAnDevice> devices;
    private int uuid; //this is the persistent SqAN ID for this device
    private String callsign; //this is the callsign which also acts as the domain name for this device
    private String uuidExtended; //this is the persistent ID for this device used solely to look for conflicts
    private String networkId; //this is the transient MANET ID for this device
    private long lastConnect = Long.MIN_VALUE;
    private long rxDataTally = 0l; //talley of received bytes from this node
    private Status status = Status.OFFLINE;
    private ArrayList<Long> latencies;
    private long discoveryTime = -1l; //used to mark when this device was discovered
    private long connectTime = -1l; //used to mark when this device was connected
    private CommsLog.Entry lastEntry = null;
    private SpaceTime lastLocation = null;

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     */
    public SqAnDevice(int uuid) {
        if (uuid == UNASSIGNED_UUID)
            this.uuid = nextUnassignedUUID.decrementAndGet();
        else
            this.uuid = uuid;
        SqAnDevice.add(this);
    }

    /**
     * Constructor that creates a placeholder UUID that is later intended to be updated
     * once the actual UUID is known
     */
    public SqAnDevice() {
        this(UNASSIGNED_UUID);
    }

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     * @param networkId == the transient ID assigned to this device for this session on this MANET
     */
    public SqAnDevice(int uuid, String networkId) {
        this(uuid);
        this.networkId = networkId;
    }

    /**
     * Gets the last CommsLog entry of interest for this device
     * @return
     */
    public CommsLog.Entry getLastEntry() { return lastEntry; }

    /**
     * Sets the last CommsLog entry of interest for this device
     * @param lastEntry
     */
    public void setLastEntry(CommsLog.Entry lastEntry) { this.lastEntry = lastEntry; }

    /**
     * Sets the short uuid for this device. This should not be changed with the
     * very rare exception of a collision with another device
     * @param uuid
     */
    public void setUUID(int uuid) { this.uuid = uuid; }

    /**
     * Gets the device specific UUID; this is (almost always) immutable and singularly
     * associated with a single device
     * @return
     */
    public int getUUID() {
        return uuid;
    }

    /**
     * Gets the callsign for this device; this callsign can be changed and also acts as
     * the domain name for this device
     * @return
     */
    public String getCallsign() {
        return callsign;
    }

    /**
     * Sets the callsign for this device; this callsign can be changed and also acts as
     * the domain name for this device
     * @param callsign
     */
    public void setCallsign(String callsign) {
        this.callsign = callsign;
    }

    public SpaceTime getLastLocation() {
        return lastLocation;
    }

    public void setLastLocation(SpaceTime lastLocation) {
        this.lastLocation = lastLocation;
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

    /**
     * Gets the last measured latency (average one-way)
     * @return latency in milliseconds (or < 0 if no latency has been recorded)
     */
    public long getLastLatency() {
        if ((latencies == null) || latencies.isEmpty())
            return -1l;
        return latencies.get(latencies.size()-1)/2l; //total latency divided by 2 to get average one-way
    }

    /**
     * Records a latency measurement
     * @param latency latency (round trip in milliseconds)
     */
    public void addLatencyMeasurement(long latency) {
        if (latency < 0l) {
            Log.d(Config.TAG,"A negative latency reported which doesn\'t make sense. Ignoring");
            return;
        }
        if (latencies == null)
            latencies = new ArrayList<>();
        latencies.add(latency);
        while (latencies.size() > MAX_LATENCY_HISTORY)
            latencies.remove(0);
    }

    public long getAverageLatency() {
        if ((latencies == null) || latencies.isEmpty())
            return -1l;
        long sum = 0;
        long divisor = 0l;
        for (long entry:latencies) {
            if (entry > 0l) {
                sum += entry;
                divisor++;
            }
        }
        if (divisor > 0l)
            return sum/(2l*divisor);
        else
            return -1l;
    }

    public void setStatus(Status status) {
        this.status = status;
        if ((status == Status.ONLINE) &&  (discoveryTime < 0l))
            discoveryTime = System.currentTimeMillis();
    }

    /**
     * Gets the delay between discovery and connection
     * @return delay in milliseconds (or -1 if not yet known)
     */
    public long getDiscoveryConnectLag() {
        if ((discoveryTime < 0l) || (connectTime < 0l))
            return -1l;
        return connectTime - discoveryTime;
    }

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

    /**
     * Is the UUID for this device known or is there just a placeholder in play
     * for now. Useful since some network connections are established before
     * the device is able to pass its identifying info
     * @return true == this is a valid UUID
     */
    public boolean isUuidKnown() {
        return uuid > 0;
    }

    public void update(SqAnDevice other) {
        if (other == null)
            return;
        if ((uuid < 0) && (other.uuid > 0)) {
            uuid = other.uuid;
            SqAnDevice existingDevice = findByUUID(other.getUUID());
            if (existingDevice != null) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,existingDevice.networkId+" was a duplicate; information merged into "+uuid);
                remove(existingDevice);
            }
        }
        if (other.networkId != null)
            networkId = other.networkId;
        if (other.callsign != null)
            callsign = other.callsign;
        if (other.uuidExtended != null)
            uuidExtended = other.uuidExtended;
        if (uuid > 0) {
            if (other.uuid < 0) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,other.networkId+" was a duplicate; information merged into "+uuid);
                remove(other);
            }
        }
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
        if (other.uuid == UNASSIGNED_UUID) {
            if (other.networkId != null)
                return other.networkId.equalsIgnoreCase(networkId);
            return false;
        } else
            return (uuid == other.uuid);
    }

    /**
     * Is this device identifiable with this UUID
     * @param uuid
     * @return true == this is the same device
     */
    public boolean isSame(int uuid) {
        if (uuid == UNASSIGNED_UUID)
            return false;
        return this.uuid == uuid;
    }

    public static ArrayList<SqAnDevice> getDevices() {
        if (devices == null)
            return null;
        if (devices.isEmpty())
            devices = null;
        return devices;
    }

    /**
     * Provides verification that two devices are the same (compares both the
     * UUID and the extended UUID). This is useful when identifying and dealing
     * with possible collisions based on the UUID.
     * @param other
     * @return
     */
    public boolean isSameHighConfidence(SqAnDevice other) {
        if (other == null)
            return false;
        if (isSame(other)) {
            if (other.uuidExtended != null)
                return other.uuidExtended.equalsIgnoreCase(uuidExtended);
        }
        return false;
    }

    /**
     * Extended UUIDs are larger (less collision risk) UUIDs that are immutable
     * and singularly identify the device. These should be used for more
     * detailed collision checks when the UUID is suspectd to possibly be
     * duplicate between two devices.
     * @param uuidExtended
     */
    public void setUuidExtended(String uuidExtended) {
        this.uuidExtended = uuidExtended;
    }
    public String getUuidExtended() { return uuidExtended; }

    /**
     * Add a new device to the list of devices
     * @param device
     * @return true == a new devices was added; false == this is a null or existing device
     */
    public static boolean add(SqAnDevice device) {
        if ((device == null) || (device.getUUID() == UNASSIGNED_UUID))
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

    public static void remove(SqAnDevice device) {
        if (devices != null) {
            devices.remove(device);
            if (devices.isEmpty())
                devices = null;
        }
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
    public static SqAnDevice findByUUID(int uuid) {
        if ((devices != null) && !devices.isEmpty()) {
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
        if (connectTime < 0l)
            connectTime = System.currentTimeMillis();
    }
    public void setLastConnect(long time) {
        lastConnect = time;
    }

    /**
     * Is location data known for this device
     * @return
     */
    public boolean isLocationKnown() {
        return ((lastLocation != null) && lastLocation.isValid());
    }

    /**
     * Is the location data on this device current
     * @return true == reasonably current
     */
    public boolean isLocationCurrent() {
        if (isLocationKnown())
            return (NetworkTime.getNetworkTimeNow() - lastLocation.getTime()) > TIME_TO_STALE;
        else
            return false;
    }
}
