package org.sofwerx.sqandr.sdr;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.util.WriteableInputStream;

import java.io.IOException;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class AbstractDataConnection {
    private final static String TAG = Config.TAG+".DataCon";
    private final static long TIME_BETWEEN_STALE_SEGMENTATION_CHECKS = 500l;
    protected DataConnectionListener listener;
    public abstract boolean isActive();
    public abstract void write(byte[] data);
    public abstract void burstPacket(byte[] data);
    public void setListener(DataConnectionListener listener) { this.listener = listener; }
    private AtomicBoolean keepGoing = new AtomicBoolean(true);

    private WriteableInputStream dataBuffer;
    private Thread readThread;
    private long nextStaleCheck = Long.MIN_VALUE;
    private ArrayList<Segmenter> segmenters;

    protected void handleRawDatalinkInput(final byte[] raw) {
        if (raw == null) {
            Log.w(TAG,"handleRawDatalinkInput received null input, ignoring");
            return;
        } else
            Log.w(TAG,"handleRawDatalinkInput received "+raw.length+"b raw input");
        if (dataBuffer == null)
            dataBuffer = new WriteableInputStream();
        dataBuffer.write(raw);
        if (readThread == null) {
            readThread = new Thread() {
                @Override
                public void run() {
                    while(keepGoing.get()) {
                        byte[] out = readPacketData();
                        if ((out != null) && (listener != null))
                            listener.onReceiveDataLinkData(out);
                        if (System.currentTimeMillis() > nextStaleCheck) {
                            if ((segmenters != null) && !segmenters.isEmpty()) {
                                int i=0;
                                while (i<segmenters.size()) {
                                    if ((segmenters.get(i) == null) || segmenters.get(i).isStale()) {
                                        Log.d(TAG,"Segment "+i+" stale, dropping");
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

    private int readPartialHeader() throws IOException {
        byte[] header = new byte[3];
        dataBuffer.read(header);
        int size;
        if (Segment.isQuickValidCheck(header)) {
            size = header[2] & 0xFF; //needed to convert signed byte into unsigned int
            return size;
        } else {
            if (listener != null)
                listener.onPacketDropped();
            int lost = 0;
            while (keepGoing.get()) {
                byte dig = (byte)dataBuffer.read();
                lost++;
                if (dig == Segment.HEADER_MARKER[0]) {
                    dig = (byte)dataBuffer.read();
                    lost++;
                    if (dig == Segment.HEADER_MARKER[1]) {
                        size = dataBuffer.read();
                        lost++;
                        if (size < Segment.MAX_LENGTH_BEFORE_SEGMENTING) {
                            Log.d(TAG,lost+"b lost, but new header found");
                            return dig;
                        }
                    }
                }
            }
            return -1;
        }
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
        Segmenter segmenter = findSegmenter(segment.getPacketId());
        if (segmenter == null) {
            Log.d(TAG,"First segment for new packet received");
            segmenter = new Segmenter(segment.getPacketId());
            segmenter.add(segment);
            if (segmenters == null)
                segmenters = new ArrayList<>();
            segmenters.add(segmenter);
        } else {
            segmenter.add(segment);
            if (segmenter.isComplete()) {
                Log.d(TAG,"Packet with "+segmenter.getSegmentCount()+" segments successfully reassembled");
                if (listener != null)
                    listener.onReceiveDataLinkData(segmenter.reassemble());
                segmenters.remove(segmenter);
            }
        }
    }

    private byte[] readPacketData() {
        if (dataBuffer == null)
            return null;
        try {
            int size = readPartialHeader();
            if ((size < 0) || (size > Segment.MAX_LENGTH_BEFORE_SEGMENTING))
                throw new IOException("Unable to read packet - invalid size "+size+"b - this condition should never happen unless the link is shutting down");
            byte[] rest = new byte[size+2]; //2 added to get the rest of the header
            dataBuffer.read(rest);
            Segment segment = new Segment();
            segment.parseRemainder(rest);
            if (segment.isValid()) {
                if (segment.isStandAlone())
                    return segment.getData();
                else
                    handleSegment(segment);
            } else {
                Log.d(TAG,"readPacketData produced invalid Segment; dropping");
                if (listener != null)
                    listener.onPacketDropped();
            }
        } catch (IOException e) {
            Log.e(TAG,"Unable to read packet: "+e.getMessage());
        }
        return null;
    }
}
