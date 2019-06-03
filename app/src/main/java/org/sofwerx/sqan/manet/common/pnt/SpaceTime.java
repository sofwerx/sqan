package org.sofwerx.sqan.manet.common.pnt;

import android.location.Location;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.StringUtil;

import java.nio.ByteBuffer;

/**
 * Used to represent a location in spacetime
 * Lat/Lng/Alt are WGS-84 and vertically meters height above ellipsoid
 * Time is device local
 */
public class SpaceTime {
    public final static double NO_ALTITUDE = Double.NaN;
    private double latitude, longitude, altitude;
    private float accuracy = Float.NaN;
    private long time;
    private final static long ACCEPTABLE_TIME_DIFFERENCE = 1000l * 30l; //how far apart can measurements be to still be considered in the same reference frame

    public double getLatitude() { return latitude; }
    public void setLatitude(double latitude) { this.latitude = latitude; }
    public double getLongitude() { return longitude; }
    public void setLongitude(double longitude) { this.longitude = longitude; }

    public SpaceTime() {
        this(Double.NaN,Double.NaN,Double.NaN,Long.MIN_VALUE);
    }

    public SpaceTime(Location location) {
        this(Double.NaN,Double.NaN,Double.NaN,Long.MIN_VALUE);
        if (location != null) {
            latitude = location.getLatitude();
            longitude = location.getLongitude();
            if (location.hasAltitude())
                altitude = location.getAltitude();
            if (location.hasAccuracy())
                accuracy = location.getAccuracy();
            else
                accuracy = Float.NaN;
            time = NetworkTime.toNetworkTime(location.getTime());
        }
    }

    /**
     * SpaceTime
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE
     * @param time network time
     */
    public SpaceTime(double latitude, double longitude, double altitude, long time) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.time = time;
    }

    /**
     * SpaceTime
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param altitude m HAE
     */
    public SpaceTime(double latitude, double longitude, double altitude) {
        this(latitude,longitude,altitude,NetworkTime.toNetworkTime(System.currentTimeMillis()));
    }

    /**
     * SpaceTime
     * @param latitude WGS-84
     * @param longitude WGS-84
     */
    public SpaceTime(double latitude, double longitude) {
        this(latitude,longitude,NO_ALTITUDE,NetworkTime.toNetworkTime(System.currentTimeMillis()));
    }

    /**
     * SpaceTime
     * @param latitude WGS-84
     * @param longitude WGS-84
     * @param time network time
     */
    public SpaceTime(double latitude, double longitude, long time) {
        this(latitude,longitude,NO_ALTITUDE,time);
    }

    /**
     * Gets the altitude
     * @return m HAE or Double.NaN if no altitude
     */
    public double getAltitude() { return altitude; }

    /**
     * Set the altitude
     * @param altitude m HAE or Double.NaN if no altitude
     */
    public void setAltitude(double altitude) { this.altitude = altitude; }

    /**
     * Does this point contain altitude data
     * @return true == has altitude data
     */
    public boolean hasAltitude() { return !Double.isNaN(altitude); }

    /**
     * Gets the time
     * @return time as calculated based on network time
     */
    public long getTime() {
        return time;
    }

    /**
     * Sets the time
     * @param networkTime
     */
    public void setTime(long networkTime) {
        this.time = time;
    }

    /**
     * Does this point have the minimum data needed to be valuable
     * @return true == point has value
     */
    public boolean isValid() {
        return (time > 0l) && !Double.isNaN(latitude) && !Double.isNaN(longitude);
    }

    public final static int SIZE_IN_BYTES = 8 + 8 + 8 + 4 + 8;

    public byte[] toByteArray() {
        ByteBuffer out = ByteBuffer.allocate(SIZE_IN_BYTES);
        out.putDouble(latitude);
        out.putDouble(longitude);
        out.putDouble(altitude);
        out.putFloat(accuracy);
        out.putLong(time);
        return out.array();
    }

    /**
     * Gets a byte array representing an empty SpaceTime
     * @return
     */
    public static byte[] toByteArrayEmptySpaceTime() {
        SpaceTime spaceTime = new SpaceTime();
        return spaceTime.toByteArray();
    }

    public void parse(byte[] bytes) {
        if ((bytes == null) || (bytes.length != SIZE_IN_BYTES)) {
            Log.e(Config.TAG,"Unable to processPacketAndNotifyManet SpaceTime as "+SIZE_IN_BYTES+"b expected");
            return;
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        latitude = buf.getDouble();
        longitude = buf.getDouble();
        altitude = buf.getDouble();
        accuracy = buf.getFloat();
        time = buf.getLong();
    }

    /**
     * Gets the distance between the two locations in meters
     * @param other
     * @return distance in meters (or NaN if not valid)
     */
    public double getDistance(SpaceTime other) {
        if (isValid() && (other != null) && other.isValid()) {
            if ((time > 0l) && (other.time > 0l) && Math.abs(time - other.time) < ACCEPTABLE_TIME_DIFFERENCE) {
                Location a = new Location("sqan");
                Location b = new Location("sqan");
                a.setLatitude(latitude);
                a.setLongitude(longitude);
                b.setLatitude(other.latitude);
                b.setLongitude(other.longitude);
                return a.distanceTo(b);
            }
        }
        return Double.NaN;
    }

    /**
     * Gets the accuracy (1SD)
     * @return accuracy in meters (or NaN if the accuracy is not available)
     */
    public float getAccuracy() { return accuracy; }

    /**
     * Sets the accuracy (1SD)
     * @param accuracy accuracy in meters
     */
    public void setAccuracy(float accuracy) { this.accuracy = accuracy; }

    public boolean hasAccuracy() { return !Float.isNaN(accuracy); }

    /**
     * Gets the total accuracy error between two points
     * @return accuracy in meters (or NaN if cannot be computed)
     */
    public float getTotalAccuracy(SpaceTime other) {
        if ((other == null) || Float.isNaN(accuracy) || Float.isNaN(other.accuracy) || (accuracy < 0f) || (other.accuracy < 0f))
            return Float.NaN;
        return (accuracy + other.accuracy) + 0.67f;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isValid()) {
            sb.append(String.format("%.5f", latitude));
            sb.append(",");
            sb.append(String.format("%.5f", longitude));
            if (!Double.isNaN(altitude)) {
                sb.append(",");
                sb.append((int)(altitude));
                sb.append("ft MSL");
            }
            if (!Double.isNaN(accuracy))
                sb.append(", ±"+(int)accuracy+"ft");
            sb.append(" ");
            sb.append(StringUtil.toDuration(System.currentTimeMillis() - time)+" ago");
        } else
            sb.append("no location");
        return sb.toString();
    }
}
