package org.sofwerx.sqandr.testing;

public interface TestListener {
    void onDataReassembled(byte[] payloadData);
    void onPacketDropped();
    void onReceivedSegment();

    void onSqandrStatus(SqandrStatus status, String message);
    void onPlutoStatus(PlutoStatus status, String message);
}
