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

    public static String getNewUUID() {
        UUID uuid = UUID.randomUUID();
        String fullUUID = uuid.toString();
        String subUUID = fullUUID.substring(fullUUID.length()-7,fullUUID.length()-1); //use a smaller UUID to speed up UUID-based searches and to possibly address connection delays in Nearby Connections
        return subUUID;
    }
}
