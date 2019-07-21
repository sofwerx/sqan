package org.sofwerx.sqan.ipc;

import org.osmdroid.views.overlay.Marker;

import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Class for sharing via IPC with apps that utilize SqAN health data for Common Operating Picture/Blue Force Tracker
 */
public class BftDevice {
    private int uuid;
    private double lat, lng, alt;
    private float horCEP;
    private long time;
    private String callsign;
    private boolean directBt = false;
    private boolean directWiFi = false;
    private ArrayList<Link> links = new ArrayList<>();

    /**
     * Constructor for a device for representation in a COP/BFT
     * @param uuid SqAN device UUID
     * @param callsign
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE, WGS-84
     * @param horizontalUncertainty m
     * @param time
     * @param directBt is this device directly connected via Bluetooth
     * @param directWiFi is this device directly connected via WiFi
     * @param links gets the links between this device and other devices
     */
    public BftDevice(int uuid, String callsign, double latitude, double longitude, double altitude, float horizontalUncertainty, long time, boolean directBt, boolean directWiFi, ArrayList<Link> links) {
        this();
        this.uuid = uuid;
        this.callsign = callsign;
        setLocation(latitude, longitude, altitude, horizontalUncertainty);
        this.time = time;
        this.directBt = directBt;
        this.directWiFi = directWiFi;
        this.links = links;
    }

    public BftDevice() {
        lat = Double.NaN;
        lng = Double.NaN;
        alt = Double.NaN;
        horCEP = Float.NaN;
        time = Long.MIN_VALUE;
    }

    public BftDevice(int uuid, String callsign, long time, boolean directBt, boolean directWiFi) {
        this();
        this.uuid = uuid;
        this.callsign = callsign;
        this.time = time;
        this.directBt = directBt;
        this.directWiFi = directWiFi;
    }

    /**
     *
     * @return WGS-84
     */
    public double getLatitude() {
        return lat;
    }

    /**
     *
     * @return WGS-84
     */
    public double getLongitude() {
        return lng;
    }

    /**
     *
     * @return m HAE
     */
    public double getAltitude() {
        return alt;
    }

    /**
     *
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE
     * @param horizontalUncertainty m
     */
    public void setLocation(double latitude, double longitude, double altitude, float horizontalUncertainty) {
        this.lat = latitude;
        this.lng = longitude;
        this.alt = altitude;
        this.horCEP = horizontalUncertainty;
    }

    /**
     *
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE
     */
    public void setLocation(double latitude, double longitude, double altitude) {
        setLocation(latitude, longitude, altitude,horCEP);
    }

    /**
     *
     * @param latitude WGS-84
     * @param longitude WGS-84
     */
    public void setLocation(double latitude, double longitude) {
        setLocation(latitude,longitude,alt);
    }

    public boolean hasAltitude() {
        return !Double.isNaN(alt);
    }

    /**
     *
     * @return m (standard not specified, but assumed to be 1 standard deviation)
     */
    public float getHorizontalUncertainty() {
        return horCEP;
    }

    public boolean hasHorizontalUncertainty() {
        return !Float.isNaN(horCEP);
    }

    /**
     *
     * @param uncertainty m (standard not specified, but assumed to be 1 standard deviation)
     */
    public void setHorizontalUncertainty(float uncertainty) {
        horCEP = uncertainty;
    }

    /**
     *
     * @return unix time in ms
     */
    public long getTime() {
        return time;
    }

    public void setTime(long time) {
        this.time = time;
    }

    /**
     * The callsign for this device in SqAN
     * @return
     */
    public String getCallsign() {
        return callsign;
    }

    public int getUUID() {
        return uuid;
    }

    private final static byte FLAG_BT = 0b00000001;
    private final static byte FLAG_WIFI = 0b00000010;
    public byte[] toBytes() {
        int size = 4 + 8 + 8 + 8 + 4 + 8 + 4 + 1 + 4;
        byte[] linkBytes = null;
        if ((links != null) && !links.isEmpty()) {
            synchronized (links) {
                try {
                    int linksByteSize = Link.BYTE_SIZE * links.size();
                    size += linksByteSize;
                    ByteBuffer linkBuf = ByteBuffer.allocate(linksByteSize);
                    for (Link link:links) {
                        linkBuf.put(link.toBytes());
                    }
                    linkBytes = linkBuf.array();
                } catch (Exception ignore) {
                }
            }
        }
        byte[] extras = extrasToBytes();
        if (extras != null)
            size += extras.length;
        ByteBuffer out = ByteBuffer.allocate(size);
        out.putInt(uuid);
        out.putDouble(lat);
        out.putDouble(lng);
        out.putDouble(alt);
        out.putFloat(horCEP);
        out.putLong(time);
        byte flags = 0b00000000;
        if (directBt)
            flags = (byte)(flags | FLAG_BT);
        if (directWiFi)
            flags = (byte)(flags | FLAG_WIFI);
        out.put(flags);

        if (linkBytes == null)
            out.putInt(0);
        else {
            out.putInt(linkBytes.length);
            out.put(linkBytes);
        }

        if (extras == null)
            out.putInt(0);
        else {
            out.putInt(extras.length);
            out.put(extras);
        }
        return out.array();
    }

