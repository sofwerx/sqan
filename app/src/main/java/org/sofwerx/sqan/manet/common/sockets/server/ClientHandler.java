package org.sofwerx.sqan.manet.common.sockets.server;

import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.Challenge;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class ClientHandler {
    private final static String TAG = Config.TAG+".ClntHndlr";
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
    private static final Object writeThreadLock = new Object();

    public static void clear() {
        BLACKLIST_MAP.clear();
        HANDLER_MAP.clear();
        START_TIME_MAP.clear();
        ID.set(0);
    }

    private enum ReadState {
        INACTIVE, READING_PACKET, READING_PREAMBLE, READING_RESPONSE, WRITING_CHALLENGE
    }

    public static int getActiveConnectionCount() {
        Set<Map.Entry<Integer, ClientHandler>> entries = HANDLER_MAP.entrySet();
        if (entries == null)
            return 0;
        return entries.size();
    }

    public static void removeUnresponsiveConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> i = START_TIME_MAP.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<Integer, Long> entry = i.next();
            if (now - entry.getValue() > RESPONSE_TIMEOUT) {
                ClientHandler h = HANDLER_MAP.get(entry.getKey());
                if (h == null)
                    Log.w(TAG, "No such handler to kill: " + entry.getKey());
                else {
                    String warning = "Killing client #" + entry.getKey() + " (no response)";
                    Log.w(TAG, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    h.closeClient();
                }
                i.remove();
            }
        }
    }

    private ByteBuffer challengeBuffer;
    private final SocketChannel client;
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
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Connection established with client "+address.getAddress());
        Long blacklistTime = BLACKLIST_MAP.get(address.getAddress());
        if (blacklistTime != null) {
            if (System.currentTimeMillis() - blacklistTime < BLACKLIST_DURATION)
                throw new BlacklistException();
            BLACKLIST_MAP.remove(address.getAddress());
        }

        readState = ReadState.WRITING_CHALLENGE;

        HANDLER_MAP.put(id, this);
        START_TIME_MAP.put(id, System.currentTimeMillis());
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"ClientHandler #"+id+" created");
    }

    public void closeClient() {
        Log.d(TAG,"Closing client #"+id);
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
        Log.d(TAG, "#" + id + ": adding readBuffer to the outgoing queue");
        for (ClientHandler h : HANDLER_MAP.values()) {
            boolean send = !h.id.equals(this.id); // don't queue the incoming packet to myself
            if (h.readState == ReadState.WRITING_CHALLENGE) {
                Log.e(TAG, "#" + id + ": cannot queue incoming packet to #"+ h.id + "; state=" + h.readState);
                send = false;
            }
            if (send)
                h.writeQueue.add(out.duplicate());
        }
    }

    /**
     * Add a message to the outgoing queue
     * @param out buffer to send
     * @param address address to send to
     * @return true == at least one recipient was found for this message
     */
    public static boolean addToWriteQue(ByteBuffer out, int address) {
        boolean sent = false;
        if (out != null) {
            for (ClientHandler h : HANDLER_MAP.values()) {
                boolean send = true;
                if (h.clientDevice != null)
                    send = AddressUtil.isApplicableAddress(h.clientDevice.getUUID(),address);
                if (h.readState == ReadState.WRITING_CHALLENGE) {
                    Log.e(TAG, "#" + h.id + ": Server cannot queue packet to client; state=" + h.readState);
                    send = false;
                }
                if (send) {
                    sent = true;
                    Log.d(TAG, "#" + h.id + ": " + out.limit()+"b added to writeQueue for client");
                    h.writeQueue.add(out.duplicate());

                    //TODO
                    h.readyToWrite();
                    //TODO
                    //TODO call read and write here possibly as a way to speed up the data burst from the server
                } else
                    Log.d(TAG, "#" + h.id +": Outgoing packet does not apply to client #");
            }
        }
        return sent;
    }

    private boolean readBody(boolean firstTime) {
        try {
            if (firstTime) {
                sizeBuffer.clear();
                while (sizeBuffer.hasRemaining() && (client.read(sizeBuffer) > 0)) {}

                if ((sizeBuffer.position() == 0)) {
                    if (firstTime) {
                        String warning = "#" + id + ": Empty header (closing)";
                        CommsLog.log(CommsLog.Entry.Category.PROBLEM, warning);
                        if (listener != null)
                            listener.onServerError(warning);
                        //TODO closeClient(); ignoring client problems
                    } else
                        Log.d(TAG, "#" + id + ": Nothing else to read for this client");
                    return false;
                }

                sizeBuffer.rewind();

                int totalSize = sizeBuffer.getInt();
                //Log.d(TAG, "#" + id + ": Total size in readBody(true) is "+totalSize);
                if ((totalSize < 4) || (totalSize > MAX_ALLOWABLE_PACKET_BYTES)) {
                    String size;
                    if (totalSize < 0)
                        size = "negative";
                    else
                        size = StringUtil.toDataSize(totalSize);
                    String warning = "Packet size is reporting to be "+size+", which is not valid";
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    //TODO instead of closing, scan the buffer for the next header marker (and add a header marker like is in the Bluetooth Manet)
                    closeClient();
                    return false;
                }
                readBuffer = ByteBuffer.allocate(totalSize);
            } else
                Log.d(TAG, "#" + id + " readyBody(false)");

            if (readBuffer == null) {
                Log.d(TAG,"readBuffer was unexpectedly null");
                return false;
            }

            int pos = readBuffer.position();
            while (readBuffer.hasRemaining() && (client.read(readBuffer) > 0)) {}
            if (readBuffer.hasRemaining()) {
                if (firstTime && (readBuffer.position() == pos)) {
                    String warning = "#" + id + ": Attempted to read "+readBuffer.capacity()+"b from body but nothing is available (closing)";
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    //TODO closeClient(); //ignoring client problems
                    return false;
                }
                return true;
            } else {
                Log.d(TAG, "#" + id + ": PACKET received ("+readBuffer.position()+"b)");
                readBuffer.flip();

                byte[] headerBytes = new byte[PacketHeader.getSize()];
                readBuffer.get(headerBytes);
                PacketHeader header = PacketHeader.newFromBytes(headerBytes);
                if (header == null) {
                    String warning = "#" + id + ": PacketHeader is null";
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, warning);
                    if (listener != null)
                        listener.onServerError(warning);
                    //TODO closeClient(); //ignoring failed headers
                    return false;
                }
                //Log.d(TAG, "#" + id + ": HEADER packet type: "+header.getType());

                if ((parser != null) && AddressUtil.isApplicableAddress(Config.getThisDevice().getUUID(),header.getDestination())) {
                    //this packet also applies to the server
                    readBuffer.position(0);
                    byte[] data = new byte[readBuffer.remaining()];
                    readBuffer.get(data);
                    parser.processPacketAndNotifyManet(data);
                }
                if ((clientDevice == null) && header.isDirectFromOrigin()) {
                    clientDevice = SqAnDevice.findByUUID(header.getOriginUUID()); //assign the device based on the origin
                    if (clientDevice != null)
                        Log.d(TAG,"Client Handler #"+id+" resolved to device "+clientDevice.getLabel());
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
                    Log.i(TAG, "#" + id + ": is terminating link (planned and reported)");
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
            Log.w(TAG, warning);
            if (listener != null)
                listener.onServerError(warning);
            //TODO closeClient(); //ignoring failed packet
            return false;
        }
    }

    private boolean readResponse() throws BlacklistException {
        try {
            Log.d(TAG,"Reading challenge response from client");
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
                Log.i(TAG, "#" + id + ": Client @" + address.getAddress()+ " passed challenge");
                readBuffer = null;
                challengeBuffer = null;
                readState = ReadState.INACTIVE;
                if (listener != null)
                    listener.onNewClient(clientDevice);
                return false;
            /*}

            Log.e(TAG, "#" + id + ": Client @" + address.getAddress() + " failed challenge, response buffer=" + readBuffer+ " - closing");
            Log.e(TAG, "#" + id + ": Expected: " + bufferToString(expected));
            Log.e(TAG, "#" + id + ": Input: " + bufferToString(readBuffer.array()));

            BLACKLIST_MAP.put(address.getAddress(), System.currentTimeMillis());
            if (listener != null)
                listener.onServerBlacklistClient(address.getAddress());
            closeClient();
            throw new BlacklistException();
        } catch (BlacklistException ex) {
            throw ex;*/
        //} catch (Throwable ex) {
        } catch (Throwable ex) {
            final String warning = "#" + id + ": Error reading packet from client";
            //Log.e(TAG, warning, ex);
            Log.e(TAG,warning);
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,warning);
            if (listener != null)
                listener.onServerError(warning);
            //TODO closeClient(); ignoring client problems
            return false;
        }
    }

    public void readyToRead() throws BlacklistException {
        boolean keepGoing = true;
        int cycleCount = 0; // limit possible floods
        while (keepGoing && (cycleCount < SINGLE_READ_MAX_PACKETS)) {
            switch (readState) {
                case INACTIVE:
                    Log.d(TAG, "#" + id + ": readyToRead().INACTIVE");
                    if (readBuffer != null)
                        readBuffer.clear();
                    //MUST be before readBody!
                    readState = ReadState.READING_PACKET;
                    keepGoing = readBody(true);
                    break;
                case READING_PACKET:
                    Log.d(TAG, "#" + id + ": readyToRead().READING_PACKET");
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
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Unknown read state, closing...");
                    keepGoing = false;
                    break;
            }
            ++cycleCount;
        }
    }

    public void readyToWrite() {
        synchronized (writeThreadLock) {
            if (readState == ReadState.WRITING_CHALLENGE) {
                try {
                    writeChallenge();
                } catch (Throwable e) {
                    String warning = "#" + id + ": Error writing challenge from client #" + id + " (closing)";
                    Log.e(TAG, warning, e);
                    if (listener != null)
                        listener.onServerError(warning);
                    closeClient();
                }
                return;
            }
            if (readState == ReadState.READING_RESPONSE) {
                return; // no writes until we pass the challenge
            }
            //Log.d(TAG, "#" + id + ": ClientHandler ready to write");
            while (true) { // write as many packets as possible
                if (writeBuffer == null) {
                    writeBuffer = writeQueue.poll();
                    if (writeBuffer == null) {
                        //Log.d(TAG, "#" + id + ": ClientHandler writeBuffer null");
                        break;
                    } else {
                        //Log.d(TAG, "#" + id + ": ClientHandler writeQueue size " + writeQueue.size());
                    }
                }
                try {
                    writeBuffer.rewind();
                    //Log.d(TAG, "#" + id + "ClientHandler WRITING buffer of size " + writeBuffer.limit() + "b, pos " + writeBuffer.position());
                    while (writeBuffer.hasRemaining() && (client.write(writeBuffer) > 0)) {}

                    if (writeBuffer.hasRemaining()) {
                        break; // nothing more to do
                    }
                    writeBuffer = null;
                    // and loop around to grab the next buffer
                    //} catch (Throwable t) {
                } catch (Exception e) {
                    String warning = "#" + id + ": Error writing packet from client #" + id + ": " + e.getMessage();
                    //TODO Log.e(TAG, warning, t);
                    Log.e(TAG, warning);
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM, warning);
                    writeBuffer = null; //TODO was closing the client but going to try to keep it open and work through the error
                    if (listener != null)
                        listener.onServerError(warning);
                    //TODO closeClient();
                    break;
                }
            }
        }
    }

    public void trimQueue(int connectionCount) {
        int limit = 100 * connectionCount;
        if (writeQueue.size() > limit) {
            Log.w(TAG, "#" + id + ": Pruning " + (writeQueue.size() - limit)+ " queued messages");
            while (writeQueue.size() > limit) {
                writeQueue.poll(); // remove the oldest
            }
        }
    }

    private void writeChallenge() throws IOException {
        Log.d(TAG, "#" + id + ": Client handler writing challenge");
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
