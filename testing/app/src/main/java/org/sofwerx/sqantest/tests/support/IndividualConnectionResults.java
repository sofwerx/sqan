package org.sofwerx.sqantest.tests.support;

import org.sofwerx.sqan.ipc.BftDevice;
import org.sofwerx.sqantest.SqAnTestService;
import org.sofwerx.sqantest.util.StringUtil;

import java.io.StringWriter;

public class IndividualConnectionResults {
    private int node = SqAnTestService.BROADCAST_ADDRESS;
    private long rxBytes = 0l;
    private int mangledPackets = 0;
    private int droppedPackets = 0;
    private int outOfOrderPackets = 0;
    private int goodPackets = 0;
    private BftDevice dev = null;

    public IndividualConnectionResults(int uuid) {
        this.node = uuid;
    }

    public int getNode() { return node; }

    public void setNode(int node) { this.node = node; }

    public long getRxBytes() { return rxBytes; }

    public int getMangledPackets() { return mangledPackets; }

    public int getGoodPackets() { return goodPackets; }

    public void incrementRxBytes(int size) {
        rxBytes += size;
    }

    public void incrementMangledPackets() {
        mangledPackets++;
    }

    public void incrementDroppedPackets() {
        droppedPackets++;
    }

    public void incrementOutOfOrderPackets() {
        outOfOrderPackets++;
    }

    public void incrementGoodPackets() {
        goodPackets++;
    }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();

        if (dev == null)
            dev = SqAnTestService.getDevice(node);
        out.append(((dev==null)||(dev.getCallsign()==null))?Integer.toString(node):dev.getCallsign());
        out.append(", Rx: ");
        out.append(StringUtil.toDataSize(rxBytes));
        if (droppedPackets > 0) {
            out.append(", ");
            out.append(Integer.toString(droppedPackets));
            out.append(" packets never arrived");
        }
        if (mangledPackets > 0) {
            out.append(", ");
            out.append(Integer.toString(mangledPackets));
            out.append(" corrupted packets");
        }
        if (outOfOrderPackets > 0) {
            out.append(", ");
            out.append(Integer.toString(outOfOrderPackets));
            out.append(" out of order packets");
        }

        int failures = getFailures();
        if ((failures > 0) || (goodPackets > 0)) {
            out.append("; successful delivery rate: ");
            out.append(Integer.toString(100*goodPackets/(goodPackets+failures)));
            out.append('%');
        }

        return out.toString();
    }

    public int getFailures() {
        return droppedPackets + mangledPackets + outOfOrderPackets;
    }
}
