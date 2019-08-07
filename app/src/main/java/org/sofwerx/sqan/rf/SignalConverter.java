package org.sofwerx.sqan.rf;

import android.util.Log;

import org.sofwerx.sqandr.util.StringUtils;

/**
 * Holds the logic to convert raw IQ values into a byte
 */
public class SignalConverter {
    private final static int PERCENT_LAST = 5; //percent of last amplitude to consider as threshold
    private final static int MIN_SPACE_BETWEEN_HEADERS = 20;
    private final static int MAX_SPACE_BETWEEN_HEADERS = 1600;
    public final static byte[] SQAN_HEADER = {(byte)0b01100110,(byte)0b10011001};
    private final static int LEAST_SIG_BIT_HEADER    = 0b00000000000000000000000000000001;
    private final static int MOST_SIG_BIT_HEADER     = 0b10000000000000000000000000000000;
    private final static int HEADER                  = 0b00000000000000000000101101010011; //this is the 12 bit header that signals coming data
    private final static int SHORT_HEADER            = 0b00000000000000000000000101010011; //this is the 9 bit header that signals coming data
    private final static int INVERSE_HEADER          = 0b00000000000000000000010010101100;
    private final static int INVERSE_SHORT_HEADER    = 0b00000000000000000000000010101100;

    //private final static int SQAN_AS_HEADER          = 0b00000000000000000110011010011001;
    //private final static int INVERSE_SQAN_AS_HEADER  = 0b00000000000000001001100101100110;
    //private final static int SQAN_HEADER_MASK        = 0b00000000000000001111111111111111;
    private final static int SQAN_AS_HEADER          = 0b01100110100110010110011010011001;
    private final static int INVERSE_SQAN_AS_HEADER  = 0b10011001011001101001100101100110;
    private final static int SQAN_HEADER_MASK        = 0b11111111111111111111111111111111;

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
    private int tempSqanHeader = 0;
    private boolean bitOn;
    private byte tempByte = 0;
    private int bitIndex = 0;
    private boolean shortHeader = false;
    private boolean ignoreHeaders = false;
    private boolean waitingOnHeader = true;
    private int headerLength = 7;
    private boolean sqanHeaderOnly = false;
    private int distanceSinceLastSqanHeader = Integer.MAX_VALUE;
    private boolean sqanHeaderComplete = false;
    private boolean processAsByte = false;

    private boolean isSignalInverted;

    /**
     *
     * @param sqanHeaderOnly true == no individual byte headers are provided, but only the SqAN header serves as the actual header to sync the following bytes as well
     */
    public SignalConverter(boolean sqanHeaderOnly) {
        this.sqanHeaderOnly = sqanHeaderOnly;
    }

    public void setShortHeader(boolean shortHeader) {
        this.shortHeader = shortHeader;
        if (shortHeader)
            headerLength = 7;
        else
            headerLength = 11;
    }


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
     * Has the SqAN header been found in the data stream
     * @return
     */
    public boolean hasSqanHeader() {
        return sqanHeaderComplete;
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

        processAsByte = true;
        amplitude = valueI; // Real (I)
        IqResult iqResult = new IqResult();
        if (sqanHeaderOnly) {
            if (distanceSinceLastSqanHeader > MIN_SPACE_BETWEEN_HEADERS) {
                if (distanceSinceLastSqanHeader > MAX_SPACE_BETWEEN_HEADERS)
                    sqanHeaderComplete = false;
                tempSqanHeader = tempSqanHeader << 1;
                if (amplitude >= amplitudeLast) {
                    tempSqanHeader = tempSqanHeader | LEAST_SIG_BIT_HEADER;
                    bitOn = true;
                } else
                    bitOn = false;
                tempSqanHeader = tempSqanHeader & SQAN_HEADER_MASK;
                if (tempSqanHeader == SQAN_AS_HEADER) {
                    Log.d(TAG,"SqAN header found");
                    bitIndex = 0;
                    sqanHeaderComplete = true;
                    isSignalInverted = false;
                    processAsByte = false;
                } else if (tempSqanHeader == INVERSE_SQAN_AS_HEADER) {
                    Log.d(TAG,"SqAN header found, INVERSE");
                    bitIndex = 0;
                    sqanHeaderComplete = true;
                    isSignalInverted = true;
                    processAsByte = false;
                }
                if (sqanHeaderComplete) {
                    tempSqanHeader = 0;
                    bitIndex = 0;
                    tempByte = 0;
                    distanceSinceLastSqanHeader = 0;
                    iqResult.headerFound = true;
                } else
                    processAsByte = false;
            }
            if (sqanHeaderComplete) {
                //Log.d(TAG,"SqAN header complete");
            } else
                processAsByte = false;
        }
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
            processAsByte = false;
        }

        if (processAsByte) {
            bitIndex++;
            //Log.d(TAG,"processAsByte; sqanHeaderComplete: "+sqanHeaderComplete);
            tempByte = (byte) (tempByte << 1);
            if (isSignalInverted) {
                if (amplitude <= amplitudeLast) {
                    tempByte = (byte) (tempByte | LEAST_SIG_BIT);
                    bitOn = true;
                } else
                    bitOn = false;
            } else {
                if (amplitude >= amplitudeLast) {
                    tempByte = (byte) (tempByte | LEAST_SIG_BIT);
                    bitOn = true;
                } else
                    bitOn = false;
            }
            if (sqanHeaderOnly) {
                if (bitIndex == 8) {
                    dataPt = tempByte;
                    dataPtIsReady = true;
                    distanceSinceLastSqanHeader++;
                }
            } else {
                if (((bitIndex == 8) && !ignoreHeaders) || ((bitIndex == 20) && ignoreHeaders) || ((bitIndex == 17) && shortHeader && ignoreHeaders)) {
                    dataPt = tempByte;
                    dataPtIsReady = true;
                }
            }
        }
        amplitudeLast = amplitude * PERCENT_LAST / 100;
        iqResult.bitOn = bitOn;
        return iqResult;
    }

    /**
     * Does the converter have a byte ready?
     * @return true ==  a byte has been assembled
     */
    public boolean hasByte() {
        return dataPtIsReady;
    }


    StringBuilder tempByteOut = new StringBuilder(); //FIXME temp
    public byte popByte() {
        if (!dataPtIsReady)
            Log.w(TAG,"popByte called when no byte is actually ready. Be sure to call hasByte() to check if a byte is ready before popping the byte");
        dataPtIsReady = false;
        final byte outgoing = dataPt;
        tempByteOut.append(StringUtils.toHex(outgoing));
        if (tempByteOut.length() > 100) {
            Log.d(TAG, "Bytes: " + tempByteOut.toString());
            tempByteOut = new StringBuilder();
        }
        dataPt = (byte)0;
        isReadingHeader = true;
        return outgoing;
    }
}
