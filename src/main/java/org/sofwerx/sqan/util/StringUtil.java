package org.sofwerx.sqan.util;

import java.text.SimpleDateFormat;


public class StringUtil {
    private final static long SEC = 1000l;
    private final static long MIN = SEC * 60l;
    private final static long HOUR = MIN * 60l;
    private final static long DAY = HOUR * 24l;

    private static SimpleDateFormat DF = new SimpleDateFormat();

    public final static String toDuration(long duration) {
        if (duration < 2000l)
            return Long.toString(duration)+"ms";
        else if (duration < 2l * MIN)
            return Long.toString(duration/SEC)+"s";
        else if (duration < 2l * HOUR)
            return Long.toString(duration/MIN)+"m";
        else
            return Long.toString(duration/HOUR)+"h";
    }

    public final static String toDataSize(long dataSize) {
        if (dataSize < 0l)
            return "invalid size";
        if (dataSize > 0l) {
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" bytes";
            dataSize = dataSize / 1024l;
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" kB";
            dataSize = dataSize / 1024l;
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" mB";
            dataSize = dataSize / 1024l;
            return Long.toString(dataSize)+" gB";
        }
        return "no bytes";
    }

    public static String getFilesafeTime(long time) {
        if (time < 0l)
            return "unknown";
        else
            return DF.format(new java.util.Date(time));
    }

    public static String getFormattedJustTime(long time) {
        return DF.format(new java.util.Date(time));
    }

    public static String getFormattedTime(long time) {
        if (time < 0l)
            return "unknown";
        else {
            long timeDiff = System.currentTimeMillis() - time;
            String format;
            if ((timeDiff < 0) || (timeDiff > DAY))
                format = "MM-dd-yy";
            else {
                if (timeDiff > 18l*HOUR)
                    format = "MM-dd HH:mm";
                else
                    format = "HH:mm";
            }
            return DF.format(new java.util.Date(time));
        }
    }

    public static String getDataRate(long dataTally, long elapsedTime) {
        if (elapsedTime < 1)
            return "-";
        else {
            int bps = (int)(dataTally/elapsedTime);
            if (bps > 2048) {
                bps = bps / 1024;
                if (bps > 2048)
                    return (bps/1024)+"Mbps";
                return bps+"Kbps";
            } else
                return bps+"bps";
        }
    }
}