    public void addLink(Link link) {
        if (link == null)
            return;
        if (links == null)
            links = new ArrayList<>();
        links.add(link);
    }

    private byte[] extrasToBytes() {
        byte[] callsignBytes = null;
        if (callsign != null) {
            try {
                callsignBytes = callsign.getBytes("UTF-8");
            } catch (UnsupportedEncodingException ignore) {
            }
        }
        return callsignBytes;
    }

    private void parseExtras(byte[] bytes) {
        if (bytes == null)
            return;
        try {
            callsign = new String(bytes,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            e.printStackTrace();
        }
    }

    public void parse(byte[] bytes) {
        if (bytes == null)
            return;
        try {
            ByteBuffer in = ByteBuffer.wrap(bytes);
            uuid = in.getInt();
            lat = in.getDouble();
            lng = in.getDouble();
            alt = in.getDouble();
            horCEP = in.getFloat();
            time = in.getLong();
            byte flags = in.get();
            directBt = (flags & FLAG_BT) == FLAG_BT;
            directWiFi = (flags & FLAG_WIFI) == FLAG_WIFI;
            int lenLinks = in.getInt();
            if (lenLinks > 0) {
                int num = lenLinks/Link.BYTE_SIZE;
                byte[] linkByte = new byte[Link.BYTE_SIZE];
                Link link;
                for (int i=0;i<num;i++) {
                    in.get(linkByte);
                    link = new Link(linkByte);
                    if (links == null)
                        links = new ArrayList<>();
                    links.add(link);
                }
            }
            int lenExtras = in.getInt();
            if (lenExtras > 0) {
                byte[] extras = new byte[lenExtras];
                in.get(extras);
                parseExtras(extras);
            }
        } catch (BufferUnderflowException e) {
            e.printStackTrace();
        }
    }

    /**
     * Is directly connected by BT
     * @return
     */
    public boolean isDirectBt() { return directBt; }
    public void setDirectBt(boolean directBt) { this.directBt = directBt; }

    /**
     * Is directly connected by WiFi
     * @return
     */
    public boolean isDirectWiFi() { return directWiFi; }
    public void setDirectWiFi(boolean directWiFi) { this.directWiFi = directWiFi; }

    /**
     * Gets the links between this device and other devices
     * @return
     */
    public ArrayList<Link> getLinks() { return links; }

    /**
     * Class that describes this devices linkage with other devices (useful for
     * visualizing network architecture)
     */
    public static class Link {
        public final static int BYTE_SIZE = 4 + 1;
        int uuid;
        boolean directBt;
        boolean directWiFi;

        public Link(int uuid, boolean directBt, boolean directWiFi) {
            this.uuid = uuid;
            this.directBt = directBt;
            this.directWiFi = directWiFi;
        }

        public Link(byte[] bytes) {
            if (bytes != null) {
                try {
                    ByteBuffer buf = ByteBuffer.wrap(bytes);
                    uuid = buf.getInt();
                    byte flags = buf.get();
                    directBt = (flags & FLAG_BT) == FLAG_BT;
                    directWiFi = (flags & FLAG_WIFI) == FLAG_WIFI;
                } catch (Exception ignore) {
                }
            }
        }

        public int getUUID() { return uuid; }
        public boolean isDirectBt() { return directBt; }
        public boolean isDirectWiFi() { return directWiFi; }

        public byte[] toBytes() {
            ByteBuffer out = ByteBuffer.allocate(BYTE_SIZE);
            out.putInt(uuid);
            byte flags = 0b00000000;
            if (directBt)
                flags = (byte)(flags | FLAG_BT);
            if (directWiFi)
                flags = (byte)(flags | FLAG_WIFI);
            out.put(flags);
            return out.array();
        }
    }
}
