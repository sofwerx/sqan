package org.sofwerx.sqan.manet.common.sockets;

import java.util.Random;

/**
 * Placeholder
 */
public class Challenge {
    public static final int CHALLENGE_LENGTH = 1;

    /**
     * Placeholder
     * @param password
     * @param array
     * @return
     */
    public static byte[] getResponse(byte[] password, byte[] array) {
        byte[] expected = new byte[CHALLENGE_LENGTH];
        Random random = new Random();
        random.nextBytes(expected);
        return expected;
    }

    /**
     * Placeholder
     * @return
     */
    public static byte[] generateChallenge() {
        byte[] challenge = new byte[CHALLENGE_LENGTH];
        Random random = new Random();
        random.nextBytes(challenge);
        return challenge;
    }
}
