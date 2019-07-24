package org.sofwerx.sqan.rf;

import android.util.Log;

import org.sofwerx.sqandr.util.WriteableInputStream;

import java.io.IOException;
import java.nio.ByteBuffer;
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

    /**
     * Creates a new signal processor that will intake IQ values and then output processed
     * bytes to the listener. The processor will wait until either 1) it fills its buffer
     * or 2) timeout (ms) after it first receives the next chunk of data.
     * @param bufferSize
     * @param timeout
     * @param listener
     */
    public SignalProcessor(int bufferSize, long timeout, SignalProcessingListener listener) {
        this.listener = listener;
        this.timeout = timeout;
        processed = ByteBuffer.allocate(bufferSize);
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

                            valueI = byteValueI[0] << 8 | (byteValueI[1] & 0xFF);
                            valueQ = byteValueQ[0] << 8 | (byteValueQ[1] & 0xFF);
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
        writeThread.start();
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
    public void consumeIqData(byte[] incoming) {
        int valueI,valueQ;
        int maxToShow = 256;
        if (incoming == null)
            return;
        int len = incoming.length-3;
        for (int i=0;i<len;i+=4) {
            valueI = incoming[i] << 8 | (incoming[i+1] & 0xFF);
            valueQ = incoming[i+2] << 8 | (incoming[i+3] & 0xFF);
            if (maxToShow != 0) { //FIXME for testing
                maxToShow--;
                Log.d(TAG, "I=" + valueI + ",Q=" + valueQ);
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
