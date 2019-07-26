package org.sofwerx.sqan.rf;

import android.util.Log;

import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqandr.util.StringUtils;
import org.sofwerx.sqandr.util.WriteableInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public class SignalProcessor {
    private final static String TAG = "SqAN.Signal";
    private final static int DEFAULT_BUFFER_SIZE = 256;
    private final static long DEFAULT_TIMEOUT = 50l;
    private ByteBuffer processed;
    private SignalProcessingListener listener;
    private RawSignalListener rawListener;
    private WriteableInputStream incomingStream;
    private Thread readThread,writeThread;
    private long timeout;
    private long nextTimeout = Long.MAX_VALUE;
    private final static int CYCLES_BETWEEN_OVERFLOW_CHECKS = 100;
    private int nextOverflowCheck = CYCLES_BETWEEN_OVERFLOW_CHECKS;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);
    private int maxToShow = 4;
    private final static boolean DETAILED_IQ = false;

    /**
     * Creates a new signal processor that will intake IQ values and then output processed
     * bytes to the listener. The processor will wait until either 1) it fills its buffer
     * or 2) timeout (ms) after it first receives the next chunk of data.
     * @param bufferSize
     * @param timeout
     * @param listener
     */
    @Deprecated
    public SignalProcessor(int bufferSize, long timeout, SignalProcessingListener listener) {
        this.listener = listener;
        this.timeout = timeout;
        //TODO non-functional at this time
        /*processed = ByteBuffer.allocate(bufferSize);
        incomingStream = new WriteableInputStream();
        readThread = new Thread("SignalIn") {
            SignalConverter converter = new SignalConverter();
            byte[] byteValueI = new byte[2];
            byte[] byteValueQ = new byte[2];
            byte dataPt;
            boolean doWrite;
            int valueI;
            int valueQ;
            int maxToShow = 1000; //FIXME for testing
            @Override
            public void run() {
                Log.d(TAG,"SignalProcessor thread started");
                while (keepGoing.get()) {
                    if (processed.position() < processed.limit()) {
                        if (incomingStream == null)
                            break;
                        try {
                            if (incomingStream.read(byteValueI) != 2) //TODO - I and Q may be reversed in this stream; that shouldn't matter overall but just calling it out here
                                continue;
                            if (incomingStream.read(byteValueQ) != 2)
                                continue;
                            nextOverflowCheck--;
                            if (nextOverflowCheck == 0) {
                                if (incomingStream.isOverflowing() && (listener != null))
                                    listener.onSignalDataOverflow();
                                nextOverflowCheck = CYCLES_BETWEEN_OVERFLOW_CHECKS;
                            }

                            valueI = (byteValueI[0] << 8 | (byteValueI[1] & 0xFF))<<4;
                            valueQ = (byteValueQ[0] << 8 | (byteValueQ[1] & 0xFF))<<4;
                            if (rawListener != null)
                                rawListener.onIqValue(valueI,valueQ);
                            if (maxToShow != 0) { //FIXME for testing
                                maxToShow--;
                                Log.d(TAG, "I=" + valueI + ",Q=" + valueQ);
                            }
                            converter.onNewIQ(valueI,valueQ);
                            if (converter.hasByte()) {
                                doWrite = false;
                                dataPt = converter.popByte();
                                synchronized (processed) {
                                    processed.put(dataPt);
                                    if ((processed.position() == processed.limit()) && (writeThread != null))
                                        doWrite = true;
                                }
                                if (doWrite) {
                                    Log.d(TAG, "reading filled processed buffer");
                                    writeThread.interrupt();
                                }
                            }
                        } catch (IOException e) {
                            Log.e(TAG, "Unable to read from incoming stream: " + e.getMessage());
                        }
                    }
                }
            }
        };
        readThread.start();

        writeThread = new Thread("SignalOut") {
            byte[] output;
            @Override
            public void run() {
                Log.d(TAG,"writeThread started");
                while (keepGoing.get()) {
                    output = null;
                    synchronized (processed) {
                        if ((processed.position() == processed.limit()) || (System.currentTimeMillis() > nextTimeout)) {
                            if (processed.position() > 0) {
                                output = processed.array();
                                processed.clear();
                            }
                        }
                    }
                    if (output != null) {
                        if (listener == null)
                            Log.d(TAG, "Processed data dropped as no listener is available");
                        else {
                            Log.d(TAG,"Sending "+output.length+"b as processed output");
                            listener.onSignalDataExtracted(output);
                        }
                    }
                    try {
                        sleep(1000);
                    } catch (InterruptedException ignore) {
                    }
                }
            }
        };
        writeThread.start();*/
    }

    /**
     * Intakes IQ data
     * @param incoming
     */
    /*public void consumeIqData(byte[] incoming) {
        Log.d(TAG,"consumeIqData("+((incoming==null)?"null":(incoming.length+"b"))+")");
        if ((incomingStream != null) && (incoming != null))
            incomingStream.write(incoming);
    }*/

    //FIXME trying a synchronous approach to parsing all the incoming data
    private SignalConverter converter = new SignalConverter();
    private ByteBuffer out = ByteBuffer.allocate(16);
    private StringBuilder iiqoffsetTest = new StringBuilder();
    public void consumeIqData(byte[] incoming) {
        if (incoming == null)
            return;
        final int limit = out.limit()-1;
        int valueI,valueQ;
        if (DETAILED_IQ) {
            if (incoming.length < 100) {
                if (incoming.length < 10) {
                    if (incoming.length % 2 == 0) {
                        for (int i = 0; i < incoming.length - 1; i += 2) {
                            iiqoffsetTest.append(StringUtils.toStringRepresentation(incoming[i]) + " " + StringUtils.toStringRepresentation(incoming[i + 1]));
                            valueI = (incoming[i + 1] << 8 | (incoming[i] & 0xFF));
                            //valueQ = (incoming[i+3] << 8 | (incoming[i+2] & 0xFF));
                            iiqoffsetTest.append("[" + String.format("% 3d", incoming[i]) + "," + String.format("% 3d", incoming[i + 1]) + "] " + " I=" + String.format("% 6d", valueI));
                            Log.d(TAG, iiqoffsetTest.toString());
                            iiqoffsetTest = new StringBuilder();
                        }
                    } else
                        Log.d(TAG, "Consuming " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
                } else
                    Log.d(TAG, "Consuming " + incoming.length + "b: " + new String(incoming, StandardCharsets.UTF_8));
            } else
                Log.d(TAG, "Consuming " + incoming.length + "b");
            maxToShow--;
        } else {
            Log.d(TAG, "Consuming " + incoming.length + "b");
            if (incoming.length < 100)
                Log.d(TAG, "Consuming " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
            else
                Log.d(TAG, "Consuming " + incoming.length + "b");
        }

        int len = incoming.length-4;
        for (int i=0;i<len;i+=4) {
            //valueI = incoming[i] << 8 | (incoming[i+1] & 0xFF);
            //valueQ = incoming[i+2] << 8 | (incoming[i+3] & 0xFF);

            if (DETAILED_IQ)
                iiqoffsetTest.append(StringUtils.toStringRepresentation(incoming[i])+" ("+String.format ("%03d",incoming[i]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+1])+" ("+String.format ("%03d",incoming[i+1]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+2])+" ("+String.format ("%03d",incoming[i+2]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+3])+" ("+String.format ("%03d",incoming[i+3]&0xFF)+")");

            //valueI = (incoming[i] << 8 | (incoming[i+1] & 0xFF));
            //valueQ = (incoming[i+2] << 8 | (incoming[i+3] & 0xFF));

            //switching endianness
            valueI = (incoming[i+1] << 8 | (incoming[i] & 0xFF));
            valueQ = (incoming[i+3] << 8 | (incoming[i+2] & 0xFF));
            if (DETAILED_IQ)
                iiqoffsetTest.append(" I=" + String.format ("% 6d", valueI)+", Q=" + String.format ("% 6d", valueQ));

            converter.onNewIQ(valueI,valueQ);
            if (converter.hasByte()) {
                out.put(converter.popByte());
                if (out.position() == limit) {
                    byte[] outBytes = out.array();
                    Log.d(TAG,"From SDR: "+ StringUtils.toHex(outBytes));
                    if (listener != null)
                        listener.onSignalDataExtracted(outBytes);
                    out.clear();
                }
            }

            //valueI = incoming[i+2] << 8 | (incoming[i+1] & 0xFF);
            //valueQ = incoming[i] << 8 | (incoming[i+3] & 0xFF);
            //iiqoffsetTest.append(" 2: I=" + String.format ("% 6d", valueI)+", Q=" + String.format ("% 6d", valueQ));
            /*converter2.onNewIQ(valueI,valueQ);
            if (converter2.hasByte()) {
                out2.put(converter2.popByte());
                if (out2.position() == limit) {
                    byte[] outBytes = out2.array();
                    Log.d(TAG,"From SDR: (offset 2) "+ StringUtils.toHex(outBytes));
                    out2.clear();
                }
            }*/
            //valueI = incoming[i+3] << 8 | (incoming[i+2] & 0xFF);
            //valueQ = incoming[i+1] << 8 | (incoming[i] & 0xFF);
            //iiqoffsetTest.append(" 3: I=" + String.format ("% 6d", valueI)+", Q=" + String.format ("% 6d", valueQ));
            /*converter3.onNewIQ(valueI,valueQ);
            if (converter3.hasByte()) {
                out3.put(converter3.popByte());
                if (out3.position() == limit) {
                    byte[] outBytes = out3.array();
                    Log.d(TAG,"From SDR: (offset 3) "+ StringUtils.toHex(outBytes));
                    out3.clear();
                }
            }*/
            //valueI = incoming[i] << 8 | (incoming[i+3] & 0xFF);
            //valueQ = incoming[i+2] << 8 | (incoming[i+1] & 0xFF);
            //iiqoffsetTest.append(" 4: I=" + String.format ("% 6d", valueI)+", Q=" + String.format ("% 6d", valueQ));
            /*converter4.onNewIQ(valueI,valueQ);
            if (converter4.hasByte()) {
                out4.put(converter4.popByte());
                if (out4.position() == limit) {
                    byte[] outBytes = out4.array();
                    Log.d(TAG,"From SDR: (offset 4) "+ StringUtils.toHex(outBytes));
                    out4.clear();
                }
            }*/


            if (DETAILED_IQ) {
                Log.d(TAG, iiqoffsetTest.toString());
                iiqoffsetTest = new StringBuilder();
            }
        }
    }

    /**
     * Creates a new signal processor that will intake IQ values and then output processed
     * bytes to the listener. The processor will wait until either 1) it fills its buffer
     * or 2) timeout (ms) after it first receives the next chunk of data.
     * @param listener
     */
    public SignalProcessor(SignalProcessingListener listener) {
        this(DEFAULT_BUFFER_SIZE,DEFAULT_TIMEOUT,listener);
    }

    public void setListener(SignalProcessingListener listener) { this.listener = listener; }

    public void shutdown() {
        Log.d(TAG,"Shutting down SignalProcessor...");
        keepGoing.set(false);
        if (readThread != null) {
            readThread.interrupt();
            readThread = null;
        }
        if (writeThread != null) {
            writeThread.interrupt();
            writeThread = null;
        }
        if (incomingStream != null) {
            try {
                incomingStream.close();
            } catch (IOException e) {
                Log.w(TAG, "Unable to close incomingStream: " + e.getMessage());
            }
            incomingStream = null;
        }
    }

    /**
     * Sets the timeout (in ms) before returning whatever is in the buffer; otherwise, the buffer
     * will wait until full to return
     * @param timeout
     */
    public void setTimeout(long timeout) { this.timeout = timeout; }

    public void setRawListener(RawSignalListener rawListener) { this.rawListener = rawListener; }
}
