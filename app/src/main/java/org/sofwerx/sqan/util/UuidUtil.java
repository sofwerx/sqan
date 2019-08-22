package org.sofwerx.sqan.util;

import java.io.StringWriter;
import java.util.Random;
import java.util.UUID;

public class UuidUtil {
    public final static String getRandomCallsign() {
        StringWriter out = new StringWriter();
        Random rm = new Random();
        out.append(CALLSIGN_STOCK[rm.nextInt(CALLSIGN_STOCK.length)]);
        out.append(' ');
        out.append(Integer.toString(rm.nextInt(10)));
        out.append(Integer.toString(rm.nextInt(10)));
        return out.toString();
    }

    private final static String[] CALLSIGN_STOCK = new String[] {
            //"Red", "Blue", "Yellow", "Green", "Orange", "Purple", "Brown", "Black"
            "Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot", "Golf", "Hotel", "India", "Juliet", "Kilo", "Lima", "Mike", "November", "Oscar", "Papa", "Quebec", "Romeo", "Sierra", "Tango", "Uniform", "Victor", "Whiskey", "X-ray", "Yankee", "Zulu"
    };

    public static int getNewUUID() {
        //int uuid = (int)(System.currentTimeMillis()/1000l); //drop the 1st 8 bits of the current time
        //return Math.abs(uuid);
        return (int)(System.currentTimeMillis()/1000l);
    }

    public static String getNewExtendedUUID() {
        UUID uuid = UUID.randomUUID();
        return uuid.toString();
    }
}
