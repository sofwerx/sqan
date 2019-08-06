package org.sofwerx.sqan.manet.common;

/**
 * Helper class for more compact storage of mac addresses
 */
public class MacAddress {
    public final static int MAC_BYTE_SIZE = 6;
    private byte[] bytes;

    public boolean isValid() {
        if ((bytes != null) && (bytes.length == MAC_BYTE_SIZE)) {
            for (int i = 0; i < MAC_BYTE_SIZE; i++) {
                if (bytes[i] != 0)
                    return true;
            }
        }
        return false;
    }

    public MacAddress() {}

    public MacAddress(String mac) {
        bytes = macToByteArray(mac);
    }

    public MacAddress(byte[] bytes) { this.bytes = bytes; }

    /**
     * Provides a byte array for this MacAddress and will always return a value
     * @return
     */
    public byte[] toByteArray() {
        if (isValid())
            return bytes;
        return new byte[] {0,0,0,0,0,0};
    }

    /**
     * Provides the String representation of this MAC address
     * @return MAC address or null if the address is not valid
     */
    public String toString() {
        if (isValid()) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (byte b : bytes) {
                if (first)
                    first = false;
                else
                    sb.append(':');
                sb.append(String.format("%02X", b));
            }
            return sb.toString();
        }
        return null;
    }

    /**
     * Converts a MAC address into a byte array
     * @param mac
     * @return
     */
    public static byte[] macToByteArray(String mac) {
        if (mac == null)
            return null;
        mac = mac.replace(" ","");
        String[] parts = mac.split(":");
        if (parts.length != MAC_BYTE_SIZE)
            return null;
        byte[] macAddressBytes = new byte[6];
        for(int i=0; i<6; i++){
            try {
                Integer hex = Integer.parseInt(parts[i], 16);
                macAddressBytes[i] = hex.byteValue();
            } catch (NumberFormatException e) {
                macAddressBytes[i] = 0;
            }
        }
        return macAddressBytes;
    }

    public static MacAddress build(String mac) {
        MacAddress address = new MacAddress(mac);
        if (address.isValid())
            return address;
        return null;
    }

    public boolean isEqual(MacAddress other) {
        if ((other == null) || (other.bytes == null) || (bytes == null) || (bytes.length != other.bytes.length))
            return false;
        for (int i=0;i<bytes.length;i++) {
            if (bytes[i] != other.bytes[i])
                return false;
        }
        return true;
    }
}
