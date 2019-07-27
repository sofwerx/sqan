package org.sofwerx.sqan.rf;

import android.util.Log;

import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqandr.util.StringUtils;

/**
 * Holds the logic to convert raw IQ values into a byte
 */
public class SignalConverter {
    private final static int PERCENT_LAST = 5; //percent of last amplitude to consider as threshold
    private final static byte[] SQAN_HEADER = {(byte)0b01100110,(byte)0b10011001};
    private final static int LEAST_SIG_BIT_HEADER    = 0b00000000000000000000000000000001;
    private final static int MOST_SIG_BIT_HEADER     = 0b10000000000000000000000000000000;
    private final static int HEADER                  = 0b00000000000000000000101101010011; //this is the 12 bit header that signals coming data
    private final static int SHORT_HEADER            = 0b00000000000000000000000101010011; //this is the 9 bit header that signals coming data
    private final static int INVERSE_HEADER          = 0b00000000000000000000010010101100;
    private final static int INVERSE_SHORT_HEADER    = 0b00000000000000000000000010101100;
    private final static int HEADER_MASK             = 0b00000000000000000000111111111111;
    private final static int SHORT_HEADER_MASK       = 0b00000000000000000000000111111111;
    private final static byte MOST_SIG_BIT  = (byte)0b10000000;
    private final static byte LEAST_SIG_BIT = (byte)0b00000001;

    private final static String TAG = "SqAN.SigCon";
    private byte dataPt;
    private boolean dataPtIsReady = false;
    private int amplitude;
    private int amplitudeLast;
    private boolean isReadingHeader = true;
    private int tempHeader;
    private boolean bitOn;
    private byte tempByte = 0;
    private int bitIndex = 0;
    private boolean shortHeader = false;
    private boolean ignoreHeaders = false;
    private boolean waitingOnHeader = true;
    private int headerLength = 7;

    private StringBuilder out = new StringBuilder(); //FIXME temp for testing

    private boolean isSignalInverted;

    public void setShortHeader(boolean shortHeader) {
        this.shortHeader = shortHeader;
        if (shortHeader)
            headerLength = 7;
        else
            headerLength = 11;
    }


    StringBuilder outNewIQ = new StringBuilder(); //FIXME for testing

    public class IqResult {
        public boolean bitOn = false;
        public boolean headerFound = false;
        public IqResult(boolean bitOn, boolean headerFound) {
            this.bitOn = bitOn;
            this.headerFound = headerFound;
        }
        public IqResult() {}
    }

    /**
     * Intake a new IQ value into the converter
     * @param valueI
     * @param valueQ
     * @return
     */
    public IqResult onNewIQ(int valueI, int valueQ) {
        if (dataPtIsReady) {
            Log.w(TAG, "SignalConverter is attempting to consume another IQ value, but the last byte hasn't been read. Be sure to call hasByte() to see if a byte is ready then popByte() to remove the byte from the converter");
            return new IqResult(false,false);
        }

        //straight port of the current Pluto logic
        amplitude = valueI; // Real (I)

        IqResult iqResult = new IqResult();
        if (isReadingHeader) {
            boolean headerComplete = false;
            tempHeader = tempHeader << 1; //move bits over to make room for new bit

            if (amplitude >= amplitudeLast) {
                tempHeader = tempHeader | LEAST_SIG_BIT_HEADER;
                bitOn = true;
            } else {
                bitOn = false;
            }
            if (shortHeader)
                tempHeader = tempHeader & SHORT_HEADER_MASK;
            else
                tempHeader = tempHeader & HEADER_MASK;

            if ((tempHeader == HEADER) || ((tempHeader == SHORT_HEADER) && shortHeader)) {
                headerComplete = true;
                isSignalInverted = false;
            } else if ((tempHeader == INVERSE_HEADER) || ((tempHeader == INVERSE_SHORT_HEADER) && shortHeader)) {
                headerComplete = true;
                isSignalInverted = true;
            }

            if (headerComplete) {
                tempHeader = 0;
                isReadingHeader = false;
                bitIndex = 0;
                tempByte = 0;
                iqResult.headerFound = true;
            }
        } else {
            bitIndex++;
            tempByte = (byte)(tempByte << 1);
            if (isSignalInverted) {
                if (amplitude <= amplitudeLast){
                    tempByte = (byte)(tempByte | LEAST_SIG_BIT);
                    bitOn = true;
                } else
                    bitOn = false;
            } else {
                if (amplitude >= amplitudeLast){
                    tempByte = (byte)(tempByte | LEAST_SIG_BIT);
                    bitOn = true;
                } else
                    bitOn = false;
            }

            if (((bitIndex == 8) && !ignoreHeaders) || ((bitIndex == 20) && ignoreHeaders) || ((bitIndex == 17) && shortHeader && ignoreHeaders)) {
                dataPt = tempByte;
                dataPtIsReady = true;
            }
        }
        amplitudeLast = amplitude*PERCENT_LAST/100;
        iqResult.bitOn = bitOn;
        return iqResult;
    }

    /**
     * Does the convertor have a byte ready?
     * @return true ==  a byte has been assembled
     */
    public boolean hasByte() {
        return dataPtIsReady;
    }


    public byte popByte() {
        if (!dataPtIsReady)
            Log.w(TAG,"popByte called when no byte is actually ready. Be sure to call hasByte() to check if a byte is ready before popping the byte");
        dataPtIsReady = false;
        final byte outgoing = dataPt;
        dataPt = (byte)0;
        isReadingHeader = true;
        return outgoing;
    }
}
