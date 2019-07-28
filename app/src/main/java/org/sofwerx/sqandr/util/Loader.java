package org.sofwerx.sqandr.util;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.UsbSerialPort;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static java.lang.Thread.sleep;

/**
 * Helper class to assist in loading SqANDR on to Pluto and to get it running
 */
public class Loader {
    private final static String TAG = Config.TAG+".Loader";
    public final static String SDR_APP_LOCATION = "/var/tmp/";
    public final static String SQANDR_VERSION = "sqandr"; //eventually includes a build number to allow for execution

    private final static int MAX_BYTES_PER_CHUNK = 100; //max number of bytes to send per command line push

    private final static String INSTALL_HEADER = "busybox printf '";
    private final static String INSTALL_FOOTER = "' >> "+SQANDR_VERSION+"\n";
    public static void pushAppToSdr(@NonNull Context context, UsbSerialPort port, final SqANDRLoaderListener listener) {
        CommsLog.log(CommsLog.Entry.Category.SDR,"Installing the latest version of SqANDR on the SDR...");
        Log.d(TAG,"Trying to push sqandr to Pluto...");
        if (port == null) {
            Log.d(TAG,"Unable to push app to SDR as Serial Port is null");
            return;
        }
        new Thread(() -> {
            AssetManager am = context.getApplicationContext().getAssets();
            FileInputStream stream = null;
            try {
                AssetFileDescriptor assetFileDescriptor = am.openFd("sqandr");
                FileDescriptor fileDescriptor = assetFileDescriptor.getFileDescriptor();
                stream = new FileInputStream(fileDescriptor);
                Log.d(TAG, "sqandr found on Android");

                boolean success = true;
                success = success && write(port, "cd " + SDR_APP_LOCATION + "\n");
                success = success && write(port, "touch " + SQANDR_VERSION + "\n");
                success = success && write(port, "chmod +x " + SDR_APP_LOCATION + SQANDR_VERSION + "\n");
                //write(port,"echo \"-"+ SerialConnection.TX_GAIN+".00 dB\" > /sys/bus/iio/devices/iio:device1/out_voltage0_hardwaregain\n");

                if (!success)
                    throw new IOException("Unable to install SqANDR - could not create " + SQANDR_VERSION + " in " + SDR_APP_LOCATION);

                byte[] b = new byte[MAX_BYTES_PER_CHUNK];
                int bytesRead;
                String hexes;
                int total = 0;
                int chunks = 0;
                long start = System.currentTimeMillis();
                String command;
                int lengthRemaining = (int)assetFileDescriptor.getLength();
                final int totalChunks = lengthRemaining/MAX_BYTES_PER_CHUNK;
                boolean keepGoing = true;
                final long startOffset = assetFileDescriptor.getStartOffset();
                Log.d(TAG,"Skipping "+Long.toString(startOffset)+"b to get start of sqandr file");
                stream.skip(startOffset);
                while (keepGoing) {
                    if (lengthRemaining < 1)
                        keepGoing = false;
                    else {
                        if (lengthRemaining < MAX_BYTES_PER_CHUNK) {
                            keepGoing = (bytesRead = stream.read(b, 0, lengthRemaining)) >= 0;
                        } else
                            keepGoing = (bytesRead = stream.read(b)) >= 0;
                        if (keepGoing) {
                            hexes = StringUtils.toFormattedHex(b, bytesRead);
                            total += bytesRead;
                            command = INSTALL_HEADER + hexes + INSTALL_FOOTER;
                            Log.d(TAG, "Chunk " + chunks + " of " + totalChunks + ": " + command);
                            sleep(50); //give BusyBox a chance to complete the last write
                            if (!write(port, command))
                                throw new IOException("Error while installing SqANDR; unable to write chunk #" + chunks + " (" + bytesRead + "b) - you may need to disconnect and reconnect the SDR");
                            chunks++;
                            lengthRemaining -= bytesRead;
                            if (listener != null)
                                listener.onProgressPercent(100*chunks/totalChunks);
                        }
                    }
                }
                Log.d(TAG, SQANDR_VERSION + " (" + total + "b from "+chunks+" chunks) installed on SDR in "+ StringUtil.toDuration(System.currentTimeMillis()-start));
                if (listener != null)
                    listener.onSuccess();
            } catch (Exception e) {
                write(port, "rm " + SQANDR_VERSION + "\n");
                final String message;
                if ((e == null) || (e.getMessage() == null))
                    message = e.getClass().getSimpleName();
                else
                    message = e.getMessage();
                Log.e(TAG,message);
                listener.onFailure(message);
            } finally {
                try {
                    if (stream != null)
                        stream.close();
                } catch (IOException e) {
                    Log.e(TAG,"Error closing the FileInputStream from the SqANDR bin asset: "+e.getMessage());
                }
            }
        }).start();
    }

    private final static int PORT_WRITE_TIMEOUT = 1000 * 5;

    /**
     * Writes the command to the SerialDevice (blocking)
     * @param port
     * @param data
     * @return true == success
     */
    private static boolean write(@NonNull UsbSerialPort port, @NonNull String data) {
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        Log.d(TAG,"Outgoing: "+data);
        try {
            return (port.write(bytes,PORT_WRITE_TIMEOUT) == bytes.length);
        } catch (Exception e) {
            Log.e(TAG,e.getMessage());
        }
        return false;
    }

    /*public static void queryIsCurrentSqANDRInstalled(@NonNull UsbSerialPort port) {
        //write(port,"if [ -f "+SDR_APP_LOCATION+SQANDR_VERSION+" ] ; then echo \"SqANDR update needed: FALSE\" ; else echo \"SqANDR update needed: TRUE\" ; fi");
        final String check  ="#!/bin/bash\n" +
                "if [ -e "+SDR_APP_LOCATION+SQANDR_VERSION+" ]\n" +
                "then\n" +
                "    echo \"INSTALLED\"\n" +
                "else\n" +
                "    echo \"FAILED\"\n" +
                "fi\n";
        write(port,check);
        //write(port,"cd "+SDR_APP_LOCATION+"\n");
        //write(port,"ls\n");
    }*/
}
