package org.sofwerx.sqan.manet.common.pnt;

/**
 * Used to translate small deviations between devices into a universally agreed upon MANET time
 * //FIXME this is just stubbed out for now
 */
public class NetworkTime {
    private static long localOffset = 0l; //localTime + localOffset == NetworkTime

    /**
     * Converts local time to network time
     * @param localTime
     * @return network time
     */
    public static long toNetworkTime(long localTime) {
        if ((localTime == Long.MIN_VALUE) || (localTime == Long.MAX_VALUE))
            return localTime;
        else
            return localTime + localOffset;
    }

    /**
     * Gets the current network time
     * @return the current network time
     */
    public static long getNetworkTimeNow() {
        return System.currentTimeMillis()+localOffset;
    }

    /**
     * Converts network time to local time
     * @param networkTime
     * @return local time
     */
    public static long toLocalTime(long networkTime) {
        if ((networkTime == Long.MIN_VALUE) || (networkTime == Long.MAX_VALUE))
            return networkTime;
        else
            return networkTime - localOffset;
    }
}
