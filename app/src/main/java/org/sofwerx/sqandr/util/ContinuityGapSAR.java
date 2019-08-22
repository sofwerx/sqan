package org.sofwerx.sqandr.util;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.nio.BufferOverflowException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

/**
 * Restructures data so that periodic gaps cause by bit inversions can be detected and addressed
 */
public class ContinuityGapSAR {
    private final static String TAG = Config.TAG+".Cont";
    private final static int CHUNK_SIZE = 16;
    private final static int REQUIRED_MATCHING_DATA = CHUNK_SIZE / 4;
    private final static int CHUNK_SIZE_WITH_MARKER = CHUNK_SIZE + 1;
    private static ByteBuffer formatBuf;
    private ByteBuffer outBuf = ByteBuffer.allocate(1024);
    private ByteBuffer processBuf = ByteBuffer.allocate(1024);
    private final static byte[] MARKER_BYTES = {0b00000001,0b00000010};
    private final static byte PAD = 0b00000000;
    private boolean readSyncEstablished = false;

    public static void test() {
        ContinuityGapSAR gap = new ContinuityGapSAR();
        byte[] original = StringUtils.toByteArray("0100112233445566778899aabbccddeeff0100112233445566778899aabbccddeeff02ffeeddccbbaa9988776655443322110002ffeeddccbbaa99887766554433221100");
        byte[] proc = formatForOutput(original);
        Log.d(TAG,"Formatted: "+StringUtils.toHex(proc));
        proc = StringUtils.toByteArray("1122110100112233445566778899aabbccddeeff0100112233445566778899aabbccddeeff02ffeeddccbbaa9988776655443322110002ffeeddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        byte[] result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("0100112233445566778899aabbccddeefe112233445566778899aabbccddeeff02ffeeddccbbaa9988776655443322110002ffeeddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("0100112233445566778899aabbccddeeff0100112233445566778899aabbccddeeff02ffeeddccbbaa998877665544332211ddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("aabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabbaabbaabbaabb11221122aabbaabb11221122aabbaabb");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("0100112233445566778899aabbccddeefe112233445566778899aabbccddeeff02ffeeddccbbaa9988776655443322110002ffeeddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("0100112233445566778899aabbccddeeff0100112233445566778899aabbccddeeff02ffeeddccbbaa998877665544332211ddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));

        proc = StringUtils.toByteArray("0100112233445566778899aabbccddeeff0100112233445566778899aabbccddeeff02ffeeddccbbaa998877665544332211ddccbbaa99887766554433221100");
        Log.d(TAG,"Input: "+StringUtils.toHex(proc));
        result = gap.parse(proc);
        if (result == null)
            Log.d(TAG,"Parsed: NULL");
        else
            Log.d(TAG,"Parsed: "+StringUtils.toHex(result));
        Log.d(TAG,"Complete");
    }

    public void close() {
        formatBuf = null;
    }

    /**
     * Intake the data to be transmitted and format it to support later reassembly
     * @param data
     * @return
     */
    public static byte[] formatForOutput(byte[] data) {
        if (data == null)
            return null;
        int makerIndex = 0;
        if (formatBuf == null)
            formatBuf = ByteBuffer.allocate(1024);
        formatBuf.clear();
        int max = data.length / CHUNK_SIZE;
        if (data.length % CHUNK_SIZE > 0)
            max++;
        try {
            int chunkStart;
            for (int chunk = 0; chunk < max; chunk++) {
                for (int cycle=0;cycle<2;cycle++) {
                    chunkStart = chunk * CHUNK_SIZE;
                    formatBuf.put(MARKER_BYTES[makerIndex]);
                    for (int i = 0; i< CHUNK_SIZE; i++) {
                        if ((chunkStart+i)<data.length)
                            formatBuf.put(data[chunkStart+i]);
                        else
                            formatBuf.put(PAD);
                    }
                }
                makerIndex++;
                if (makerIndex == MARKER_BYTES.length)
                    makerIndex = 0;
            }
        } catch (BufferOverflowException e) {
            Log.w(TAG,"Only a portion of the inputted "+data.length+"b formatted as the rest exceeds the max size of the buffer - adjust the buffer size.");
        }
        byte[] out = new byte[formatBuf.position()];
        formatBuf.flip();
        formatBuf.get(out);
        return out;
    }

    /**
     * Intake data already formatted to address the continuity gap issue and reconstruct the
     * original data
     * @param gapData
     * @return
     */
    public byte[] parse(byte[] gapData) {
        if (gapData == null)
            return null;
        try {
            byte[] chunkA = new byte[CHUNK_SIZE];
            byte[] chunkB = new byte[CHUNK_SIZE];
            outBuf.clear();
            processBuf.put(gapData);
            processBuf.flip();
            byte firstMarker;
            byte nextMarker;
            final int limit = processBuf.limit() - CHUNK_SIZE - 1; //the size remaining needed to have a complete chunk of data
            int index = processBuf.position();
            while (index < limit)  {
                firstMarker = processBuf.get();
                index++;
                if (isMarkerByte(firstMarker)) {
                    nextMarker = processBuf.get(index + CHUNK_SIZE);
                    //Log.d(TAG,"First marker (pos = "+(index-1)+"): "+new String(StringUtils.toHex(firstMarker))+" next marker at pos "+(index + CHUNK_SIZE)+" = "+new String(StringUtils.toHex(nextMarker)));
                    if (isMarkerByte(nextMarker)) { //this is a good block of data
                        processBuf.get(chunkA);
                        //Log.d(TAG,"Recovered "+StringUtils.toHex(chunkA));
                        outBuf.put(chunkA); //saving the data to the output
                        readSyncEstablished = true;
                        if (nextMarker == firstMarker) { //this is the first of chunk of two chunks for the same data, so we skip over the second chunk
                            index = processBuf.position() + CHUNK_SIZE_WITH_MARKER;
                            if (index > processBuf.limit())
                                index = processBuf.limit();
                            processBuf.position(index);
                        } //otherwise, we leave the processBuf pointer at the header of the second chunk
                    } else {
                        if (readSyncEstablished) {
                            //there is some problem with the data in this area but read sync has
                            //already been established so we'll try to reconstruct the data
                            //Log.d(TAG,"1st true, 2nd false, readSync true");

                            //read sync is only used once after a failed header line-up; after
                            //that, we need to look for a sync again
                            readSyncEstablished = false;

                            //first thing that needs to happen is we need to find the header that
                            //occurs after these two chunks
                            final int minAcceptable = index + CHUNK_SIZE;
                            int endMarkerIndex = index + CHUNK_SIZE + CHUNK_SIZE_WITH_MARKER;
                            if (endMarkerIndex >= processBuf.limit()) { //only look to the buffer end
                                endMarkerIndex = processBuf.limit();
                            } else {
                                boolean keepSearching = true;
                                while (keepSearching && (endMarkerIndex > minAcceptable)) {
                                    nextMarker = processBuf.get(endMarkerIndex);
                                    if (isMarkerByte(nextMarker))
                                        keepSearching = false; //looks like we found the start of the next chunk
                                    else
                                        endMarkerIndex--;
                                }
                            }

                            if (endMarkerIndex > minAcceptable) { //the end was found or we are at the end of the buffer so we need to compare these two arrays and try to see what value should have been present
                                processBuf.get(chunkA);
                                int offset = endMarkerIndex - CHUNK_SIZE;
                                processBuf.position(offset);
                                processBuf.get(chunkB);
                                nextMarker = processBuf.get(endMarkerIndex - CHUNK_SIZE - 1);
                                if (nextMarker == firstMarker) { //chunkB occurs right after the correct marker header and contains the expected number of bytes so is likely valid
                                    //Log.d(TAG, "Recovered " + StringUtils.toHex(chunkB) + " by comparing " + StringUtils.toHex(chunkA) + " and " + StringUtils.toHex(chunkB) + " and just relying on the second chunk");
                                    outBuf.put(chunkB);
                                } else {
                                    byte[] fusedValue = getFusedValue(chunkA, chunkB);
                                    if (fusedValue != null) {
                                        outBuf.put(fusedValue);
                                        processBuf.position(endMarkerIndex);
                                    }
                                }
                            } else
                                processBuf.position(index + 1);
                        }
                    }
                }
                index = processBuf.position();
            }
        } catch (BufferOverflowException | BufferUnderflowException | IndexOutOfBoundsException e) {
            Log.e(TAG,"The "+gapData.length+"b formatted data could not be processed as it exceeds the max size of the buffer - adjust the buffer size and look to see if the buffer is draining properly. "+e.getClass().getSimpleName()+": "+e.getMessage());
        }
        if ((processBuf.limit() - processBuf.position()) > CHUNK_SIZE_WITH_MARKER) { //only carry over up to the size of one chunk
            //Log.d(TAG,"More data present (pos == "+processBuf.position()+", limit == "+processBuf.limit()+") than just a chunk, updating position");
            processBuf.position(processBuf.limit() - CHUNK_SIZE_WITH_MARKER);
        }
        processBuf.compact();
        if (outBuf.position() > 0) {
            byte[] out = new byte[outBuf.position()];
            outBuf.flip();
            outBuf.get(out);
            return out;
        } else {
            return null;
        }
    }

    /**
     * Compares A and B assuming that A and B were both supposed to be the same data, but somewhere
     * in the middle the transmission  was corrupted.
     * @param a
     * @param b
     * @return
     */
    private byte[] getFusedValue(byte[] a, byte[] b) {
        if ((a == null) || (b == null) || (a.length != b.length)) {
            Log.e(TAG,"getFusedValue called on two arrays that are not comparable.. This should never happen. Both must be non-null and the same length.");
            return null;
        }
        byte[] fused = new byte[a.length];
        for (int i=0;i<a.length;i++) {
            fused[i] = a[i];
        }
        //Log.d(TAG,"Trying to reconstruct the original value from "+StringUtils.toHex(a)+" and "+StringUtils.toHex(b)+" ...");
        int matchInRow = 0; //streak of matching characters
        int i=b.length-1;
        while (i > 0) {
            if (b[i] == a[i]) {
                matchInRow++;
            } else {
                if (matchInRow >= REQUIRED_MATCHING_DATA)
                    break;
                matchInRow = 0;
                fused[i] = b[i];
            }
            i--;
        }
        if (matchInRow < REQUIRED_MATCHING_DATA) { //no commonality found
            //Log.d(TAG,StringUtils.toHex(a)+" and "+StringUtils.toHex(b)+" only had "+matchInRow+" out of "+REQUIRED_MATCHING_DATA+" required common bytes in the middle so the two are not likely originating from the same data");
            return null;
        }
        //Log.d(TAG, "Recovered " + StringUtils.toHex(fused) + " by merging " + StringUtils.toHex(a) + " and " + StringUtils.toHex(b));
        return fused;
    }

    private boolean isMarkerByte(byte value) {
        return (value == MARKER_BYTES[0]) || (value == MARKER_BYTES[1]);
    }
}
