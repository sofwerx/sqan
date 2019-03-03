package org.sofwerx.sqan.ipc;

import java.io.UnsupportedEncodingException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Class for sharing via IPC with apps that utilize SqAN health data for Common Operating Picture/Blue Force Tracker
 */
public class BftDevice {
    private int uuid;
    private double lat, lng, alt;
    private float horCEP;
    private long time;
    private String callsign;

    /**
     * Constructor for a device for representation in a COP/BFT
     * @param uuid SqAN device UUID
     * @param callsign
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE, WGS-84
     * @param horizontalUncertainty m
     * @param time
     */
    public BftDevice(int uuid, String callsign, double latitude, double longitude, double altitude, float horizontalUncertainty, long time) {
        this();
        this.uuid = uuid;
        this.callsign = callsign;
        setLocation(latitude, longitude, altitude, horizontalUncertainty);
        this.time = time;
    }

    public BftDevice() {
        lat = Double.NaN;
        lng = Double.NaN;
        alt = Double.NaN;
        horCEP = Float.NaN;
        time = Long.MIN_VALUE;
    }

    public BftDevice(int uuid, String callsign, long time) {
        this();
        this.uuid = uuid;
        this.callsign = callsign;
        this.time = time;
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

    public byte[] toBytes() {
        int size = 4 + 8 + 8 + 8 + 4 + 8 + 4;
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
        if (extras == null)
            out.putInt(0);
        else {
            out.putInt(extras.length);
            out.put(extras);
        }
        return out.array();
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
            //ByteBuffer in = ByteBuffer.wrap(bytes);
            //byte[] callsignBytes =
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
}
