package org.sofwerx.sqan.manet.common.sockets.server;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.Challenge;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.util.CommsLog;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientHandler {
    private final static int MAX_ALLOWABLE_PACKET_BYTES = 1024*1024*20;
    private final static long BLACKLIST_DURATION = 1000l * 60l * 5l;
    public static final Map<InetAddress, Long> BLACKLIST_MAP = new HashMap<>();
    private static final Map<Integer, ClientHandler> HANDLER_MAP = new ConcurrentHashMap<>();
    private static final AtomicInteger ID = new AtomicInteger(0);
    private static final long RESPONSE_TIMEOUT = 1000L * 5L;
    private static final int SINGLE_READ_MAX_PACKETS = 10;
    public static final Map<Integer, Long> START_TIME_MAP = new HashMap<>();
    private static ServerStatusListener listener;
    private SqAnDevice clientDevice = null;

    private enum ReadState {
        INACTIVE, READING_PACKET, READING_PREAMBLE, READING_RESPONSE, WRITING_CHALLENGE
    }

    public static void removeUnresponsiveConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> i = START_TIME_MAP.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<Integer, Long> entry = i.next();
            if (now - entry.getValue() > RESPONSE_TIMEOUT) {
                ClientHandler h = HANDLER_MAP.get(entry.getKey());
                if (h == null)
                    Log.w(Config.TAG, "No such handler to kill: " + entry.getKey());
                else {
                    String warning = "Killing client #" + entry.getKey() + " (no response)";
                    Log.w(Config.TAG, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    h.closeClient();
                }
                i.remove();
            }
        }
        //if (listener != null)
        //    listener.onServerNumberOfConnections(HANDLER_MAP.size());
    }

    private ByteBuffer challengeBuffer;
    private final SocketChannel client;
    //private final int serverSqAnAddress;
    //private final int clientSqAnAddress;
    private final Integer id = ID.incrementAndGet();
    private final byte[] password = null;
    private ByteBuffer sizeBuffer = ByteBuffer.allocate(4); //just used to get the size
    private ByteBuffer readBuffer, writeBuffer;
    private ReadState readState = ReadState.INACTIVE;
    private final PacketParser parser;

    private final BlockingQueue<ByteBuffer> writeQueue = new LinkedBlockingQueue<>();

    public ClientHandler(SocketChannel client, PacketParser parser, ServerStatusListener listener) throws IOException, BlacklistException {
        this.parser = parser;
        this.client = client;
        ClientHandler.listener = listener;
        InetSocketAddress address = (InetSocketAddress) client.getRemoteAddress();
        InetSocketAddress myAddress = (InetSocketAddress) client.getLocalAddress();
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Connection established with client "+address.getAddress());
        Long blacklistTime = BLACKLIST_MAP.get(address.getAddress());
        if (blacklistTime != null) {
            if (System.currentTimeMillis() - blacklistTime < BLACKLIST_DURATION)
                throw new BlacklistException();
            BLACKLIST_MAP.remove(address.getAddress());
        }

        readState = ReadState.WRITING_CHALLENGE;

        HANDLER_MAP.put(id, this);
        START_TIME_MAP.put(id, System.currentTimeMillis());
    }

    public void closeClient() {
        try {
            if (client != null) {
                final SocketAddress socketAddress = client.getRemoteAddress();
                client.close();
                if ((listener != null) && (socketAddress != null) && (socketAddress instanceof InetSocketAddress))
                    listener.onServerClientDisconnected(((InetSocketAddress)socketAddress).getAddress());
            }
        } catch (IOException ignore) {
        }
        HANDLER_MAP.remove(id);
    }

    public Integer getId() {
        return id;
    }

    public boolean hasBacklog() {
        switch (readState) {
            case INACTIVE:
            case READING_PACKET:
            case READING_PREAMBLE:
            case READING_RESPONSE:
                return (writeBuffer != null) || (!writeQueue.isEmpty());
            case WRITING_CHALLENGE:
            default:
                return true;
        }
    }

    public boolean isClosed() {
        return !client.isOpen();
    }

    private void queueReadBuffer() {
        ByteBuffer out = ByteBuffer.allocate(4 + readBuffer.limit());
        out.putInt(readBuffer.limit());
        out.put(readBuffer);
        out.flip();
        Log.d(Config.TAG, "#" + id + ": adding readBuffer to the outgoing queue");
        for (ClientHandler h : HANDLER_MAP.values()) {
            boolean send = !h.id.equals(this.id); // don't queue the incoming packet to myself
            if (h.readState == ReadState.WRITING_CHALLENGE) {
                Log.e(Config.TAG, "#" + id + ": cannot queue incoming packet to #"+ h.id + "; state=" + h.readState);
                send = false;
            }
            if (send)
                h.writeQueue.add(out.duplicate());
        }
    }

    public static void addToWriteQue(ByteBuffer out, int address) {
        if (out != null) {
            for (ClientHandler h : HANDLER_MAP.values()) {
                boolean send = true;
                if (h.clientDevice != null)
                    send = AddressUtil.isApplicableAddress(h.clientDevice.getUUID(),address);
                if (h.readState == ReadState.WRITING_CHALLENGE) {
                    Log.e(Config.TAG, "Server cannot queue packet to #"+ h.id + "; state=" + h.readState);
                    send = false;
                }
                if (send) {
                    Log.d(Config.TAG,out.limit()+"b added to writeQueue for #"+h.id);
                    h.writeQueue.add(out.duplicate());
                    //TODO call read and write here possibly as a way to speed up the data burst from the server
                } else
                    Log.d(Config.TAG,"Outgoing packet does not apply to client #"+h.id);
            }
        }
    }

    private boolean readBody(boolean firstTime) {
        try {
            if (firstTime) {
                sizeBuffer.clear();
                while (sizeBuffer.hasRemaining() && (client.read(sizeBuffer) > 0)) {}

                if ((sizeBuffer.position() == 0)) {
                    if (firstTime) {
                        String warning = "#" + id + ": Empty header (closing)";
                        Log.w(Config.TAG, warning);
                        if (listener != null)
                            listener.onServerError(warning);
                        closeClient();
                    } else
                        Log.d(Config.TAG, "#" + id + ": Nothing else to read for this client");
                    return false;
                }

                sizeBuffer.rewind();

                int totalSize = sizeBuffer.getInt();
                Log.d(Config.TAG,"#" + id +": Total size in readBody(true) is "+totalSize);
                if ((totalSize < 4) || (totalSize > MAX_ALLOWABLE_PACKET_BYTES)) {
                    String size;
                    if (totalSize < 0)
                        size = "negative";
                    else if (totalSize < (1024*1024))
                        size = Integer.toString(totalSize);
                    else
                        size = Integer.toString(totalSize/(1024*1024))+"mb";
                    String warning = "Packet size is reporting to be "+size+", which is not valid (closing)";
                    Log.w(Config.TAG,warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    closeClient();
                    return false;
                }
                readBuffer = ByteBuffer.allocate(totalSize);
            } else
                Log.d(Config.TAG,"readyBody(false)");

            int pos = readBuffer.position();
            while (readBuffer.hasRemaining() && (client.read(readBuffer) > 0)) {}
            if (readBuffer.hasRemaining()) {
                if (firstTime && (readBuffer.position() == pos)) {
                    String warning = "#" + id + ": Attempted to read "+readBuffer.capacity()+"b from body but nothing is available (closing)";
                    Log.w(Config.TAG, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    closeClient();
                    return false;
                }
                return true;
            } else {
                Log.d(Config.TAG, "#" + id + ": PACKET received ("+readBuffer.position()+"b)");
                readBuffer.flip();

                //readBuffer.position(4);

                byte[] headerBytes = new byte[PacketHeader.getSize()];
                readBuffer.get(headerBytes);
                PacketHeader header = PacketHeader.newFromBytes(headerBytes);
                if (header == null) {
                    String warning = "#" + id + ": PacketHeader is null (closing)";
                    Log.w(Config.TAG, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    closeClient();
                    return false;
                }
                Log.d(Config.TAG, "#" + id + ": HEADER packet type: "+header.getType());
                if ((parser != null) && AddressUtil.isApplicableAddress(header.getDestination(), Config.getThisDevice().getUUID())) {
                    //this packet also applies to the server
                    readBuffer.position(0);
                    byte[] data = new byte[readBuffer.remaining()];
                    readBuffer.get(data);
                    parser.processPacketAndNotifyManet(data);
                }
                //Add one hop to the count of message routing then write the new header to the readBuffer
                readBuffer.position(0);
                header.incrementHopCount();
                headerBytes = header.toByteArray();
                readBuffer.put(headerBytes);
                readBuffer.position(0);
                if (header.getType() != PacketHeader.PACKET_TYPE_PING) //don't forward pings
                    queueReadBuffer();
                if (header.getType() == PacketHeader.PACKET_TYPE_DISCONNECTING) {
                    Log.i(Config.TAG, "#" + id + ": is terminating link (planned and reported)");
                    closeClient(); //client requested termination
                    return false;
                }
                readBuffer = null;
                readState = ReadState.INACTIVE;
                return false;
            }
        } catch (Exception e) {
            String reason = e.getMessage();
            String warning = "#" + id + " DROPPED PACKET: Error reading packet from client #" + id + " (closing). " + ((reason==null)?"No reason provided":"Reason: "+reason);
            Log.w(Config.TAG, warning);
            if (listener != null)
                listener.onServerError(warning);
            closeClient();
            return false;
        }
    }

    private boolean readResponse() throws BlacklistException {
        try {
            while (readBuffer.hasRemaining() && (client.read(readBuffer) > 0)) {}
            if (readBuffer.hasRemaining()) {
                return false; // nothing more to read
            }

            START_TIME_MAP.remove(this.id);
            InetSocketAddress address = (InetSocketAddress) client.getRemoteAddress();
            byte[] expected = Challenge.getResponse(password,challengeBuffer.array());
            readBuffer.flip();
            byte[] actual = new byte[Challenge.CHALLENGE_LENGTH];
            readBuffer.get(actual);
            //if (Arrays.equals(expected, actual)) {
                Log.i(Config.TAG, "#" + id + ": Client @" + address.getAddress()+ " passed challenge");
                readBuffer = null;
                challengeBuffer = null;
                readState = ReadState.INACTIVE;
                if (listener != null)
                    listener.onNewClient(clientDevice);
                return false;
            /*}

            Log.e(Config.TAG, "#" + id + ": Client @" + address.getAddress() + " failed challenge, response buffer=" + readBuffer+ " - closing");
            Log.e(Config.TAG, "#" + id + ": Expected: " + bufferToString(expected));
            Log.e(Config.TAG, "#" + id + ": Input: " + bufferToString(readBuffer.array()));

            BLACKLIST_MAP.put(address.getAddress(), System.currentTimeMillis());
            if (listener != null)
                listener.onServerBacklistClient(address.getAddress());
            closeClient();
            throw new BlacklistException();
        } catch (BlacklistException ex) {
            throw ex;*/
        } catch (Throwable ex) {
            String warning = "#" + id + ": Error reading packet from client #" + id+ " (closing)";
            Log.e(Config.TAG, warning, ex);
            if (listener != null)
                listener.onServerError(warning);
            closeClient();
            return false;
        }
    }

    public void readyToRead() throws BlacklistException {
        boolean keepGoing = true;
        int cycleCount = 0; // limit possible floods
        while (keepGoing && (cycleCount < SINGLE_READ_MAX_PACKETS)) {
            switch (readState) {
                case INACTIVE:
                    Log.d(Config.TAG,"readyToRead().INACTIVE");
                    if (readBuffer != null)
                        readBuffer.clear();
                    //MUST be before readBody!
                    readState = ReadState.READING_PACKET;
                    keepGoing = readBody(true);
                    break;
                case READING_PACKET:
                    Log.d(Config.TAG,"readyToRead().READING_PACKET");
                    keepGoing = readBody(false);
                    break;
                case READING_RESPONSE:
                    keepGoing = readResponse();
                    break;
                case WRITING_CHALLENGE:
                    keepGoing = false;
                    break;
                default:
                    closeClient();
                    keepGoing = false;
                    break;
            }
            ++cycleCount;
        }
    }

    public void readyToWrite() {
        if (readState == ReadState.WRITING_CHALLENGE) {
            try {
                writeChallenge();
            } catch (Throwable e) {
                String warning = "#" + id+ ": Error writing challenge from client #" + id+ " (closing)";
                Log.e(Config.TAG, warning, e);
                if (listener != null)
                    listener.onServerError(warning);
                closeClient();
            }
            return;
        }
        if (readState == ReadState.READING_RESPONSE) {
            return; // no writes until we pass the challenge
        }
        Log.d(Config.TAG,"ClientHandler ready to write");
        while (true) { // write as many packets as possible
            if (writeBuffer == null) {
                writeBuffer = writeQueue.poll();
                if (writeBuffer == null) {
                    Log.d(Config.TAG, "ClientHandler writeBuffer null");
                    break;
                } else
                    Log.d(Config.TAG, "ClientHandler writeQueue size "+writeQueue.size());
            }
            try {
                writeBuffer.rewind();
                Log.d(Config.TAG, "ClientHandler WRITING buffer of size "+writeBuffer.limit()+"b, pos "+writeBuffer.position());
                while (writeBuffer.hasRemaining() && (client.write(writeBuffer) > 0)) {
                }
                if (writeBuffer.hasRemaining()) {
                    break; // nothing more to do
                }
                writeBuffer = null;
                // and loop around to grab the next buffer
            } catch (Throwable t) {
                String warning = "#" + id + ": Error writing packet from client #"+ id + " (closing)";
                Log.e(Config.TAG, warning, t);
                if (listener != null)
                    listener.onServerError(warning);
                closeClient();
                break;
            }
        }
    }

    public void trimQueue(int connectionCount) {
        int limit = 100 * connectionCount;
        if (writeQueue.size() > limit) {
            Log.w(Config.TAG, "#" + id + ": Pruning " + (writeQueue.size() - limit)+ " queued messages");
            while (writeQueue.size() > limit) {
                writeQueue.poll(); // remove the oldest
            }
        }
    }

    private void writeChallenge() throws IOException {
        Log.d(Config.TAG,"Client handler writing challenge");
        if (challengeBuffer == null) {
            byte[] challenge = Challenge.generateChallenge();
            challengeBuffer = ByteBuffer.allocate(challenge.length);
            challengeBuffer.put(challenge);
            challengeBuffer.flip();
            writeBuffer = challengeBuffer;
        }
        while (writeBuffer.hasRemaining() && (client.write(writeBuffer) > 0)) {}
        if (writeBuffer.hasRemaining()) {
            return; // no state change
        }
        writeBuffer = null;
        readBuffer = ByteBuffer.allocate(Challenge.CHALLENGE_LENGTH);
        readState = ReadState.READING_RESPONSE;
    }
}
