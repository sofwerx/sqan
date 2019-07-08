package org.sofwerx.pisqan;

import org.sofwerx.sqandr.serial.SerialConnection;

import java.util.Random;

import static java.lang.Thread.sleep;
import static org.sofwerx.pisqan.Log.*;

public class Main {
    private final static String TAG = Config.TAG+".Main";
    public static int MAX_INTERVAL = 10000;    // time between sending data, in ms
    public static LogLevel LOG_LEVEL = LogLevel.WARNING;

    public static void main(String[] argv) {
        LOG_LEVEL = LogLevel.DEBUG;

        SerialConnection commo = new SerialConnection();
        commo.open();

        try {
            sleep(1000l * 10l);
        }
        catch (Exception e) {
            Log.w(TAG, e.getMessage());
        }

        int i = 0;
        Random rnd = new Random(Double.doubleToLongBits(Math.random()));
        while (true) {
            try {
                ++i;
                int delay = rnd.nextInt(MAX_INTERVAL);
                Log.d(TAG, "Waiting " + delay/1000.0 + " seconds...");
                sleep(delay);
                String outstr = "Hello world! This is transmission #" + i;
                commo.burstPacket(outstr.getBytes());
                Log.d(TAG, "Wrote: " + outstr);
            }
            catch (Exception e) {
                Log.w(TAG, "Something went wrong in main(), tx #" +i + ": " + e.getMessage());
            }
        }


    }
}

