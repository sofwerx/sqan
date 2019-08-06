package org.sofwerx.sqandr.serial;

@Deprecated
public interface SerialListener {
    void onSerialConnect();
    void onSerialError(Exception e);
    void onSerialRead(byte[] data);
}
