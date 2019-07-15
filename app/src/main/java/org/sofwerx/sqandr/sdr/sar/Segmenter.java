package org.sofwerx.sqandr.sdr.sar;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates segmented packets for transport across the SDR.
 */
public class Segmenter {
    private final static String TAG = Config.TAG+".Seg";
    public final static int MAX_POSSIBLE_LENGTH = Segment.MAX_LENGTH_BEFORE_SEGMENTING * Segment.MAX_VALID_INDEX;
    private final static long TIME_TO_STALE_FRAGMENTS = 1000l * 5l; //after this time (ms), this fragment should be abandoned

    private ArrayList<Segment> segments;
    private final long staleTime = System.currentTimeMillis() + TIME_TO_STALE_FRAGMENTS;
    private byte packetId;
    private static AtomicInteger packetIdIndex = new AtomicInteger(0);

    /*public Segmenter() { this(getNextPacketId()); }*/

    public Segmenter(byte packetId) {
        this.packetId = packetId;
        init();
    }

    public boolean isSegmentPart(Segment segment) {
        if ((segment == null) || segment.isStandAlone() || !segment.isValid())
            return false;
        return segment.getPacketId() == packetId;
    }

    /**
     * Adds a segment to this Segmenter
     * @param segment
     */
    public void add(Segment segment) {
        if (!isSegmentPart(segment)) {
            Log.w(TAG,"Unable to add segment - is null or does not belong");
            return;
        }
        if (segments == null) {
            segments = new ArrayList<>();
            segments.add(segment);
            Log.d(TAG,"Added Seg "+segment.getIndex()+" to Packet ID "+packetId+" (first segment)");
        } else {
            Segment other = find(segment.getIndex());
            if (other == null) {
                Log.d(TAG,"Added Seg "+segment.getIndex()+" to Packet ID "+packetId);
                segments.add(segment);
            } else
                Log.d(TAG,"This segment already exists; dropping");
        }
    }

    public Segment find(int index) {
        if ((segments != null) && !segments.isEmpty()) {
            for (Segment segment : segments) {
                if (segment.getIndex() == index)
                    return segment;
            }
        }
        return null;
    }

    /**
     * Does this segmenter have all of it's segments
     * @return
     */
    public boolean isComplete() {
        if ((segments != null) && !segments.isEmpty()) {
            sortAscending();
            int index = -1;
            for (Segment segment:segments) {
                index++;
                if (segment.getIndex() == index) {
                    if (segment.isFinalSegment())
                        return true;
                } else
                    return false;
            }
        }
        return false;
    }

    private void sortAscending() {
        if ((segments == null) || (segments.size() < 2))
            return;
        boolean sorted = false;
        int i;
        while (!sorted) {
            i = 1;
            sorted = true;
            while (i < segments.size()){
                if (segments.get(i - 1).getIndex() > segments.get(i).getIndex()) {
                    Segment temp = segments.get(i - 1);
                    segments.set(i - 1, segments.get(i));
                    segments.set(i, temp);
                    sorted = false;
                }
                i++;
            }
        }
    }

    /**
     * Reassembles all of the segments into the original package
     * @return
     */
    public byte[] reassemble() {
        if ((segments == null) || segments.isEmpty())
            return null;
        int totalSize = 0;
        for (Segment segment:segments) {
            if (segment.getData() != null)
                totalSize += segment.getData().length;
        }
        ByteBuffer out = ByteBuffer.allocate(totalSize);
        for (Segment segment:segments) {
            out.put(segment.getData());
        }
        return out.array();
    }

    public static byte getNextPacketId() {
        int overallId = packetIdIndex.getAndIncrement();
        if (packetIdIndex.get() > Segment.MAX_UNIQUE_PACKET_ID)
            packetIdIndex.set(0);
        return (byte)(overallId << 5);
    }

    private void init() { }

    public boolean isStale() {
        return System.currentTimeMillis() > staleTime;
    }

    public ArrayList<Segment> getSegments() { return segments; }

    /**
     * Wraps the data in the required segmentation info
     * @param data
     * @return
     */
    public static ArrayList<Segment> wrapIntoSegments(byte[] data) {
        if (data == null)
            return null;
        ArrayList<Segment> segments = new ArrayList<>();
        if (data.length > MAX_POSSIBLE_LENGTH) {
            Log.w(TAG,"Unable to wrap data that is "+data.length+"b as this exceeds the max total of "+MAX_POSSIBLE_LENGTH+"b");
            return null;
        }
        int i=0;
        int len;
        int index = 0;
        byte packetId = getNextPacketId();
        ByteBuffer buf = ByteBuffer.wrap(data);
        byte[] chunk;
        Segment segment;
        while (i<data.length) {
            len = data.length - i;
            if (len > Segment.MAX_LENGTH_BEFORE_SEGMENTING)
                len = Segment.MAX_LENGTH_BEFORE_SEGMENTING;
            chunk = new byte[len];
            buf.get(chunk);
            i += len;
            segment = wrap(chunk);
            segment.setIndex(index);
            segment.setPacketId(packetId);
            if (i >= data.length)
                segment.setFinalSegment(true);
            segments.add(segment);
            index++;
            if (index > Segment.MAX_LENGTH_BEFORE_SEGMENTING) {
                Log.e(TAG, "WARNING, unable to segment packet - packet is larger than supported; this packet should be broken into smaller packets before being passed to the segmenter");
                break;
            }
        }
        return segments;
    }

    public static Segment wrap(byte[] data) {
        if (data == null)
            return null;
        if (data.length > Segment.MAX_LENGTH_BEFORE_SEGMENTING) {
            Log.e(TAG,data.length+"b packet is greater than the max acceptable "+Segment.MAX_LENGTH_BEFORE_SEGMENTING+"b, use wrapIntoSegments() instead");
            return null;
        }
        Segment segment = new Segment();
        segment.setData(data);
        return segment;
     }

    public int getSegmentCount() {
        if (segments == null)
            return 0;
        return segments.size();
    }

    public byte getPacketId() { return packetId; }

    public String getParts() {
        if ((segments == null) || segments.isEmpty())
            return "empty";
        else {
            StringBuilder out = new StringBuilder();
            boolean first = true;
            for (Segment seg:segments) {
                if (first)
                    first = false;
                else
                    out.append(',');
                out.append(seg.getIndex());
            }
            return out.toString();
        }
    }
}
