package org.sofwerx.sqan.manet.common;

import android.bluetooth.BluetoothAdapter;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.ipc.BftDevice;
import org.sofwerx.sqan.manet.common.issues.AbstractCommsIssue;
import org.sofwerx.sqan.manet.common.issues.PacketDropIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiConnectivityIssue;
import org.sofwerx.sqan.manet.common.issues.WiFiIssue;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.VpnPacket;
import org.sofwerx.sqan.manet.common.pnt.NetworkTime;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;
import org.sofwerx.sqan.manet.common.sockets.TransportPreference;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.ui.DeviceSummary;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqan.util.UuidUtil;

import java.net.Inet6Address;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class SqAnDevice {
    private final static int FIRST_VALID_UUID = 1540000000; //no SqAN device should have a UUID below this
    //private final static int VALID_UUID_UPPER_BOUNDS = (int)(System.currentTimeMillis()/1000l)+2000000;
    private final static int VALID_UUID_UPPER_BOUNDS = (int)(System.currentTimeMillis()/1000l)+80000000;
    private static final long TIME_TO_CONSIDER_HOP_COUNT_STALE = 1000l * 60l;
    public final static long TIME_TO_STALE = 1000l * 60l;
    //private final static long TIME_TO_REMOVE_STALE = TIME_TO_STALE * 2l;
    private final static int MAX_LATENCY_HISTORY = 100; //the max number of latency records to keep
    public final static int UNASSIGNED_UUID = Integer.MIN_VALUE;
    public final static int BROADCAST_IP = AddressUtil.getSqAnVpnIpv4Address(PacketHeader.BROADCAST_ADDRESS);
    private final static int MAX_RELAY_CONNECTIONS_TO_SAVE = 20;
    public final static byte[] NO_IPV6_ADDRESS = {(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0,(byte)0};
    private static AtomicInteger nextUnassignedUUID = new AtomicInteger(-1);
    private static ArrayList<SqAnDevice> devices;
    private int uuid; //this is the persistent SqAN ID for this device
    private String callsign; //this is the callsign which also acts as the domain name for this device
    private String uuidExtended; //this is the persistent ID for this device used solely to look for conflicts
    private MacAddress bluetoothMac;
    private String networkId; //this is the transient MANET ID for this device
    private int transientAwareId = UNASSIGNED_UUID;
    private long lastConnect = Long.MIN_VALUE;
    private long rxDataTally = 0l; //talley of received bytes from this node
    private long displayedRxTally = 0l; //a counter used to help update the GUI when a "ping" happens
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
    private NodeRole roleSDR = NodeRole.OFF;
    private int hopsAway = 0;
    private boolean directBt = false;
    private boolean directWiFi = false;
    private boolean directWiFiHiPerf = false; //is high performance WiFi connected
    private boolean directSDR = false;
    private long lastHopUpdate = Long.MIN_VALUE;
    private long lastForwardedToThisDevice = Long.MIN_VALUE;
    private ArrayList<RelayConnection> relays = new ArrayList<>();
    private int ipV4Address = Integer.MIN_VALUE;
    private TransportPreference preferredTransport = TransportPreference.AGNOSTIC;
    private long connectionStart = Long.MIN_VALUE;
    private final static int MAX_ISSUES_LOG = 20;
    private ArrayList<AbstractCommsIssue> issues;
    private int packetsDropped = 0;
    private MacAddress directMac;
    private MacAddress awareMac;
    private Inet6Address awareServerIp;
    private ArrayList<VpnForwardValue> forwarding;

    public static boolean isValidUuid(int origin) {
        return (origin > FIRST_VALID_UUID) && (origin < VALID_UUID_UPPER_BOUNDS);
    }
    //private boolean awareServer = false;
    //private Inet6Address ipv6 = null;

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();

        sb.append("Callsign: ");
        if (callsign == null)
            sb.append("NONE");
        else
            sb.append(callsign);
        if (status != null) {
            sb.append(", ");
            sb.append(status.name());
        }
        sb.append(", BT MAC: ");
        if ((bluetoothMac == null) || !bluetoothMac.isValid())
            sb.append("unknown");
        else
            sb.append(bluetoothMac.toString());
        if ((networkId != null) && (networkId.length() > 0)) {
            sb.append(", transient WiFI ID ");
            sb.append(networkId);
        }
        if (transientAwareId != UNASSIGNED_UUID) {
            sb.append(", AWARE ID ");
            sb.append(Integer.toString(transientAwareId));
        }
        if (lastConnect > 0l)
            sb.append(", last comms "+StringUtil.toDuration(System.currentTimeMillis() - lastConnect)+" ago");
        if (backhaulConnection)
            sb.append(", is backhaul connection");
        if ((roleWiFi != null) && (roleWiFi != NodeRole.OFF)) {
            sb.append(", WiFi role ");
            sb.append(roleWiFi.name());
        }
        if ((roleBT != null) && (roleBT != NodeRole.OFF)) {
            sb.append(", BT role ");
            sb.append(roleBT.name());
        }
        if (hopsAway == 0)
            sb.append(", directly connected");
        else
            sb.append(", "+hopsAway+" hop"+((hopsAway==1)?"":"s")+" away");
        sb.append(", preferred transport: ");
        sb.append(preferredTransport.name());
        if (directWiFiHiPerf)
            sb.append(", requires high performance WiFi");
        sb.append(", ");
        sb.append(StringUtil.toDataSize(rxDataTally)+" received");
        if (connectionStart > 0l) {
            sb.append(" since connecting ");
            sb.append(StringUtil.toDuration(System.currentTimeMillis() - connectionStart));
            sb.append(" ago");
        }
        if (lastLocation != null) {
            sb.append(", location (");
            sb.append(lastLocation.toString());
            sb.append(")");
        }
        if ((relays != null) && !relays.isEmpty()) {
            sb.append(", connected to [");
            boolean first = true;
            for (RelayConnection relay:relays) {
                if (first)
                    first = false;
                else
                    sb.append(", ");
                sb.append(relay.toString());
            }
            sb.append("]");
        }
        if (packetsDropped > 0)
            sb.append(packetsDropped+" packet"+((packetsDropped==1)?"":"s")+" dropped");
        if ((awareMac != null) && awareMac.isValid())
            sb.append(", AWARE MAC "+awareMac.toString());
        if (awareServerIp != null)
            sb.append(", AWARE IP "+awareServerIp.getHostAddress());
        if ((issues != null) && (issues.size() > 0)) {
            sb.append(", ");
            sb.append(issues.get(issues.size()-1).toString());
        }

        return sb.toString();
    }

    /**
     * SqAnDevice
     * @param uuid == the persistent UUID associated with SqAN on this physical device
     */
    public SqAnDevice(int uuid) {
        init(uuid);
    }

    public long getFirstConnection() { return connectionStart; }

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

    public SqAnDevice(SavedTeammate teammate) {
        if (teammate != null) {
            init(teammate.getSqAnAddress());
            networkId = teammate.getNetID();
            callsign = teammate.getCallsign();
            bluetoothMac = teammate.getBluetoothMac();
        }
    }

    /**
     * Finds the device that matches this WiFi Aware ID
     * @param id
     * @return null == not found
     */
    public static SqAnDevice findByTransientAwareID(int id) {
        if ((id != UNASSIGNED_UUID) && (devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device:devices) {
                    if ((device != null) && (device.transientAwareId == id))
                        return device;
                }
            }
        }
        return null;
    }

    /*public Inet6Address getIpv6() { return ipv6; }

    public static SqAnDevice findByTransientAwareIPV6(Inet6Address ipv6) {
        if ((ipv6 != null) && (devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device:devices) {
                    if ((device != null) && device.ipv6.equals(ipv6))
                        return device;
                }
            }
        }
        return null;
    }*/

    private void init(int uuid) {
        if (uuid == UNASSIGNED_UUID) {
            this.uuid = nextUnassignedUUID.decrementAndGet();
            if (this.uuid > 0)
                CommsLog.log(CommsLog.Entry.Category.PROBLEM,"SqAnDevice with unknown UUID assigned a positive UUID of "+this.uuid+" [this should never happen]");
            Log.w(Config.TAG,"This device has an unassigned UUID and so has been assigned the next UUID in the host's UUID block: "+this.uuid);
        } else
            this.uuid = uuid;
        SqAnDevice.add(this);
    }

    public static boolean hasAtLeastOneActiveConnection() {
        if (devices != null) {
            for (SqAnDevice device:devices) {
                if ((device != null) && device.isActive())
                    return true;
            }
        }
        return false;
    }

    /**
     * Adjust the block counter for the next unassigned UUID. Used primarily when reading in
     * a chunk of saved devices to prevent a UUID from being reused).
     * @param index
     */
    public static void setUnassignedBlockIndex(int index) {
        nextUnassignedUUID.set(index);
    }

    /**
     * Gets any device that has an ID conflict with this device
     * @return null == no device is in conflict
     */
    public SqAnDevice getConflictingDevice() {
        int thisIpv4Address = getVpnIpv4AddressInt();
        if ((devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device : devices) {
                    if (device.getVpnIpv4AddressInt() == thisIpv4Address)
                        return device;
                }
            }
        }
        return null;
    }

    /**
     * Gets a list of devices with WiFi Aware MACs or IPV6s
     * @return
     */
    public static ArrayList<SqAnDevice> getWiFiAwareDevices() {
        ArrayList<SqAnDevice> awareDevices = null;
        if ((devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device : devices) {
                    if (device != null) {
                        if (((device.awareMac != null) && device.awareMac.isValid()) || (device.awareServerIp != null)) {
                            if (awareDevices == null)
                                awareDevices = new ArrayList<>();
                            awareDevices.add(device);
                        }
                    }
                }
            }
        }
        return awareDevices;
    }

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
        if (isActive())
            return hopsAway;
        return Integer.MAX_VALUE;
    }

    public void setHopsAway(int hops, boolean directBt, boolean directWiFi) { setHopsAway(hops, directBt, directWiFi,directWiFiHiPerf); }
    public void setHopsAway(int hops, boolean directBt, boolean directWiFi, boolean directWiFiHiPerf) {
        hopsAway = hops;
        if (hopsAway == 0) {
            this.directBt = directBt;
            this.directWiFi = directWiFi;
            this.directWiFiHiPerf = directWiFiHiPerf;
        }
    }

    public void setDirectSDR(boolean directSDR) {
        this.directSDR = directSDR;
        hopsAway = 0;
    }

    /**
     * Is this device connected with a high performance version of WiFi (as opposed to something like using WiFi
     * Aware messages
     * @return
     */
    public boolean isDirectWiFiHighPerformance() { return directWiFiHiPerf; }

    /**
     * Is this device directly connected via WiFi
     * @return
     */
    public boolean isDirectWiFi() {
        if (hopsAway == 0)
            return directWiFi;
        return false;
    }

    /**
     * Sets if this device directly connected via WiFi
     * @param directWiFi
     */
    public void setDirectWiFi(boolean directWiFi) { this.directWiFi = directWiFi; }

    /**
     * Is this device directly connected via Bluetooth
     * @return
     */
    public boolean isDirectBt() {
        if (hopsAway == 0)
            return directBt;
        return false;
    }

    /**
     * Sets if this device directly connected via Bluetooth
     * @param directBt
     */
    public void setDirectBt(boolean directBt) { this.directBt = directBt; }

    /**
     * Sets the last time our device forwarded a packet to this device
     * @param time
     */
    public void setLastForward(long time) { lastForwardedToThisDevice = time; }

    public void setLastForward() { setLastForward(System.currentTimeMillis()); }

    /**
     * Gets the last time our device forwarded a packet to this device
     * @return
     */
    public long getLastForward() { return lastForwardedToThisDevice; }

    public TransportPreference getPreferredTransport() {
        return preferredTransport;
    }

    /**
     * Gets the direct links for reporting in SA broadcasts
     * @return the direct links between this device and other devices
     */
    public ArrayList<BftDevice.Link> getLinks() {
        ArrayList<BftDevice.Link> links = null;
        if ((relays != null) && !relays.isEmpty()) {
            synchronized (relays) {
                BftDevice.Link link;
                for (RelayConnection relay : relays) {
                    if (relay.getHops() == 0) {
                        link = new BftDevice.Link(relay.getSqAnID(), relay.isDirectBt(), relay.isDirectWiFi());
                        if (links == null)
                            links = new ArrayList<>();
                        links.add(link);
                    }
                }
            }
        }
        return links;
    }

    public boolean isBtPreferred() {
        switch (preferredTransport) {
            case BLUETOOTH:
            case ALL:
                return true;

            default:
                return false;
        }
    }

    public boolean isSdrPreferred() {
        switch (preferredTransport) {
            case SDR:
            case ALL:
                return true;

            default:
                return false;
        }
    }

    public boolean isWiFiPreferred() {
        switch (preferredTransport) {
            case WIFI:
            case ALL:
                return true;

            default:
                return false;
        }
    }

    /**
     * Sets the transient ID assigned by WiFi Aware for this device
     * @param id
     */
    public void setTransientAwareId(int id) {
        transientAwareId = id;
    }
    public int getTransientAwareId() { return transientAwareId; }

    /**
     * Takes all the updated values from the other device then removes the other device from the list of devices
     * @param other
     */
    public void consume(SqAnDevice other) {
        if (other == null)
            return;
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Device "+other.getLabel()+" is being merged into "+getLabel());
        update(other);
        if ((devices == null) || devices.isEmpty())
            return; //this should not happen
        synchronized (devices) {
            devices.remove(other);
        }
    }

    public void addIssue(AbstractCommsIssue issue) {
        if (issue == null)
            return;
        if (issues == null)
            issues = new ArrayList<>();
        synchronized (issues) {
            issues.add(issue);
            if (issue instanceof PacketDropIssue)
                packetsDropped++;
            if (issues.size() > MAX_ISSUES_LOG)
                issues.remove(0);
        }
    }

    public int getPacketsDropped() {
        return packetsDropped;
    }

    public MacAddress getAwareMac() {
        return awareMac;
    }

    public void setAwareMac(MacAddress awareMac) {
        this.awareMac = awareMac;
    }

    /**
     * Is this device hosting a WiFi Aware server
     * @return
     */
    public boolean isAwareServer() { return awareServerIp != null; }

    /**
     * Sets the IPV6 address of this device IF this device is an aware server
     * @param awareServerIp IPV6 address of this device if aware server (or null if not)
     */
    public void setAwareServerIp(Inet6Address awareServerIp) {
        if (awareServerIp == null)
            Log.d(Config.TAG,getLabel()+" Aware Server IP cleared");
        else
            Log.d(Config.TAG,getLabel()+" Aware Server IP set to: "+awareServerIp.getHostAddress());
        this.awareServerIp = awareServerIp;
    }

    public void setAwareServerIp(byte[] bytes) {
        if ((bytes == null) || (bytes.length != NO_IPV6_ADDRESS.length)) {
            awareServerIp = null;
            return;
        } else {
            boolean isNoValue = true;
            for (int i=0;i<bytes.length;i++) {
                if (bytes[i] != 0) {
                    isNoValue = false;
                    break;
                }
            }
            if (isNoValue) {
                awareServerIp = null;
            } else {
                try {
                    //awareServerIp = (Inet6Address)Inet6Address.getByAddress("aware_data",bytes,0);
                    awareServerIp = (Inet6Address)Inet6Address.getByAddress(bytes);
                } catch (UnknownHostException e) {
                    Log.d(Config.TAG,"Unable to assign aware server IP: "+e.getMessage());
                    awareServerIp = null;
                }
            }
        }
    }

    /**
     * Gets the IPV6 address of this device IF this device is an aware server
     * @return IPV6 address of this device if aware server (or null if not)
     */
    public Inet6Address getAwareServerIp() { return awareServerIp; }

    public void clearAwareServerIp() { awareServerIp = null; }

    public void setWiFiDirectMac(MacAddress mac) { directMac = mac; }

    public VpnForwardValue getIpForwardAddress(final byte index) {
        if (forwarding != null) {
            for (int i=0;i<forwarding.size();i++) {
                if (forwarding.get(i).getForwardIndex() == index)
                    return forwarding.get(i);
            }
        }
        Log.w(Config.TAG,"Unable to find forwarding address; no device at index "+index);
        return null;
    }

    public ArrayList<VpnForwardValue> getIpForwardAddresses() { return forwarding; }

    private void sortForwarding() {
        if ((forwarding == null) || (forwarding.size() < 2))
            return;
        boolean complete = false;
        VpnForwardValue temp;
        while (!complete) {
            complete = true;
            for (int i=1;i<forwarding.size();i++) {
                if (forwarding.get(i).getForwardIndex() < forwarding.get(i-1).getForwardIndex()) {
                    temp = forwarding.get(i);
                    forwarding.set(i,forwarding.get(i-1));
                    forwarding.set(i-1,temp);
                    complete = false;
                }
            }
        }
    }

    public byte getNextFowardingIndex() {
        if (forwarding == null)
            return 0;
        sortForwarding();
        int last = 1;
        for (int i=0;i<forwarding.size();i++) {
            if (forwarding.get(i).getForwardIndex() > last)
                break;
            last = forwarding.get(i).getForwardIndex() + 1;
        }
        return (byte)(last & 0xFF);
    }

    public void addVpnForwardValue(VpnForwardValue value) {
        if (value == null)
            return;
        if (forwarding == null) {
            forwarding = new ArrayList<>();
            forwarding.add(value);
        }

        //move any forwarding record currently in this index to another place
        byte indexToInsert = value.getForwardIndex();
        for (int i=0;i<forwarding.size();i++) {
            if (forwarding.get(i).getForwardIndex() == indexToInsert) {
                if (forwarding.get(i).getAddress() == value.getAddress())
                    return; //this record already exists and is in the right index
                Log.d(Config.TAG,AddressUtil.intToIpv4String(forwarding.get(i).getAddress())+" was being forwarded as "
                        +AddressUtil.intToIpv4String(AddressUtil.getSqAnVpnIpv4Address(uuid,indexToInsert))
                        +" but is being moved so "+AddressUtil.intToIpv4String(value.getAddress())
                        +" can be reached at that IP");
                forwarding.get(i).setIndex(getNextFowardingIndex());
            }
        }

        for (int i=0;i<forwarding.size();i++) {
            if (forwarding.get(i).getAddress() == value.getAddress()) {
                forwarding.set(i,value); //update any index issue and close
                return;
            }
        }
        forwarding.add(value);
    }

    /**
     * Gets the forward value for this IP address or adds it if not already on the list
     * @param ipv4
     * @param addIfNotPresent
     * @return
     */
    public VpnForwardValue getOrAddIpForwardAddress(final int ipv4, boolean addIfNotPresent) {
        VpnForwardValue value = null;
        if (forwarding == null) {
            if (addIfNotPresent) {
                forwarding = new ArrayList<>();
                value = new VpnForwardValue(getNextFowardingIndex(), ipv4);
                forwarding.add(value);
            }
        } else {
            for (int i=0;i<forwarding.size();i++) {
                if (forwarding.get(i).getAddress() == ipv4)
                    return forwarding.get(i);
            }
            if (forwarding.size() > 254) {
                Log.w(Config.TAG,"A SqAN node can only forward 254 IPV4 addresses; "+AddressUtil.intToIpv4String(ipv4)+" is being ignored");
                return null;
            }
            if (addIfNotPresent) {
                value = new VpnForwardValue(getNextFowardingIndex(), ipv4);
                forwarding.add(value);
            }
        }
        return value;
    }

    public enum NodeRole { HUB, SPOKE, OFF, BOTH }

    /**
     * Look for and merge any likely duplicate nodes
     * @return the device that absorbed a duplicate device
     */
    public static SqAnDevice dedup() {
        if ((devices == null) || (devices.size() < 2))
            return null;

        boolean scanNeeded = true;
        int inspectingIndex = 0;
        while ((inspectingIndex < devices.size()) && scanNeeded) {
            SqAnDevice inspecting = devices.get(inspectingIndex);
            for (int i=0;i<devices.size();i++) {
                if (i != inspectingIndex) {
                    SqAnDevice other = devices.get(i);
                    if (inspecting.isSame(other)) {
                        if (inspecting.lastConnect > other.lastConnect) {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Duplicate devices detected; " + other.uuid + " merged into " + inspecting.uuid);
                            devices.remove(i);
                            inspecting.update(other);
                            return inspecting;
                        } else {
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Duplicate devices detected; " + inspecting.uuid + " merged into " + other.uuid);
                            devices.remove(inspectingIndex);
                            other.update(inspecting);
                            return other;
                        }
                    }
                }
            }
            inspectingIndex++;
        }

        return null;
    }

    private void cullOldRelayConnections() {
        if (relays != null) {
            synchronized (relays) {
                RelayConnection worst = null;
                int i = 0;
                RelayConnection relay;
                while (i < relays.size()) {
                    relay = relays.get(i);
                    if (System.currentTimeMillis() > relay.getLastConnection() + TIME_TO_CONSIDER_HOP_COUNT_STALE) {
                        Log.d(Config.TAG, "Removing old relay info from " + callsign + " to SqAn ID " + relay.getSqAnID());
                        relays.remove(i);
                    } else {
                        if (worst == null)
                            worst = relay;
                        else {
                            if (relay.getHops() > worst.getHops())
                                worst = relay;
                            else if (relay.getLastConnection() < worst.getLastConnection())
                                worst = relay;
                        }
                        i++;
                    }
                }
                if ((relays.size() > MAX_RELAY_CONNECTIONS_TO_SAVE) && (worst != null))
                    relays.remove(worst);
            }
        }
    }

    /**
     * Updates the preferred routing for each device based on current connectivity. This
     * logic will probably need a bit of tweaking
     */
    public static void updateDeviceRoutePreferences() {
        if ((devices == null) || devices.isEmpty())
            return;
        for (SqAnDevice device:devices) {
            if (device.isActive()) {
                if (device.directWiFi)
                    device.addPreferWiFi();
                else
                    device.removePreferWiFi();
                if (device.directWiFi && (System.currentTimeMillis() < device.lastConnect + TIME_TO_STALE/2)) //very fresh device, prefer WiFi)
                    device.removePreferBt();
                else {
                    if (device.directBt)
                        device.addPreferBt();
                    else
                        device.removePreferBt();
                }
                if (device.directSDR)
                    device.addPreferSDR();
            } else
                device.preferredTransport = TransportPreference.AGNOSTIC;
        }
    }

    /**
     * Updates this device's routing preference to include WiFi
     */
    public void addPreferWiFi() {
        switch (preferredTransport) {
            case BLUETOOTH:
            case ALL:
                preferredTransport = TransportPreference.ALL;
                break;

            default:
                preferredTransport = TransportPreference.WIFI;
        }
    }

    /**
     * Updates this device's routing preference to include BT
     */
    public void addPreferBt() {
        switch (preferredTransport) {
            case WIFI:
            case ALL:
                preferredTransport = TransportPreference.ALL;
                break;

            default:
                preferredTransport = TransportPreference.BLUETOOTH;
        }
    }

    /**
     * Updates this device's routing preference to include WiFi
     */
    public void addPreferSDR() {
        switch (preferredTransport) {
            case WIFI:
            case BLUETOOTH:
            case ALL:
                preferredTransport = TransportPreference.ALL;
                break;

            default:
                preferredTransport = TransportPreference.SDR;
        }
    }

    /**
     * Updates this device's routing preference to exclude WiFI
     */
    public void removePreferWiFi() {
        switch (preferredTransport) {
            case ALL:
            case BLUETOOTH:
                preferredTransport = TransportPreference.BLUETOOTH;
                break;

            default:
                preferredTransport = TransportPreference.AGNOSTIC;
        }
    }

    /**
     * Updates this device's routing preference to exclude BT
     */
    public void removePreferBt() {
        switch (preferredTransport) {
            case ALL:
            case WIFI:
                preferredTransport = TransportPreference.WIFI;
                break;

            default:
                preferredTransport = TransportPreference.AGNOSTIC;
        }
    }

    /**
     * Remove old devices from the roster
     * @return
     */
    public static boolean cullOldDevices() {
        if ((devices == null) || devices.isEmpty())
            return false;

        boolean culled = false;
        synchronized (devices) {
            int i = 0;
            while (i < devices.size()) {
                SqAnDevice device = devices.get(i);
                if (device == null)
                    devices.remove(i);
                else {
                    if (!device.isActive()) {
                        SavedTeammate teammate = Config.getTeammate(device.getUUID());
                        if ((teammate != null) && !teammate.isEnabled()) {
                            devices.remove(i);
                            CommsLog.log(CommsLog.Entry.Category.STATUS, "Removed disabled device " + device.getLabel());
                            continue;
                        }
                    }
                    if ((device.lastConnect > 0l) && (System.currentTimeMillis() > device.lastConnect + TIME_TO_STALE))
                        device.setStatus(Status.STALE);
                    device.cullOldRelayConnections();
                    i++;
                }
            }

            //move inactive devices to the bottom
            boolean passed = false;
            while (!passed) {
                passed = true;
                for (int a=devices.size()-1;a>0;a--) {
                    final SqAnDevice devA = devices.get(a);
                    final SqAnDevice devA1 = devices.get(a-1);
                    if (devA == null) {
                        devices.remove(a);
                        passed = false;
                        break;
                    }
                    if (devA1 == null) {
                        devices.remove(a-1);
                        passed = false;
                        break;
                    }
                    if (!devA1.isActive() && devA.isActive()) {
                        devices.set(a-1,devA);
                        devices.set(a,devA1);
                        passed = false;
                    }
                }
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
        if (this.lastLocation == null)
            this.lastLocation = lastLocation;
        else {
            if (lastLocation == null)
                return;
            if (lastLocation.getTime() > this.lastLocation.getTime())
                this.lastLocation = lastLocation;
        }
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
        //FIXME note: encrypted internal connections are outside of the scope of this project and will be handled sepsrately
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
        if (this.status != status) {
            this.status = status;
            switch(status) {
                case STALE:
                case OFFLINE:
                    if (connectionStart > 0l) {
                        CommsLog.log(CommsLog.Entry.Category.CONNECTION, callsign + " disconnected after " + StringUtil.toDuration(System.currentTimeMillis() - connectionStart));
                        connectionStart = Long.MIN_VALUE;
                    }
                    break;
            }
        }
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
        if (relays == null)
            return 0;
        int sum = 0;
        long cutoffTime = System.currentTimeMillis() - TIME_TO_CONSIDER_HOP_COUNT_STALE;
        for (RelayConnection relay:relays) {
            if ((relay.getHops() == 0) && (relay.getLastConnection() > cutoffTime))
                sum++;
        }
        return sum;
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
        if (current == null) {
            if (relays.size() > MAX_RELAY_CONNECTIONS_TO_SAVE)
                cullOldRelayConnections();
            relays.add(connection);
        } else
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
            if ((device != null) && device.isActive())
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
        if (other.transientAwareId != UNASSIGNED_UUID)
            transientAwareId = other.transientAwareId;
        if (other.callsign != null)
            callsign = other.callsign;
        if (other.uuidExtended != null)
            uuidExtended = other.uuidExtended;
        if (other.awareMac != null)
            awareMac = other.awareMac;
        if (other.lastLocation != null) {
            if ((lastLocation == null) || !lastLocation.isValid() || (other.lastLocation.getTime() > lastLocation.getTime()))
                lastLocation = other.lastLocation;
        }
        if (other.relays != null)
            relays = other.relays;
        else
            cullOldRelayConnections();
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
        synchronized (devices) {
            for (SqAnDevice device : devices) {
                if ((device != null) && (device.status == Status.CONNECTED))
                    sum++;
            }
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
        if ((uuid == other.uuid) && (uuid != UNASSIGNED_UUID))
            return true;
        if (other.isUuidKnown() && isUuidKnown())
            return false; //if both UUIDs are known and they did not already pass the == test, then this must be two different devices
        if ((other.networkId != null) && (networkId != null) && (other.networkId.length() > 1) && other.networkId.equalsIgnoreCase(networkId))
            return true;
        if ((bluetoothMac != null) && bluetoothMac.isEqual(other.bluetoothMac))
            return true;
        if ((transientAwareId != UNASSIGNED_UUID) && (transientAwareId == other.transientAwareId))
            return true;
        return false;
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
    public static boolean add(final SqAnDevice device) {
        if ((device == null) || (device.getUUID() == UNASSIGNED_UUID))
            return false;
        if ((Config.getThisDevice() != null) && (device.getUUID() == Config.getThisDevice().getUUID())) //dont add our own device
            return false;
        if (devices == null) {
            devices = new ArrayList<>();
            devices.add(device);
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,device.getLabel()+" was added to the list of devices.");
            return true;
        }
        SqAnDevice existing = find(device);
        if (existing == null) {
            synchronized (devices) {
                devices.add(device);
                CommsLog.log(CommsLog.Entry.Category.CONNECTION,device.getLabel()+" was added to the list of devices.");
            }
            return true;
        } else {
            existing.update(device);
            SavedTeammate teammate = new SavedTeammate(device.getUUID());
            teammate.setBluetoothMac(device.bluetoothMac);
            Config.saveTeammate(teammate);
        }
        return false;
    }

    /**
     * Gets a good human readable label for this device
     * @return
     */
    public String getLabel() {
        if (callsign != null)
            return callsign;
        if (uuid > 0)
            return Integer.toString(uuid);
        if ((networkId != null) && (networkId.length() > 1))
            return "WiFi "+networkId;
        if ((bluetoothMac != null) && bluetoothMac.isValid())
            return "BT "+bluetoothMac.toString();
        if (transientAwareId != UNASSIGNED_UUID)
            return "Aware ID "+transientAwareId;
        return "unknown";
    }

    public static void remove(final SqAnDevice device) {
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
    public static SqAnDevice find(final SqAnDevice other) {
        if ((other != null) && (devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device : devices) {
                    if ((device != null) && device.isSame(other))
                        return device;
                }
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
                if ((device != null) && device.isSame(uuid))
                    return device;
            }
        }
        return null;
    }

    /**
     * Finds a device in the list of devices based on the int representation of an IPV4 address
     * @param ip
     * @return the device (or null if UUID is not found)
     */
    public static SqAnDevice findByIpv4IP(int ip) {
        if ((devices != null) && !devices.isEmpty()) {
            for (SqAnDevice device : devices) {
                if ((device != null) && (ip == device.getVpnIpv4AddressInt()))
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
                if ((device != null) && (device.networkId != null) && device.networkId.equalsIgnoreCase(networkId))
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
            synchronized (devices) {
                for (SqAnDevice device : devices) {
                    if ((device != null) && (mac.isEqual(device.bluetoothMac)))
                        return device;
                }
            }
        }
        return null;
    }

    /**
     * Finds a device in the list of devices based on WiFi Direct MAC
     * @param mac WiFi Direct MAC
     * @return the device (or null if not found)
     */
    public static SqAnDevice findByWiFiDirectMac(String mac) { return findByWiFiDirectMac(MacAddress.build(mac)); }

    /**
     * Finds a device in the list of devices based on WiFi Direct MAC
     * @param mac WiFi Direct MAC
     * @return the device (or null if not found)
     */
    public static SqAnDevice findByWiFiDirectMac(MacAddress mac) {
        if ((mac != null) && (devices != null) && !devices.isEmpty()) {
            synchronized (devices) {
                for (SqAnDevice device : devices) {
                    if ((device != null) && (mac.isEqual(device.directMac)))
                        return device;
                }
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
        if (devices != null) {
            synchronized (devices) {
                boolean anyActive = false;
                boolean allActive = true;
                for (SqAnDevice device : devices) {
                    if (device != null) {
                        if (device.status == Status.CONNECTED)
                            anyActive = true;
                        else
                            allActive = false;
                    }
                }

                if (anyActive) {
                    if (allActive)
                        return FullMeshCapability.UP;
                    else
                        return FullMeshCapability.DEGRADED;
                }
            }
        }
        return FullMeshCapability.DOWN;
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
     * How many hops does it take for this device to reach a specific other device
     * @param sqAnID
     * @return the number of hops it takes to reach the specific device, or Integer.MAX_VALUE if this device does not have a connection
     */
    public int getHopsToDevice(int sqAnID) {
        if (sqAnID == uuid)
            return -1;
        if (relays != null) {
            for (RelayConnection relay:relays) {
                if (relay.getSqAnID() == sqAnID)
                    return relay.getHops();
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * Gets the total tally of bytes received from this device so far
     * @return tally (in bytes)
     */
    public long getDataTally() {
        return rxDataTally;
    }
    public void markDataTallyDisplayed() { displayedRxTally = rxDataTally; }
    public boolean isDataTallyGuiNeedUpdate() { return rxDataTally > displayedRxTally; }
    public String getNetworkId() {
        return networkId;
    }
    public void setNetworkId(String networkId) { this.networkId = networkId; }
    public void setDirectWiFiHiPerf(boolean isWifiInHighPerformanceMode) { this.directWiFiHiPerf = isWifiInHighPerformanceMode; }
    public void setConnected(int hopsAway, boolean directBt, boolean directWiFi) { setConnected(hopsAway, directBt, directWiFi,directWiFiHiPerf); }
    public void setConnected(int hopsAway, boolean directBt, boolean directWiFi, boolean directWiFiHiPerf) {
        setStatus(Status.CONNECTED);
        setLastConnect(System.currentTimeMillis());
        if (connectTime < 0l)
            connectTime = System.currentTimeMillis();
        if (hopsAway > this.hopsAway) {
            if (System.currentTimeMillis() > lastHopUpdate + TIME_TO_CONSIDER_HOP_COUNT_STALE) {
                lastHopUpdate = System.currentTimeMillis();
                setHopsAway(hopsAway, directBt, directWiFi, directWiFiHiPerf);
            }
        } else {
            lastHopUpdate = System.currentTimeMillis();
            setHopsAway(hopsAway, directBt, directWiFi, directWiFiHiPerf);
        }
    }

    public void setLastConnect() { setLastConnect(System.currentTimeMillis());
    }
    public void setLastConnect(long time) {
        lastConnect = time;
        if (connectionStart < 0l) {
            connectionStart = time;
            CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Connected to "+((callsign==null)?uuid:callsign)+((hopsAway>0)?(", ("+hopsAway+" hops away)"):""));
        }
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
    public void setRoleWiFi(NodeRole roleWiFi) {
        if (this.roleWiFi != roleWiFi) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"WiFi role now "+roleWiFi.name());
            this.roleWiFi = roleWiFi;
            if (roleWiFi == NodeRole.OFF) {
                addIssue(new WiFiConnectivityIssue());
                staleRolesCheck();
                if (uiSummary != null)
                    uiSummary.update(this);
            }
        }
    }

    /**
     * Gets this device's role in the network in Bluetooth comms
     * @return
     */
    public NodeRole getRoleBT() { return roleBT; }

    /**
     * Sets this device's role in the network in Bluetooth comms
     * @param roleBT
     */
    public void setRoleBT(NodeRole roleBT) {
        if (this.roleBT != roleBT) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth role now "+roleBT.name());
            this.roleBT = roleBT;
            if (roleBT == NodeRole.OFF)
                staleRolesCheck();
        }
    }

    public NodeRole getRoleSDR() { return roleSDR; }

    public void setRoleSDR(NodeRole roleSDR) {
        if (this.roleSDR != roleSDR) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"SDR role now "+roleSDR.name());
            this.roleSDR = roleSDR;
            if (roleSDR == NodeRole.OFF)
                staleRolesCheck();
        }
    }

    private void staleRolesCheck() {
        if ((roleWiFi == NodeRole.OFF) && (roleBT == NodeRole.OFF) && (roleSDR == NodeRole.OFF))
            setStatus(Status.STALE);
    }

    /**
     * Gets the int representation of this device's IPV4 IP address when using the SqAN VPN
     * @return
     */
    public int getVpnIpv4AddressInt() {
        if (ipV4Address == Integer.MIN_VALUE)
            ipV4Address = AddressUtil.getSqAnVpnIpv4Address(uuid);
        return ipV4Address;
    }

    /**
     * Gets the string representation of this device's IPV4 IP address when using the SqAN VPN
     * @return
     */
    public String getVpnIpv4AddressString() {
        return AddressUtil.intToIpv4String(getVpnIpv4AddressInt());
    }

    /**
     * Checks if there is an IP address collision between these two devices
     * @param other
     * @return
     */
    public boolean isVpnIpv4CollisionWith(SqAnDevice other) {
        if (other == null)
            return false;
        return getVpnIpv4AddressInt() == other.getVpnIpv4AddressInt();
    }

    private final static byte MASK_NONE =         (byte)0b00000000;
    private final static byte MASK_BACKHAUL =     (byte)0b10000000;
    private final static byte MASK_RESERVED_2 =   (byte)0b01000000;
    private final static byte MASK_RESERVED_3 =   (byte)0b00100000;
    private final static byte MASK_RESERVED_4 =   (byte)0b00010000;
    private final static byte MASK_RESERVED_5 =   (byte)0b00001000;
    private final static byte MASK_RESERVED_6 =   (byte)0b00000100;
    private final static byte MASK_RESERVED_7 =   (byte)0b00000010;
    private final static byte MASK_RESERVED_8 =   (byte)0b00000001;

    public byte getFlags() {
        byte flags = backhaulConnection?MASK_BACKHAUL:MASK_NONE;

        return flags;
    }

    public void parseFlags(byte data) {
        backhaulConnection = (MASK_BACKHAUL & data) == MASK_BACKHAUL;
    }
}
