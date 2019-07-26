package org.sofwerx.sqandr.util;

import android.text.format.DateFormat;
import android.util.Log;

import org.sofwerx.sqan.Config;

public class StringUtils {
    private final static long SEC = 1000l;
    private final static long MIN = SEC * 60l;
    private final static long HOUR = MIN * 60l;
    private final static long DAY = HOUR * 24l;

    public final static String toDuration(long duration) {
        if (duration < 250l)
            return "immediately";
        if (duration < 2000l)
            return Long.toString(duration)+"ms";
        else if (duration < 2l * MIN)
            return Long.toString(duration/SEC)+"s";
        else if (duration < 2l * HOUR)
            return Long.toString(duration/MIN)+"m";
        else
            return Long.toString(duration/HOUR)+"h";
    }

    public final static String toDataSize(long dataSize) {
        if (dataSize < 0l)
            return "invalid size";
        if (dataSize > 0l) {
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" bytes";
            dataSize = dataSize / 1024l;
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" kB";
            dataSize = dataSize / 1024l;
            if (dataSize < 2048l)
                return Long.toString(dataSize)+" mB";
            dataSize = dataSize / 1024l;
            return Long.toString(dataSize)+" gB";
        }
        return "no bytes";
    }

    public static String getFilesafeTime(long time) {
        if (time < 0l)
            return "unknown";
        else
            return DateFormat.format("MM-dd HHmm", new java.util.Date(time)).toString();
    }

    public static String getFormattedJustTime(long time) {
        return DateFormat.format("HH:mm:ss", new java.util.Date(time)).toString();
    }

    public static String getFormattedTime(long time) {
        if (time < 0l)
            return "unknown";
        else {
            long timeDiff = System.currentTimeMillis() - time;
            String format;
            if ((timeDiff < 0) || (timeDiff > DAY))
                format = "MM-dd-yy";
            else {
                if (timeDiff > 18l*HOUR)
                    format = "MM-dd HH:mm";
                else
                    format = "HH:mm";
            }
            return DateFormat.format(format, new java.util.Date(time)).toString();
        }
    }

    private final static byte[] MASK_BYTES = {
            (byte)0b10000000,
            (byte)0b01000000,
            (byte)0b00100000,
            (byte)0b00010000,
            (byte)0b00001000,
            (byte)0b00000100,
            (byte)0b00000010,
            (byte)0b00000001
    };

    /**
     * Turns a byte into a string representation of that byte (i.e. with 8 characters)
     * @param input
     * @return
     */
    public static String toStringRepresentation(byte input) {
        StringBuilder sb = new StringBuilder();
        for (int i=0;i<8;i++) {
            if ((MASK_BYTES[i] & input) == MASK_BYTES[i])
                sb.append('1');
            else
                sb.append('0');
        }
        return sb.toString();
    }

    /**
     * Turns a string representation of a byte (i.e. with 8 characters) into that byte
     * @param string
     * @return
     */
    public static byte toByteFromStringRepresentation(String string) {
        if ((string == null) || (string.length() != 8)) {
            Log.e(Config.TAG,"String "+string+" not properly formatted to be read into bytes");
            return (byte)0;
        }
        int[] digits = new int[8];
        int valueInPlace;
        for (int i=0;i<8;i++) {
            try {
                valueInPlace = string.charAt(i);
                if ((valueInPlace != 0) && (valueInPlace != 1)) {
                    Log.e(Config.TAG,"String "+string+" not properly formatted to be read into bytes");
                    return (byte)0;
                }
                if (valueInPlace == 1)
                    digits[i] = MASK_BYTES[i];
                else
                    digits[i] = (byte)0;
            } catch (NumberFormatException e) {
                Log.e(Config.TAG,"String "+string+" not properly formatted to be read into bytes");
                return (byte)0;
            }
        }
        return (byte)(digits[0] | digits[1] |digits[2] |digits[3] |digits[4] |digits[5] |digits[6] |digits[7]);
    }

    private final static char[] HEX_ARRAY = "0123456789abcdef".toCharArray();
    private final static char[] HEX_ARRAY_UCASE = "0123456789ABCDEF".toCharArray();

    /**
     * Converts a byte string up to length "len" to an array of hex
     * @param bytes
     * @param len
     * @return
     */
    public static String toHex(byte[] bytes, int len) {
        if ((bytes == null) || (len < 1))
            return null;
        if (len > bytes.length)
            len = bytes.length;
        char[] hexChars = new char[len * 2];
        for ( int j = 0; j < len; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static char[] toHex(byte data) {
        int v = data & 0xFF;
        char[] hexChars = new char[2];
        hexChars[0] = HEX_ARRAY[v >>> 4];
        hexChars[1] = HEX_ARRAY[v & 0x0F];
        return hexChars;
    }

    /**
     * Converts a byte string up to length "len" to an array of hex preceeded by "\x"
     * @param bytes
     * @param len
     * @return
     */
    public static String toFormattedHex(byte[] bytes, int len) {
        if ((bytes == null) || (len < 1))
            return null;
        if (len > bytes.length)
            len = bytes.length;
        char[] hexChars = new char[len * 4];
        for ( int j = 0; j < len; j++ ) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 4] = '\\';
            hexChars[j * 4 + 1] = 'x';
            hexChars[j * 4 + 2] = HEX_ARRAY_UCASE[v >>> 4];
            hexChars[j * 4 + 3] = HEX_ARRAY_UCASE[v & 0x0F];
        }
        return new String(hexChars);
    }

    public static String toHex(byte[] bytes) {
        return toHex(bytes,bytes.length);
    }

    public static byte toByte(char a, char b) {
        return (byte) ((Character.digit(a, 16) << 4)
                + Character.digit(b, 16));
    }

    public static byte[] toByteArray(String s) {
        if (s == null)
            return null;
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                    + Character.digit(s.charAt(i+1), 16));
        }
        return data;
    }
}