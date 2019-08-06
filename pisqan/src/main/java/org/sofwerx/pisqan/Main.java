package org.sofwerx.pisqan;

import org.sofwerx.notdroid.content.Context;
import org.sofwerx.notdroid.hardware.usb.UsbDevice;
import org.sofwerx.sqandr.serial.SerialConnection;

import java.util.Random;

import static java.lang.Thread.sleep;

public class Main {
    private final static String TAG = Config.TAG+".Main";
    public static int MAX_INTERVAL = 10000;    // time between sending data, in ms

    public static void main(String[] argv) {
        Log.LOG_LEVEL = Config.DFLT_LOG_LEVEL;

        Context context = new Context();
        UsbDevice usbDev = new UsbDevice();

        SerialConnection commo = new SerialConnection(Config.TERMINAL_USERNAME, Config.TERMINAL_PASSWORD);
        commo.open(context, usbDev);

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

