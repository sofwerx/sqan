package org.sofwerx.sqantest.tests.support;

import androidx.annotation.NonNull;

import org.sofwerx.sqantest.util.StringUtil;

import java.io.StringWriter;
import java.util.ArrayList;

public class TimeSlice {
    public final static long TIME_SLICE_SIZE = 1000l * 60l * 15l; //time in ms
    private int errorCount = 0;
    private ArrayList<IndividualConnectionResults> connections = new ArrayList<>();
    private long sliceStart = System.currentTimeMillis();
    private long txBytes = 0l;

    public void add(TestPacket packet) {
        if (packet != null) {
            IndividualConnectionResults dev = findDevice(packet.getOrigin());
            if (dev == null) {
                dev = new IndividualConnectionResults(packet.getOrigin());
                connections.add(dev);
            }
            dev.incrementGoodPackets();
            add(dev,(packet.getData()==null)?0:packet.getData().length);
        }
    }

    public long getStartTime() { return sliceStart; }

    public void add(TestException exception) {
        errorCount++;
    }

    public void add(int origin, int size) {
        IndividualConnectionResults dev = findDevice(origin);
        if (dev == null) {
            dev = new IndividualConnectionResults(origin);
            connections.add(dev);
        }
        add(dev,size);
    }

    public boolean isSliceCurrent() {
        return System.currentTimeMillis() < sliceStart + TIME_SLICE_SIZE;
    }

    /**
     * Is this slice so new that it probably doesn't paint an accurate picture of connectivity
     * @return
     */
    public boolean isBarelyUsed() {
        return System.currentTimeMillis() < sliceStart + TIME_SLICE_SIZE/2l;
    }

    private void add(@NonNull IndividualConnectionResults dev, int size) {
        dev.incrementRxBytes(size);
    }

    public void incrementTxBytes(int size) {
        txBytes += size;
    }

    public void incrementErrorCount() {
        errorCount++;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public int getDeviceCount() {
        return connections.size();
    }

    public int getPercentSuccess() {
        int failures = 0;
        int successes = 0;
        for (IndividualConnectionResults conn:connections) {
            failures += conn.getFailures();
            successes += conn.getGoodPackets();
        }
        if ((failures > 0) || (successes > 0))
            return 100*successes/(successes+failures);
        return 0;
    }

    public int getRxBytesPerSecond() {
        long bytes = 0;
        long elapsedTime = (System.currentTimeMillis() - sliceStart)/1000l; //in seconds
        for (IndividualConnectionResults conn:connections) {
            bytes += conn.getRxBytes();
        }
        if (elapsedTime > 0l)
            return (int)(bytes/elapsedTime);
        return 0;
    }

    public int getTxBytesPerSecond() {
        long elapsedTime = (System.currentTimeMillis() - sliceStart)/1000l; //in seconds
        if (elapsedTime > 0l)
            return (int)(txBytes/elapsedTime);
        return 0;
    }

    private IndividualConnectionResults findDevice(int origin) {
        for (IndividualConnectionResults connection:connections) {
            if (connection.getNode() == origin)
                return connection;
        }
        return null;
    }

    public String getFullReport() {
        StringWriter out = new StringWriter();
        out.append(StringUtil.getFormattedTime(sliceStart));
        out.append(" to ");
        if (System.currentTimeMillis() < sliceStart + TIME_SLICE_SIZE)
            out.append(StringUtil.getFormattedTime(System.currentTimeMillis()));
        else
            out.append(StringUtil.getFormattedTime(sliceStart+TimeSlice.TIME_SLICE_SIZE));
        out.append(": Tx ");

        int successRate = getPercentSuccess();
        int rxBps = getRxBytesPerSecond();
        int txBps = getTxBytesPerSecond();
        out.append(Integer.toString(txBps));
        out.append("bps, Rx ");
        out.append(Integer.toString(rxBps));
        out.append("bps (");
        out.append(Integer.toString(successRate));
        out.append("% success), ");
        out.append(Integer.toString(connections.size()));
        out.append(" connection");
        if (connections.size() != 1)
            out.append('s');
        out.append(" as follows:\r\n");
        for (IndividualConnectionResults connection:connections) {
            out.append(" • ");
            out.append(connection.toString());
            out.append("\r\n");
        }

        return out.toString();
    }
}
