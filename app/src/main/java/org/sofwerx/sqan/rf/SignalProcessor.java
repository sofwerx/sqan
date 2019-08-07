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
    private final static boolean FORCE_DETAILED_IQ = false;
    private final static byte[] SQAN_HEADER = {(byte)0b01100110,(byte)0b10011001};
    private boolean leanMode = false; //in Lean mode, only the SqAN header is present and that is used to sync up reading the following bytes; there are no byte headers between individual bytes
    private boolean sqanHeaderFound = false;

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
            int maxToShow = 1000;
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
                            if (maxToShow != 0) {
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

    public boolean hasSqAnHeader() {
        if (converter == null)
            return false;
        return converter.hasSqanHeader();
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

    private int turnOnIqRemaining = 0;
    public void showIq(int additionalValues) { turnOnIqRemaining += additionalValues; } //show at least "additionalValues" more IQ values in logcat

    //public void turnOnDetailedIq() {
    //    turnOnIqRemaining = 1000;
    //    Log.d(TAG,"Detailed IQ reporting turned on");
    //}

    private int sqanHeaderIndex = 0;
    private SignalConverter converter;
    private ByteBuffer out = ByteBuffer.allocate(16);
    private StringBuilder iiqoffsetTest = new StringBuilder();
    public void consumeIqData(byte[] incoming) {
        if (incoming == null)
            return;
        final int limit = out.limit()-1;
        int valueI,valueQ;
        if (DETAILED_IQ) {
            if (incoming.length < 200) {
                if (incoming.length < 10) {
                    /*if (incoming.length % 2 == 0) {
                        for (int i = 0; i < incoming.length - 1; i += 2) {
                            iiqoffsetTest.append(StringUtils.toStringRepresentation(incoming[i]) + " " + StringUtils.toStringRepresentation(incoming[i + 1]));
                            valueI = (incoming[i + 1] << 8 | (incoming[i] & 0xFF));
                            //valueQ = (incoming[i+3] << 8 | (incoming[i+2] & 0xFF));
                            iiqoffsetTest.append("[" + String.format("% 3d", incoming[i]) + "," + String.format("% 3d", incoming[i + 1]) + "] " + " I=" + String.format("% 6d", valueI));
                            Log.d(TAG, iiqoffsetTest.toString());
                            iiqoffsetTest = new StringBuilder();
                        }
                    } else*/
                        Log.d(TAG, "Consuming " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
                } else
                    Log.d(TAG, "Consuming " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
            } else {
                if ((incoming.length % 1024) != 0) {
                    if (incoming.length < 200)
                        Log.d(TAG, "Consuming unexpected size " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
                    else
                        Log.d(TAG, "Consuming unexpected size " + incoming.length + "b");
                }
            }
            maxToShow--;
        } else {
            /*if (incoming.length < 100)
                Log.d(TAG, "Consuming " + incoming.length + "b: " + StringUtils.toHex(incoming) + ": " + new String(incoming, StandardCharsets.UTF_8));
            else
                Log.d(TAG, "Consuming " + incoming.length + "b");*/
        }

        int asterisksInARow = 0;
        int len = incoming.length-3;
        for (int i=0;i<len;i+=4) {
            //switching endianness
            valueI = (incoming[i+1] << 8 | (incoming[i] & 0xFF))<<4;
            valueQ = (incoming[i+3] << 8 | (incoming[i+2] & 0xFF))<<4;

            SignalConverter.IqResult iqResult = converter.onNewIQ(valueI,valueQ);
            /*if (iqResult.headerFound) {
                //Log.d(TAG,"header found");
                if (turnOnIqRemaining < 10) {
                    if (turnOnIqRemaining == 0)
                        Log.d(TAG, "Consuming size " + incoming.length + "b");
                    turnOnIqRemaining = 10;
                }
            }*/
            byte valueByte = 0;
            boolean showByteValue = false;
            if (leanMode) {
                if (converter.hasByte()) {
                    if (converter.hasSqanHeader() && !sqanHeaderFound) {
                        Log.d(TAG,"consumeIqData -> SqAN Header found");
                        sqanHeaderFound = true;
                        out.put(SignalConverter.SQAN_HEADER);
                    }
                    valueByte = converter.popByte();
                    showByteValue = true;
                    out.put(valueByte);
                    if (out.position() > limit) {
                        byte[] outBytes = out.array();
                        Log.d(TAG,"From SDR: "+ StringUtils.toHex(outBytes));
                        if (listener != null)
                            listener.onSignalDataExtracted(outBytes);
                        out.clear();
                    }
                }
            } else {
                if (converter.hasByte()) {
                    valueByte = converter.popByte();
                    showByteValue = true;
                    if (DETAILED_IQ) {
                        if (sqanHeaderIndex == 0) {
                            if (valueByte == SQAN_HEADER[0]) {
                                sqanHeaderIndex = 1;
                            }
                        } else if (sqanHeaderIndex == 1) {
                            if (valueByte == SQAN_HEADER[1]) {
                                sqanHeaderIndex = 2;
                                turnOnIqRemaining = 2000;
                                Log.d(TAG, "SqAN header found, beginning detailed IQ reporting");
                            } else {
                                sqanHeaderIndex = 0;
                            }
                        }
                    }
                    out.put(valueByte);
                    if (out.position() > limit) {
                        byte[] outBytes = out.array();
                        //Log.d(TAG,"From SDR: "+ StringUtils.toHex(outBytes));
                        if (listener != null)
                            listener.onSignalDataExtracted(outBytes);
                        out.clear();
                    }
                }
            }
            if (valueByte == (byte)42) {
                asterisksInARow++;
                if (asterisksInARow == 4) {
                    Log.d(TAG,"fwrite error signal detected from Pluto");
                    turnOnIqRemaining = 200;
                }
            } else
                asterisksInARow = 0;
            if (FORCE_DETAILED_IQ || (DETAILED_IQ && (turnOnIqRemaining > 0))) {
                //iiqoffsetTest.append(String.format ("%04d",i/4)+": "+StringUtils.toStringRepresentation(incoming[i])+" ("+String.format ("%03d",incoming[i]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+1])+" ("+String.format ("%03d",incoming[i+1]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+2])+" ("+String.format ("%03d",incoming[i+2]&0xFF)+") "+ StringUtils.toStringRepresentation(incoming[i+3])+" ("+String.format ("%03d",incoming[i+3]&0xFF)+")");
                //iiqoffsetTest.append(" I=" + String.format ("% 6d", valueI)+", Q=" + String.format ("% 6d", valueQ)+" Bit:"+(iqResult.bitOn?"1":"0"));
                //Log.d(TAG, iiqoffsetTest.toString());
                iiqoffsetTest.append((iqResult.bitOn?"1":"0"));
                if (iiqoffsetTest.length() > 100) {
                    Log.d(TAG, iiqoffsetTest.toString());
                    iiqoffsetTest = new StringBuilder();
                }
                turnOnIqRemaining--;
                if (turnOnIqRemaining == 0)
                    Log.d(TAG,"Reverting back to no IQ reporting");
                else if ((turnOnIqRemaining > 0) && showByteValue) {
                    StringBuilder tempO = new StringBuilder();
                    tempO.append("**** Byte: ");
                    tempO.append(StringUtils.toHex(valueByte));
                    tempO.append(" (");
                    tempO.append(StringUtils.toStringRepresentation(valueByte));
                    tempO.append(")");
                    Log.d(TAG, tempO.toString());
                }
            }
        }
    }

    /**
     * Creates a new signal processor that will intake IQ values and then output processed
     * bytes to the listener. The processor will wait until either 1) it fills its buffer
     * or 2) timeout (ms) after it first receives the next chunk of data.
     * @param listener
     * @param leanMode true == only thr SqAN header is being provided; no byte headers are provided between individual bytes
     */
    public SignalProcessor(SignalProcessingListener listener, boolean leanMode) {
        this(DEFAULT_BUFFER_SIZE,DEFAULT_TIMEOUT,listener);
        this.leanMode = leanMode;
        converter = new SignalConverter(leanMode);
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
