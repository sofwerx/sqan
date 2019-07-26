package org.sofwerx.sqan.rf;

public interface SignalProcessingListener {
    void onSignalDataExtracted(byte[] data);

    /**
     * Data is being received faster than it is being processed
     */
    void onSignalDataOverflow();
}
