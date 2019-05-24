package org.sofwerx.sqan.manet.wifiaware.client;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.listeners.ManetListener;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.Challenge;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.manet.common.sockets.SocketChannelConfig;
import org.sofwerx.sqan.util.NetUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.security.NoSuchAlgorithmException;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.ShortBufferException;

public class AwareSocketTransceiver {
    private final static String TAG = Config.TAG+".AwareTxRx";
    private final static int MAX_PACKET_SIZE = 256000;
    //private ByteBuffer inputBuffer;
    private ClientState state;
    private static final int MAX_QUEUE_SLOTS = 10;
    private final PacketParser parser;
    private enum ClientState {
        READING_BODY, READING_CHALLENGE
    }

    protected AwareSocketTransceiver(PacketParser parser) {
        this.state = ClientState.READING_CHALLENGE;
        this.parser = parser;
    }

    private void immediateOutput(byte[] data, final OutputStream output) throws IOException {
        immediateOutput(data,false,output);
    }

    private void immediateOutput(byte[] data, boolean includeSize, final OutputStream output) throws IOException {
        if (data == null)
            return;
        if (includeSize)
            output.write(NetUtil.intToByteArray(data.length));
        output.write(data);
        ManetOps.addBytesToTransmittedTally(data.length);
    }

    /*public void closeAll() {
        try {
            if (socket != null)
                socket.close();
        } catch (IOException ignore) {
        }
    }*/

    public boolean isReadyToWrite() {
        return state != ClientState.READING_CHALLENGE;
    }

    public int queue(AbstractPacket packet, OutputStream outputStream, ManetListener listener) throws IOException {
        if (packet != null) {
            if (isReadyToWrite()) {
                immediateOutput(packet.toByteArray(), true, outputStream);
                if (listener != null)
                    listener.onTx(packet);
            } else
                Log.d(Config.TAG, "Packet sent for queuing but socket connection is not ready to write");
        }

        return MAX_QUEUE_SLOTS;
    }

    public void read(InputStream inputStream, OutputStream outputStream) throws IOException, NoSuchAlgorithmException, IllegalBlockSizeException, BadPaddingException, ShortBufferException {
        boolean keepGoing = true;
        //boolean firstTime = true;
        while (keepGoing) {
            if (state == ClientState.READING_CHALLENGE) {
                keepGoing = readChallenge(inputStream, outputStream);
                Log.d(Config.TAG,"SocketTransceiver.readChallenge complete, keepGoing "+keepGoing);
            } else
                keepGoing = parseMessage(inputStream);
            //firstTime = false;
        }
    }

    private boolean parseMessage(InputStream inputStream) throws IOException {
        if (inputStream == null)
            return false;
        byte[] preamble = new byte[4];
        int readBytes = inputStream.read(preamble);
        if (readBytes < 4)
            return false;
        int size = NetUtil.byteArrayToInt(preamble);
        if (size < 0)
            throw new IOException("Unable to processPacketAndNotifyManet a message with a negative size");
        else if (size > MAX_PACKET_SIZE)
            throw new IOException("Unable to processPacketAndNotifyManet a "+size+"b message, this must be an error");
        else
            Log.d(Config.TAG,"Received "+size+"b message");
        byte[] payload = new byte[size];
        inputStream.read(payload);
        parser.processPacketAndNotifyManet(payload);
        return false;
    }

    private boolean readChallenge(InputStream inputStream, OutputStream outputStream) throws IOException, NoSuchAlgorithmException {
        Log.d(TAG,"Reading challenge");
        boolean success = false;
        byte[] challengeBytes = new byte[Challenge.CHALLENGE_LENGTH];
        try {
            inputStream.read(challengeBytes);
        } catch (Exception e) {
            Log.e(TAG,"Unable to read challenge: "+e.getMessage());
        }

        try {
            byte[] response = Challenge.getResponse(null,null);
            Log.d(Config.TAG,"Writing challenge response");
            immediateOutput(response, outputStream);
            state = ClientState.READING_BODY;
            if (parser != null)
                parser.getManet().onAuthenticatedOnNet();
        } catch (UnsupportedEncodingException ignore) {
        }
        return success;
    }
}
