package org.sofwerx.sqan.vpn;

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.VpnPacket;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqAnVpnConnection implements Runnable {
    private final static String TAG = Config.TAG+".Vpn";
    private static final int SIZE_TO_ROUTE_WIFI_ONLY = 256; //byte size to be considered too large and should be sent over broad pipes only
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE; //Max packet size cannot exceed MTU constraint of Short
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    private final VpnService vpnService;
    private SqAnService sqAnService;
    private SqAnDevice thisDevice;
    private int thisDeviceIp;
    private final int id;
    private PendingIntent configureIntent;
    private OnEstablishListener listener;
    private final AtomicBoolean keepGoing = new AtomicBoolean(true);

    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    /**
     * TODO
     *
     * VPN forwarding of non-SqAN IPs has been partially implemented but development
     * against this requirement has been paused as there was no priority requirement
     * identified that necessitates it. The implementation included creating a series
     * of 169.x.x.x IP addresses for each device on SqAN that would serve as the SqAN
     * facing IP address and was mapped to the IP address of device on a non-SqAN
     * network connected to this device. Methods were built to extract and change IPV4
     * header information to support routing across SqAN, but the underlying requirement
     * to change UDP and TCP packets as well was not completed. Any resumed implementation
     * of forwarding packets would likely need to implement some port assignments as
     * well as modifying UDP and TCP headers.
     *
     */

    public SqAnVpnConnection(final VpnService service, final int connectionId) {
        vpnService = service;
        sqAnService = SqAnService.getInstance();
        thisDevice = Config.getThisDevice();
        if (thisDevice == null)
            thisDeviceIp = 0;
        else
            thisDeviceIp = AddressUtil.getSqAnVpnIpv4Address(thisDevice.getUUID());
        id = connectionId;
    }

    public void setConfigureIntent(PendingIntent intent) {
        configureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        this.listener = listener;
    }

    @Override
    public void run() {
        ParcelFileDescriptor pfd = configure();
        if (pfd == null) {
            Log.d(getTag(),"run() exited as could not fet ParcelFileDescriptor");
            return;
        }

        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());

        ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

        while (keepGoing.get()) {
            boolean idle = true;
            try {
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    packet.rewind();
                    byte[] rawBytes = new byte[length];
                    packet.get(rawBytes);
                    VpnPacket outgoing = new VpnPacket(new PacketHeader(thisDevice.getUUID()));
                    int destinationIp = NetUtil.getDestinationIpFromIpPacket(rawBytes);

                    SqAnDevice device = SqAnDevice.findByIpv4IP(destinationIp);
                    if (!Config.isIgnoringPacketsTo0000() || (destinationIp != 0)) {
                        if (Config.isVpnForwardIps()) {
                            int sourceIp = NetUtil.getSourceIpFromIpPacket(rawBytes);
                            if (sourceIp != thisDeviceIp) {
                                VpnForwardValue forwardValue = thisDevice.getOrAddIpForwardAddress(sourceIp,Config.isVpnAutoAdd());
                                if (forwardValue == null) {
                                    Log.d(getTag(),"Packet from "+AddressUtil.intToIpv4String(sourceIp)+" was blocked from forwarding traffic as it is not on the list of forwarding IP addresses.");
                                    outgoing = null;
                                } else {
                                    outgoing.setForwardValue(forwardValue);
                                    int newSource = AddressUtil.getSqAnVpnIpvForwardingAddress(thisDevice.getUUID(), forwardValue);
                                    NetUtil.changeIpv4HeaderSrc(rawBytes, newSource);
                                    Log.d(getTag(), "Forwarding a packet from " + AddressUtil.intToIpv4String(sourceIp) + " (altering to appear from " + AddressUtil.intToIpv4String(newSource) + ")");
                                    if (device == null)
                                        swapIpInPayload(rawBytes, sourceIp, newSource);
                                }
                            }
                        }

                        if (outgoing != null) {
                            NetUtil.PacketType type = NetUtil.getPacketType(rawBytes);
                            byte dscp = NetUtil.getDscpFromIpPacket(rawBytes); //TODO for future routing decisions
                            int srcPort = NetUtil.getSourcePort(rawBytes);
                            int destPort = NetUtil.getDestinationPort(rawBytes);

                            //FIXME for testing
                            String ipAdd = AddressUtil.intToIpv4String(destinationIp);
                            String port = Integer.toString(destPort);
                            Log.d(TAG,"VpnPkt out to SqAN ("+ipAdd+":"+port+"): "+new String(rawBytes,StandardCharsets.US_ASCII));
                            Log.d(TAG,"VpnPkt out to SqAN ("+ipAdd+":"+port+"): "+StringUtils.toHex(rawBytes));
                            //FIXME for testing

                            if (destinationIp != SqAnDevice.BROADCAST_IP) {
                                if (device == null) {
                                    Log.d(getTag(), "VpnPacket destined for an IP address (" + AddressUtil.intToIpv4String(destinationIp)+ ":" + destPort + ") that I do not recognize - broadcasting this message to all devices");
                                } else {
                                /*if (Config.portForwardingEnabled()) {
                                    int port = NetUtil.getPort(rawBytes);
                                    Log.d(getTag(),"Port "+port+" found for forwarding...");
                                }*/
                                    Log.d(getTag(), "VpnPacket (DSCP " + NetUtil.getDscpType(dscp).name() + ", " + type.name() + ") being sent to " + device.getLabel()+", src port "+srcPort+", dest port "+destPort);
                                    outgoing.setDestination(device.getUUID());
                                }
                            }
                            outgoing.setData(rawBytes);
                            if (Config.isLargeDataWiFiOnly() && (length > SIZE_TO_ROUTE_WIFI_ONLY))
                                outgoing.setHighPerformanceNeeded(true);
                            sqAnService.burst(outgoing);
                        }
                    }

                    packet.clear();

                    idle = false;
                }
            } catch (IOException e) {
                String message = e.getMessage();
                Log.e(getTag(),"IOException: "+message);
                if ((pfd == null) || ((message != null) && message.contains("EBADF")))
                    keepGoing.set(false);
            } catch (BufferUnderflowException e) {
                Log.e(getTag(),"BufferUnderflowException: "+e.getMessage());
            }
            if (idle) {
                try {
                    Thread.sleep(IDLE_INTERVAL_MS);
                } catch (InterruptedException ignore) {
                }
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException e) {
                Log.e(getTag(), "Unable to close interface", e);
            }
        }
    }

    /**
     * Looks for the original IP address in the data from a packet and swaps any
     * occurrence out with the newIp. Used to adjust broadcast messages from connected
     * devices whos IP addresses are being altered to support SqAN transmission
     * @param packet
     * @param toFind the IP address to find
     * @param replacement the new IP address to replace the found IP
     */
    public static void swapIpInPayload(byte[] packet, byte[] toFind, byte[] replacement) {
        if ((packet == null) || (toFind == null) || (replacement == null) || (toFind.length != replacement.length))
            return;
        int len = NetUtil.getHeaderLength(packet);
        byte[] compare = {(byte)0,(byte)0,(byte)0,(byte)0};
        int max = packet.length - len;
        if (max > 3) {
            //its an arbitrary number, but only consider the first 96 bytes of the payload
            //as there is where any discovery related IP address information is likely
            //to be. If we look further into the packet: 1) it takes more time and
            //2) it becomes more likely that the IP address we are looking for occurs
            //randomly in data
            if (max > 96) //only consider the first 96 bytes of the payload
                max = 96;
            max += len;
            boolean match;
            for (int i = len + 3;i<max;i++) {
                compare[0] = compare[1];
                compare[1] = compare[2];
                compare[2] = compare[3];
                compare[3] = packet[i];
                match = true;
                for (int b=0;b<4;b++) {
                    if (compare[b] != toFind[b]) {
                        match = false;
                        continue;
                    }
                }
                if (match) {
                    Log.d(TAG,"IP address swapped in broadcast packet");
                    packet[i-3] = replacement[0];
                    packet[i-2] = replacement[1];
                    packet[i-1] = replacement[2];
                    packet[i-0] = replacement[3];
                }
            }
        }
    }

    /**
     * Looks for the original IP address in the data from a packet and swaps any
     * occurrence out with the newIp. Used to adjust broadcast messages from connected
     * devices whos IP addresses are being altered to support SqAN transmission
     * @param packet
     * @param originalIp
     * @param newIp
     */
    public static void swapIpInPayload(byte[] packet, int originalIp, int newIp) {
        swapIpInPayload(packet,NetUtil.intToByteArray(originalIp),NetUtil.intToByteArray(newIp));
    }

    private ParcelFileDescriptor configure() {
        if (thisDevice == null) {
            Log.e(getTag(),"Cannot configure VPN as SqAnDevice is null");
            return null;
        }
        VpnService.Builder builder = vpnService.new Builder();
        builder.setMtu(Config.getMtuSize());
        builder.addAddress(thisDevice.getVpnIpv4AddressString(),16);
        builder.addRoute(AddressUtil.VPN_NET_MASK,8);
        if (Config.isMulticastEnabled()) {
            for (String route:AddressUtil.VPN_MULTICAST_MASK) {
                builder.addRoute(route, 8);
            }
        }
        final ParcelFileDescriptor vpnInterface;
        builder.setSession("SqAnVpn").setConfigureIntent(configureIntent);
        synchronized (vpnService) {
            vpnInterface = builder.establish();
            if (listener != null)
                listener.onEstablish(vpnInterface);
        }
        Log.i(getTag(), "New interface: " + vpnInterface);
        return vpnInterface;
    }

    private final String getTag() {
        return TAG + "[" + id + "]";
    }
}