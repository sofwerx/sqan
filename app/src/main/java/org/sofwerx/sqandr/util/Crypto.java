package org.sofwerx.sqandr.util;

/**
 * This is a placeholder class for encryption. There is NO MEANINGFUL ENCRYPTION occurring in the
 * methods in this class; right now it does a simple XOR scheme primarily to help with signal
 * processing impacts for repeated 0 bits.
 */
public class Crypto {
    private final static byte XOR_PATTERN = 0b01010101;
    public static byte[] encrypt(byte[] plaintext) {
        if (plaintext == null)
            return null;
        byte[] ciphertext = new byte[plaintext.length];
        for (int i=0;i<plaintext.length;i++) {
            ciphertext[i] = (byte)(plaintext[i] ^ XOR_PATTERN);
        }
        return ciphertext;
    }

    public static byte[] decrypt(byte[] ciphertext) {
        if (ciphertext == null)
            return null;
        byte[] plaintext = new byte[ciphertext.length];
        for (int i=0;i<ciphertext.length;i++) {
            plaintext[i] = (byte)(ciphertext[i] ^ XOR_PATTERN);
        }
        return plaintext;
    }
}
