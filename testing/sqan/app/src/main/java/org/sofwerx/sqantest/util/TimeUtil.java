package org.sofwerx.sqantest.util;

public class TimeUtil {
    private final static long SEC = 1000l;
    private final static long MIN = SEC * 60l;
    private final static long HOUR = MIN * 60l;

    public final static String getDuration(long duration) {
        if (duration < 1000l)
            return "immediately";
        if (duration < 2l * MIN)
            return Long.toString(duration/SEC)+"s";
        else {
            if (duration < 2l * HOUR)
                return Long.toString(duration/MIN)+"m";
            else
                return Long.toString(duration/HOUR)+"h";
        }
    }
}
