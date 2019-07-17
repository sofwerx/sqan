package org.sofwerx.sqandr;

import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqandr.util.SdrUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Deprecated
public class SdrSocket {
    private final static String TAG = Config.TAG+".socket";
    private final static int POOL_WARNING_SIZE = 1000; //warn if the write queue gets bigger than this
    public final static int MAX_PACKET_SIZE = 1600; //FIXME arbitrary picked size
    private final static byte ALIGNMENT_BYTE_A = 0b0100100;
    private final static byte ALIGNMENT_BYTE_B = 0b1011011;
    private final static byte ALIGNMENT_BYTE_C = 0b1011110;
    private final static byte ALIGNMENT_BYTE_D = 0b1100000;
    private InputStream inStream;
    private OutputStream outStream;
    private SqANDRService sqANDRService;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);
    private Thread readThread;
    private static ArrayList<Thread> readThreads = new ArrayList();
    private static ExecutorService writeThread;
    private static AtomicInteger poolCount = new AtomicInteger(0);
    private long lastConnectInbound = Long.MIN_VALUE;
    private long lastConnectOutbound = Long.MIN_VALUE;

    //private static final Object readThreadLock = new Object();
    private static final Object writeThreadLock = new Object();

    public SdrSocket(@NonNull SqANDRService sqANDRService) {
        this.sqANDRService = sqANDRService;
    }

    public void open(InputStream in, OutputStream out) {
        if ((in == null) || (out == null)) {
            Log.e(TAG,"Cannot open connection without both and Input and Output Stream");
            return;
        }
        inStream = in;
        outStream = out;
        readAsyncPacket();
    }

    public void close() {
        keepGoing.set(false);
        if (inStream != null) {
            try {
                inStream.close();
            } catch (IOException e) {
                Log.e(TAG,"Cannot close inStream: "+e.getMessage());
            }
            inStream = null;
        }
        if (outStream != null) {
            try {
                outStream.close();
            } catch (IOException e) {
                Log.e(TAG,"Cannot close inStream: "+e.getMessage());
            }
            outStream = null;
        }
    }

    /**
     * Read an Int from stream (blocking)
     * @return int
     * @throws IOException
     */
    private int readInt() throws IOException {
        //Log.d(TAG,getLogHeader()+" readInt()");
        byte[] byteInt = new byte[4];
        if (inStream == null)
            throw new IOException("Cannot readInt(), inStream is null");
        try {
            inStream.read(byteInt);
        } catch (Exception e) {
            throw new IOException("Error in readInt(): "+e.getMessage());
        }
        return SdrUtils.getInt(byteInt);
    }

    /**
     * Read a packet of raw byte array data from stream (blocking)
     * @return
     * @throws IOException
     */
    private byte[] readPacketData() throws IOException {
        //Log.d(TAG,getLogHeader()+" readPacketData()");
        readAlignmentByte();
        int size = readInt();
        if (size > 0) {
            if (size > MAX_PACKET_SIZE) {
                String message = "readPacketData() is reporting a packet size of "+size+" which seems invalid. Packet being dropped...";
                Log.e(TAG, message);
                throw new IOException(message);
            }
            byte[] data = new byte[size];
            //Log.d(TAG,getLogHeader()+" readPacketData() looking for "+size+"b payload");
            inStream.read(data);
            byte checksum = (byte)inStream.read();
            byte calculatedChecksum = NetUtil.getChecksum(data);
            if (checksum != calculatedChecksum) {
                if (sqANDRService != null)
                    sqANDRService.onPacketDropped();
                throw new IOException("received a packet that did not have the proper checksum (" + calculatedChecksum + " expected but " + checksum + " received)");
            } else {
                //Log.d(TAG, getLogHeader() + " readPacketData() received data");
                return data;
            }
        } else {
            if (sqANDRService != null)
                sqANDRService.onPacketDropped();
            throw new IOException("readPacketData failed as reported data size is "+size+"b");
        }
    }

    private void readAsyncPacket() {
        if (readThread == null) {
            readThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, "readThread created");
                    while (keepGoing.get()) {
                        try {
                            byte[] data = readPacketData();
                            if (data == null)
                                Log.e(TAG, "readPacketData produced null data");
                            else {
                                Log.d(TAG, "readPacketData returned " + data.length + "b");
                                lastConnectInbound = System.currentTimeMillis();
                                if (sqANDRService != null)
                                    sqANDRService.onPacketReceived(data);
                            }
                        } catch (IOException e) {
                            Log.e(TAG,"readPacketData error: "+e.getMessage());
                        }
                    }
                    Log.d(TAG,"readThread closing...");
                }
            };
            readThreads.add(readThread);
            readThread.start();
        }
    }

    /**
     * Writes the raw data but also wraps it in the alignment byte, the length of the data, and checksum needed for reading
     * and error checking
     * @param data
     * @throws IOException
     */
    public void write(final byte[] data) {
        synchronized (writeThreadLock) {
            if (writeThread==null) {
                writeThread = Executors.newSingleThreadExecutor();
                Log.d(TAG, "writeThread created");
            }
        }
        poolCount.incrementAndGet();
        writeThread.execute(() -> {
            try {
                if (data == null)
                    throw new IOException("Cannot write as data is null");
                if (outStream == null)
                    throw new IOException("Cannot write as outStream is null");
                outStream.write(ALIGNMENT_BYTE_A);
                outStream.write(ALIGNMENT_BYTE_B);
                byte[] length = SdrUtils.intToByteArray(data.length);
                //outStream.write(ALIGNMENT_BYTE_C);
                //outStream.write(ALIGNMENT_BYTE_D);
                outStream.write(length);
                outStream.write(data);
                byte checksum = NetUtil.getChecksum(data);
                outStream.write(checksum);
                lastConnectOutbound = System.currentTimeMillis();
            } catch (IOException e) {
                Log.d(TAG, "writeThread error: "+e.getMessage());
                if (e != null) {
                    String message = e.getMessage();
                    if ((message != null) && message.contains("Broken")) {
                        keepGoing.set(false);
                        close();
                    }
                }
            } finally {
                if (poolCount.decrementAndGet() > POOL_WARNING_SIZE) {
                    Log.w(TAG,"Warning, BTSocket write queue is " + poolCount.get());
                    //TODO handle the pool surge as well
                }
            }
        });
    }

    /**
     * Reads until a specific byte is found; should be the first byte read, but this is implemented
     * to get the stream back in sync if noise is introduced
     * @throws IOException
     */
    private void readAlignmentByte() throws IOException {
        Log.d(TAG,"readAlignmentByte()");
        if (inStream == null)
            throw new IOException("Cannot readAlignmentByte(), inStream is null");
        try {
            int shift = 0;
            boolean found = false;
            while (!found) {
                while (inStream.read() != ALIGNMENT_BYTE_A) {
                    shift++;
                }
                found = inStream.read() == ALIGNMENT_BYTE_B;
            }
            if (shift > 0) {
                Log.w(TAG, "Alignment byte not found where expected; packet start shifted by " + shift + "b");
                sqANDRService.onPacketDropped();
            }
        } catch (Exception e) {
            keepGoing.set(false);
            close();
            throw new IOException("Error in readAlignmentByte(): "+e.getMessage());
        }
    }

    static void closeIOThreads() {
        synchronized (writeThreadLock) {
            if (writeThread != null) {
                try { writeThread.shutdown(); } catch(Exception e) {}
                writeThread = null;
            }
        }
        readThreads = null; //readThreads should be allowed to die naturally
        poolCount = null;
    }

    private boolean lastReportedIsActive = false;
    public boolean isActive() {
        boolean active = (inStream != null) && (outStream != null);
        if (active != lastReportedIsActive) {
            lastReportedIsActive = active;
            Log.d(TAG,"Socket is "+(active?"active":"not active"));
        }

        return active;
    }
}
