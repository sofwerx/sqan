package org.sofwerx.sqantest.tests.support;

import org.sofwerx.sqantest.util.StringUtil;

import java.io.StringWriter;
import java.util.ArrayList;

public class TestProgress {
    private long startTime = System.currentTimeMillis();
    private ArrayList<TimeSlice> slices = new ArrayList<>();
    private TimeSlice currentSlice = null;

    public void add(TestPacket packet) {
        checkSlice();
        currentSlice.add(packet);
    }

    public void add(TestException exception) {
        checkSlice();
        currentSlice.add(exception);
    }

    public void add(int origin, int size) {
        checkSlice();
        currentSlice.add(origin, size);
    }

    public void addTxBytes(int size) {
        checkSlice();
        currentSlice.incrementTxBytes(size);
    }

    private void checkSlice() {
        if ((currentSlice == null) || !currentSlice.isSliceCurrent()) {
            currentSlice = new TimeSlice();
            slices.add(currentSlice);
        }
    }

    public String getShortStatus(boolean includeTime) {
        checkSlice();
        int successRate = currentSlice.getPercentSuccess();
        int rxBps = currentSlice.getRxBytesPerSecond();
        int txBps = currentSlice.getTxBytesPerSecond();
        int connections = currentSlice.getDeviceCount();
        if (currentSlice.isBarelyUsed()) {
            TimeSlice previous = null;
            if (slices.size() > 1)
                previous = slices.get(slices.size()-2);
            if (previous != null) {
                successRate = (successRate + previous.getPercentSuccess())/2;
                rxBps = (rxBps + previous.getRxBytesPerSecond())/2;
                txBps = (txBps + previous.getTxBytesPerSecond())/2;
                connections = previous.getDeviceCount();
            }
        }
        StringWriter out = new StringWriter();

        if (includeTime) {
            long duration = System.currentTimeMillis() - startTime;
            if (duration > 1000l * 120l) {
                out.append(StringUtil.toDuration(duration));
                out.append(", ");
            }
        }
        out.append("Tx ");
        out.append(Integer.toString(txBps));
        out.append("bps, Rx ");
        out.append(Integer.toString(rxBps));
        out.append("bps (");
        out.append(Integer.toString(successRate));
        out.append("% success) with ");
        out.append(Integer.toString(connections));
        out.append(" connection");
        if (connections != 1)
            out.append('s');

        return out.toString();
    }

    public String getFullStatus() {
        StringWriter out = new StringWriter();
        out.append("Test started at ");
        out.append(StringUtil.getFormattedTime(startTime));
        out.append(" (elapsed time ");
        out.append(StringUtil.toDuration(System.currentTimeMillis()-startTime));
        out.append(")\r\n\r\n");
        if (slices.isEmpty()) {
            out.append("No data collected");
            return out.toString();
        }
        out.append("** Performance by time **\r\n\r\n");
        for (TimeSlice slice:slices) {
            if (slice == null)
                continue;
            out.append(slice.getFullReport());
            out.append("\r\n");
        }
        return out.toString();
    }

    public long getStartTime() {
        return startTime;
    }
}
