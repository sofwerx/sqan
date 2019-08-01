package org.sofwerx.sqandr.sdr;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.testing.SqandrStatus;
import org.sofwerx.sqandr.testing.TestListener;
import org.sofwerx.sqandr.util.Crypto;
import org.sofwerx.sqandr.util.SdrUtils;
import org.sofwerx.sqandr.util.StringUtils;
import org.sofwerx.sqandr.util.WriteableInputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractDataConnection {
    private final static String TAG = Config.TAG+".DataCon";
    private final static long TIME_BETWEEN_STALE_SEGMENTATION_CHECKS = 500l;
    protected TestListener listener;
    public abstract boolean isActive();
    public abstract void write(byte[] data);
    public abstract void burstPacket(byte[] data);
    private AtomicBoolean keepGoing = new AtomicBoolean(true);
    private WriteableInputStream dataBuffer;
    private Thread readThread;
    private long nextStaleCheck = Long.MIN_VALUE;
    private ArrayList<Segmenter> segmenters;
    protected long sdrConnectionCongestedUntil = Long.MIN_VALUE;
    protected long lastSqandrHeartbeat = Long.MIN_VALUE;
    private int lastHeaderBufferIndex = 0; //used to rewind the buffer a bit when a header turns out to produce an invalid packet
    private final static long TIME_CONGESTION_IS_RECENT = 1000l * 5l; //time in ms to consider any congestion marker as recent

    private class PartialHeaderData {
        public PartialHeaderData(int size, boolean inverted) {
            this.size = size;
            this.inverted = inverted;
        }
        protected int size;
        protected boolean inverted;
    }

    protected void handleRawDatalinkInput(final byte[] raw) {
        if (raw == null) {
            //Log.w(TAG,"handleRawDatalinkInput received null input, ignoring");
            return;
        } else {
            //Log.w(TAG, "handleRawDatalinkInput received " + raw.length + "b raw input");
        }
        //Log.d(TAG,"handleRawDatalinkInput is processing "+raw.length+"b");
        if (dataBuffer == null)
            dataBuffer = new WriteableInputStream();
        dataBuffer.write(raw);
        //Log.d(TAG,raw.length+"b added to dataBuffer");
        if (readThread == null) {
            readThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG,"starting read thread");
                    while(keepGoing.get()) {
                        byte[] out = readPacketData();
                        lastSqandrHeartbeat = System.currentTimeMillis();
                        if (out != null) {
                            if (listener != null) {
                                listener.onSqandrStatus(SqandrStatus.RUNNING,null);
                                Log.d(TAG,"reassembled = "+StringUtils.toHex(out));
                                listener.onDataReassembled(out);
                            }
                        }
                        if (System.currentTimeMillis() > nextStaleCheck) {
                            if ((segmenters != null) && !segmenters.isEmpty()) {
                                int i=0;
                                while (i<segmenters.size()) {
                                    if ((segmenters.get(i) == null) || segmenters.get(i).isStale()) {
                                        Log.d(TAG,"Segmenter #"+i+" (Packet ID "+((int)segmenters.get(i).getPacketId())+") stale, dropping (Segments: "+segmenters.get(i).getParts()+")");
                                        segmenters.remove(i);
                                        if (listener != null)
                                            listener.onPacketDropped();
                                    } else
                                        i++;
                                }
                                nextStaleCheck = System.currentTimeMillis() + TIME_BETWEEN_STALE_SEGMENTATION_CHECKS;
                            } else
                                nextStaleCheck = System.currentTimeMillis() + TIME_BETWEEN_STALE_SEGMENTATION_CHECKS * 2;
                        }
                    }
                }
            };
            readThread.start();
        }
    }

    /**
     * Is the comms path to the SDR unable to keep up with the current outflow of data
     * @return true == congested
     */
    public boolean isSdrConnectionCongested() {
        return System.currentTimeMillis() < sdrConnectionCongestedUntil;
    }

    /**
     * Is the connection to the SDR congested or recently congested
     * @return true == the connection to the SDR was recently or is congested
     */
    public boolean isSdrConnectionRecentlyCongested() {
        return System.currentTimeMillis() < sdrConnectionCongestedUntil + TIME_CONGESTION_IS_RECENT;
    }

    public void close() {
        keepGoing.set(false);
        if (dataBuffer != null) {
            Log.d(TAG,"Closing data buffer");
            try {
                dataBuffer.close();
            } catch (IOException e) {
                Log.w(TAG,"Unable to close dataBuffer: "+e.getMessage());
            }
            dataBuffer = null;
        }
        if (readThread != null) {
            Log.d(TAG,"Stopping readThread");
            readThread.interrupt();
            readThread = null;
        }
    }

    private PartialHeaderData readPartialHeader() throws IOException {
        //Log.d(TAG,"readPartialHeader()");
        byte[] header = new byte[3];
        header[1] = (byte)0;
        header[2] = (byte)0;

        int size;
        int lost = 0;
        while (keepGoing.get()) {
            header[0] = header[1];
            header[1] = header[2];
            header[2] = (byte)dataBuffer.read();
            if (Segment.isQuickValidCheck(header)) {
                //Log.d(TAG,"readPartialHeader() validity test passed");
                size = header[2] & 0xFF; //needed to convert signed byte into unsigned int
                lastHeaderBufferIndex = dataBuffer.getReadPosition();
                return new PartialHeaderData(size,false);
            } else if (Segment.isQuickInversionValidCheck(header)) {
                Log.d(TAG,"readPartialHeader() validity test passed, but inverted");
                size = header[2] & 0xFF; //needed to convert signed byte into unsigned int and invert
                lastHeaderBufferIndex = dataBuffer.getReadPosition();
                return new PartialHeaderData(size,false);
            } else {
                lost++;
                if ((lost % 100) == 0) {
                    Log.d(TAG, "100b lost with no packet header found");
                    if (listener != null)
                        listener.onPacketDropped();
                }
            }
        }
        return new PartialHeaderData(-1,false);

        /*dataBuffer.read(header);
        int size;
        if (Segment.isQuickValidCheck(header)) {
            //Log.d(TAG,"readPartialHeader() validity test passed");
            size = header[2] & 0xFF; //needed to convert signed byte into unsigned int
            return new PartialHeaderData(size,false);
        } else if (Segment.isQuickInversionValidCheck(header)) {
            Log.d(TAG,"readPartialHeader() validity test passed, but inverted");
            size = ~header[2] & 0xFF; //needed to convert signed byte into unsigned int and invert
            return new PartialHeaderData(size,false);
        } else {
            Log.d(TAG,"readPartialHeader() validity test failed");
            if (listener != null)
                listener.onPacketDropped();
            int lost = 0;
            while (keepGoing.get()) {
                byte dig = (byte)dataBuffer.read();
                lost++;
                if ((lost % 100) == 0) {
                    Log.d(TAG, "100b lost as no packet header found");
                    if (listener != null)
                        listener.onPacketDropped();
                }
                if (dig == Segment.HEADER_MARKER[0]) {
                    dig = (byte)dataBuffer.read();
                    lost++;
                    if (dig == Segment.HEADER_MARKER[1]) {
                        size = dataBuffer.read() & 0xFF;
                        lost++;
                        if ((size <= Segment.MAX_LENGTH_BEFORE_SEGMENTING) && (size > 0)) {
                            Log.d(TAG,lost+"b lost, but new header found");
                            return new PartialHeaderData(size,false);
                        }
                    }
                } else if (dig == Segment.INVERSE_HEADER_MARKER[0]) {
                    dig = (byte)dataBuffer.read();
                    lost++;
                    if (dig == Segment.INVERSE_HEADER_MARKER[1]) {
                        size = ~dataBuffer.read() & 0xFF;
                        lost++;
                        if ((size <= Segment.MAX_LENGTH_BEFORE_SEGMENTING) && (size > 0)) {
                            Log.d(TAG,lost+"b lost, but new header found");
                            return new PartialHeaderData(size,true);
                        }
                    }
                }
            }
            return new PartialHeaderData(-1,false);
        }*/
    }

    private Segmenter findSegmenter(byte packetId) {
        if ((segmenters != null) && !segmenters.isEmpty()) {
            for (Segmenter segmenter:segmenters) {
                if (segmenter.getPacketId() == packetId)
                    return segmenter;
            }
        }
        return null;
    }

    private void handleSegment(Segment segment) {
        if (segment == null)
            return;
        byte packetId = segment.getPacketId();
        Segmenter segmenter = findSegmenter(packetId);
        if (segmenter == null) {
            Log.d(TAG,"First segment ("+segment.getIndex()+") for Packet ID "+packetId+" received");
            segmenter = new Segmenter(packetId);
            segmenter.add(segment);
            if (segmenters == null)
                segmenters = new ArrayList<>();
            segmenters.add(segmenter);
        } else {
            segmenter.add(segment);
            if (segmenter.isComplete()) {
                Log.d(TAG,"Packet with "+segmenter.getSegmentCount()+" segments successfully reassembled");
                if (listener != null)
                    listener.onDataReassembled(Crypto.decrypt(segmenter.reassemble()));
                segmenters.remove(segmenter);
            }
        }
    }

    private byte[] readPacketData() {
        //Log.d(TAG,"readPacketData()");
        if (dataBuffer == null) {
            Log.w(TAG,"dataBuffer is null, ignoring readPacketData()");
            return null;
        }
        try {
            PartialHeaderData headerData = readPartialHeader();
            if ((headerData.size < 0) || (headerData.size > Segment.MAX_LENGTH_BEFORE_SEGMENTING)) {
                dataBuffer.rewindReadPosition(lastHeaderBufferIndex);
                throw new IOException("Unable to read packet - invalid size " + headerData.size + "b - this condition should never happen unless the link is shutting down");
            }
            byte[] rest = new byte[headerData.size+2]; //2 added to get the rest of the header
            dataBuffer.read(rest,true);
            //Log.d(TAG,"Rest of packet read");
            if (headerData.inverted) {
                Log.d(TAG,"Packet header was inverted, inverting data...");
                rest = SdrUtils.invert(rest);
            }
            Segment segment = new Segment();
            segment.parseRemainder(rest);
            if (segment.isValid()) {
                if (listener != null)
                    listener.onReceivedSegment();
                if (segment.isStandAlone()) {
                    Log.d(TAG,"Standalone packet recovered ("+headerData.size+"b): "+ StringUtils.toHex(rest));
                    byte[] sabytes = segment.getData();
                    Log.d(TAG,"Standalone data: "+ StringUtils.toHex(sabytes));
                    return Crypto.decrypt(sabytes);
                } else
                    handleSegment(segment);
            } else {
                dataBuffer.rewindReadPosition(lastHeaderBufferIndex);
                Log.d(TAG,"readPacketData produced invalid Segment (Seg "+segment.getIndex()+", Packet ID "+segment.getPacketId()+") and was dropped");
                if (listener != null)
                    listener.onPacketDropped();
            }
        } catch (IOException e) {
            Log.e(TAG,"Unable to read packet: "+e.getMessage());
        }
        return null;
    }

    public void setListener(TestListener listener) { this.listener = listener; }
}
