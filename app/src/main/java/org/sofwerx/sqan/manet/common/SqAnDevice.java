package org.sofwerx.sqan.manet.common;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.location.Location;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.pnt.NetworkTime;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;
import org.sofwerx.sqan.ui.DeviceSummary;
import org.sofwerx.sqan.util.CommsLog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SqAnDevice {
    private static final long TIME_TO_CONSIDER_HOP_COUNT_STALE = 1000l * 60l;
    public final static int UNASSIGNED_UUID = Integer.MIN_VALUE;
    private static AtomicInteger nextUnassignedUUID = new AtomicInteger(-1);
    private final static long TIME_TO_STALE = 1000l * 60l;
    private final static int MAX_LATENCY_HISTORY = 100; //the max number of latency records to keep
    private static ArrayList<SqAnDevice> devices;
    private int uuid; //this is the persistent SqAN ID for this device
    private String callsign; //this is the callsign which also acts as the domain name for this device
    private String uuidExtended; //this is the persistent ID for this device used solely to look for conflicts
    private MacAddress bluetoothMac;
    private String networkId; //this is the transient MANET ID for this device
    //private int sqanAddress; //this is the transient SqAN address for this device; usually a translation of the IPV4 address
    private long lastConnect = Long.MIN_VALUE;
    private long rxDataTally = 0l; //talley of received bytes from this node
    private Status status = Status.OFFLINE;
    private ArrayList<Long> latencies;
    private long discoveryTime = -1l; //used to mark when this device was discovered
    private long connectTime = -1l; //used to mark when this device was connected
    private CommsLog.Entry lastEntry = null;
    private SpaceTime lastLocation = null;
    private boolean backhaulConnection = false;
    private DeviceSummary uiSummary = null;
    private NodeRole roleWiFi = NodeRole.OFF;
    private NodeRole roleBT = NodeRole.OFF;
    private int hopsAway = 0;
    private long lastHopUpdate = Long.MIN_VALUE;
    private ArrayList<RelayConnection> relays = new ArrayList<>();

    /**
     * Gets this device's bluetooth MAC address
     * @return
     */
    public MacAddress getBluetoothMac() {
        return bluetoothMac;
    }

    /**
     * Sets this device's bluetooth MAC address
     * @param mac
     */
    public void setBluetoothMac(String mac) {
        if ((mac == null) || !BluetoothAdapter.checkBluetoothAddress(mac)) {
            Log.e(Config.TAG,"MAC address "+mac+" is not a valid Bluetooth mac address");
            bluetoothMac = null;
            return;
        }
        bluetoothMac = new MacAddress(mac);
    }

    /**
     * Gets how far away this device is (i.e. 0 hops == direct connection, 1 hop == one device acting as a relay)
     * @return
     */
    public int getHopsAway() {
        return hopsAway;
    }
    public void setHopsAway(int hops) { hopsAway = hops; }

    public static enum NodeRole { HUB, SPOKE, OFF, BOTH }

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     */
    public SqAnDevice(int uuid) {
        if (uuid == UNASSIGNED_UUID) {
            this.uuid = nextUnassignedUUID.decrementAndGet();
            Log.w(Config.TAG,"This device has an unassigned UUID and so has been assigned the next UUID in the host's UUID block: "+this.uuid);
        } else
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
     * Look for and merge any likely duplicate nodes
     * @return the device that absorbed a duplicate device
     */
    public static SqAnDevice dedup() {
        if ((devices == null) || (devices.size() < 2))
            return null;

        SqAnDevice merged = null;

        boolean scanNeeded = true;
        int inspectingIndex = 0;
        while ((merged == null) && (inspectingIndex < devices.size()) && scanNeeded) {
            SqAnDevice inspecting = devices.get(inspectingIndex);
            for (int i=0;i<devices.size();i++) {
                if ((merged == null) && (i != inspectingIndex)) {
                    SqAnDevice other = devices.get(i);
                    if (inspecting.networkId != null) {
                        if (inspecting.networkId.equalsIgnoreCase(other.networkId)) {
                            if (inspecting.lastConnect > other.lastConnect) {
                                merged = inspecting;
                                CommsLog.log(CommsLog.Entry.Category.STATUS,"Duplicate devices detected; "+other.uuid+" merged into "+inspecting.uuid);
                                devices.remove(i);
                            } else {
                                merged = other;
                                CommsLog.log(CommsLog.Entry.Category.STATUS,"Duplicate devices detected; "+inspecting.uuid+" merged into "+other.uuid);
                                devices.remove(inspectingIndex);
                            }
                        }
                    }
                }
            }
            inspectingIndex++;
        }

        return merged;
    }

    /**
     * Remove old devices from the roster
     * @return
     */
    public static boolean cullOldDevices() {
        if ((devices == null) || devices.isEmpty())
            return false;

        boolean culled = false;

        int i=0;
        while (i<devices.size()) {
            SqAnDevice device = devices.get(i);
            if (device.networkId == null) {
                CommsLog.log(CommsLog.Entry.Category.STATUS, "Device " + ((device.callsign == null) ? Integer.toString(device.uuid) : device.callsign) + " removed (no network ID)");
                devices.remove(i);
                culled = true;
            } else {
                if ((device.lastConnect > 0l) && (System.currentTimeMillis() > device.lastConnect + TIME_TO_STALE))
                    device.status = Status.STALE;
                if (device.status == Status.STALE) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS, "Stale device " + ((device.callsign == null) ? Integer.toString(device.uuid) : device.callsign) + " removed");
                    devices.remove(i);
                    culled = true;
                } else
                    i++;
            }
        }

        return culled;
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
     * Gets a network and file saving safe version of the callsign
     * @return
     */
    public String getSafeCallsign() {
        if (callsign == null)
            return "";
        return callsign.replaceAll("\\W+", "");
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
     * Is this device connected outside the mesh to enable data to be backhauled (i.e. moved
     * out of theater)
     * @return true == device has backhaul
     */
    public boolean isBackhaulConnection() { return backhaulConnection; }

    //TODO implement designating this as a backhaul devices. This could be an app broadcast thing or alternatively could be based on SqANs ability to reach the internet
    /**
     * Designates if this device connected outside the mesh to enable data to be backhauled
     * (i.e. moved out of theater)
     * @param backhaulConnection
     */
    public void setBackhaulConnection(boolean backhaulConnection) { this.backhaulConnection = backhaulConnection; }

    public void setUiSummary(DeviceSummary deviceSummary) { this.uiSummary = deviceSummary; }
    public DeviceSummary getUiSummary() { return uiSummary; }

    /**
     * Gets the SqAN address. This is the transient address that is usually the integer equivalent
     * of the IPV4 address for this device as seen by SqAN. Used primarily for streaming and
     * multi-hop routing.
     * @return
     */
    //public int getSqanAddress() { return sqanAddress; }

    /**
     * Gets the SqAN address. This is the transient address that is usually the integer equivalent
     * of the IPV4 address for this device as seen by SqAN. Used primarily for streaming and
     * multi-hop routing.
     * @return
     */
    //public void setSqanAddress(int sqanAddress) { this.sqanAddress = sqanAddress; }

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

    /**
     * Gets a map of what devices this device is connected to
     * @return
     */
    public ArrayList<RelayConnection> getRelayConnections() {
        return relays;
    }

    public int getActiveRelays() {
        //TODO factor in some age consideration
        if (relays == null)
            return 0;
        return relays.size();
    }

    public RelayConnection getConnection(int sqAnID) {
        if (relays != null) {
            for (RelayConnection relay:relays) {
                if (relay.getSqAnID() == sqAnID)
                    return relay;
            }
        }
        return null;
    }

    public void updateRelayConnection(RelayConnection connection) {
        if (connection == null)
            return;
        RelayConnection current = getConnection(connection.getSqAnID());
        if (current == null)
            relays.add(connection);
        else
            current.update(connection);
    }

    public void setRelayConnections(ArrayList<RelayConnection> relays) {
        this.relays = relays;
    }

    public boolean isActive() {
        return (status == Status.CONNECTED);
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
        if (other.lastLocation != null) {
            if ((lastLocation == null) || !lastLocation.isValid() || (other.lastLocation.getTime() > lastLocation.getTime()))
                lastLocation = other.lastLocation;
        }
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
        if ((Config.getThisDevice() != null) && (device.getUUID() == Config.getThisDevice().getUUID())) //dont add our own device
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
        } else {
            existing.update(device);
            Config.saveTeammate(device.getUUID(),null,device.getCallsign());
        }
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
     * @return the device (or null if not found)
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
     * Finds a device in the list of devices based on Bluetooth MAC
     * @param macString Bluetooth MAC
     * @return the device (or null if not found)
     */
    public static SqAnDevice findByBtMac(String macString) {
        MacAddress mac = MacAddress.build(macString);
        return findByBtMac(mac);
    }

    /**
     * Finds a device in the list of devices based on Bluetooth MAC
     * @param mac Bluetooth MAC
     * @return the device (or null if not found)
     */
    public static SqAnDevice findByBtMac(MacAddress mac) {
        if ((mac != null) && (devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (mac.isEqual(device.bluetoothMac))
                    return device;
            }
        }
        return null;
    }

    /**
     * Finds a device in the list of devices based on its SqAnAddress (which is
     * different than its UUID; the SqAnAddress represents a temporary address
     * to find this device on the SqAN mesh and usually is the integer equivalent
     * of the device's IPV4 address as seen by SqAN
     * @return
     */
    /*public static SqAnDevice findBySqAnAddress(int sqAnAddress) {
        if ((devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if (device.sqanAddress == sqAnAddress)
                    return device;
            }
        }
        return null;
    }*/


    public enum FullMeshCapability {
        UP, DEGRADED, DOWN
    }

    /**
     * Gets the overall status based on all the devices in the mesh. If no devices are connected
     * the mesh is DOWN. If all the devices are connected, the mesh is UP. In between, the
     * mesh is DEGRADED
     * @return overall mesh status
     */
    public static FullMeshCapability getFullMeshStatus() {
        if ((devices == null) || devices.isEmpty())
            return FullMeshCapability.DOWN;

        for (SqAnDevice device:devices) {
            if (device.status != Status.CONNECTED)
                return FullMeshCapability.DEGRADED;
        }

        return FullMeshCapability.UP;
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
    public void setConnected(int hopsAway) {
        status = Status.CONNECTED;
        setLastConnect(System.currentTimeMillis());
        if (connectTime < 0l)
            connectTime = System.currentTimeMillis();
        if (hopsAway > this.hopsAway) {
            if (System.currentTimeMillis() > lastHopUpdate + TIME_TO_CONSIDER_HOP_COUNT_STALE) {
                lastHopUpdate = System.currentTimeMillis();
                this.hopsAway = hopsAway;
            }
        } else {
            lastHopUpdate = System.currentTimeMillis();
            this.hopsAway = hopsAway;
        }
    }
    public void setLastConnect(long time) {
        lastConnect = time;
    }

    public long getLastConnect() { return lastConnect; }

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
            return (NetworkTime.getNetworkTimeNow() - lastLocation.getTime()) < TIME_TO_STALE;
        else
            return false;
    }

    /**
     * Gets the distance between two devices
     * @param other
     * @return the distance in meters (or NaN if the distance cannot be computed)
     */
    public double getDistance(SqAnDevice other) {
        if ((other != null) && (other.lastLocation != null) && other.lastLocation.isValid()
                && (lastLocation != null) && lastLocation.isValid()) {
            return lastLocation.getDistance(other.lastLocation);
        } else
            return -100d;
        //TODO check to see if the points are close in time
        //TODO add the ability to use computed RTT distances instead
    }

    /**
     * Gets the distance between two devices as a string
     * @param other
     * @return the distance (or null if the distance cannot be computed)
     */
    public String getDistanceText(SqAnDevice other) {
        double distance = getDistance(other);
        if (Double.isNaN(distance))
            return null;
        float errorDistance = 7f;
        if ((lastLocation != null) && (other != null) && (other.lastLocation != null))
            errorDistance = lastLocation.getTotalAccuracy(other.lastLocation);
        if (Float.isNaN(errorDistance) || (distance < errorDistance))
            return "close";
        return Math.round(distance)+"m";
    }

    public String getAggregateAccuracy(SqAnDevice other) {
        float errorDistance = Float.NaN;
        if ((lastLocation != null) && (other != null) && (other.lastLocation != null))
            errorDistance = lastLocation.getTotalAccuracy(other.lastLocation);
        if (Float.isNaN(errorDistance))
            return null;
        return "±"+Math.round(errorDistance)+"m";
    }

    /**
     * Gets this device's role in the network in WiFi comms
     * @return
     */
    public NodeRole getRoleWiFi() { return roleWiFi; }

    /**
     * Sets this device's role in the network in WiFi comms
     * @param roleWiFi
     */
    public void setRoleWiFi(NodeRole roleWiFi) { this.roleWiFi = roleWiFi; }

    /**
     * Gets this device's role in the network in Bluetooth comms
     * @return
     */
    public NodeRole getRoleBT() { return roleBT; }

    /**
     * Sets this device's role in the network in Bluetooth comms
     * @param roleBT
     */
    public void setRoleBT(NodeRole roleBT) { this.roleBT = roleBT; }
}
