package org.sofwerx.sqan.manet.bt;

import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.sockets.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.util.CommsLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

public class BtSocketHandler {
    private final static int MAX_ALLOWABLE_PACKET_BYTES = 1024*1024*20;
    private static final Map<Integer, BtSocketHandler> HANDLER_MAP = new ConcurrentHashMap<>();
    private static final AtomicInteger ID = new AtomicInteger(0);
    private final Integer id = ID.incrementAndGet();
    private static final long RESPONSE_TIMEOUT = 1000L * 5L;
    private static final int SINGLE_READ_MAX_PACKETS = 10;
    public static final Map<Integer, Long> START_TIME_MAP = new HashMap<>();
    private SqAnDevice device = null;
    private final PacketParser parser;
    private static BtSocketListener listener;
    private BluetoothSocket socket;
    private byte[] sizeBuffer = new byte[4];
    private ByteBuffer readBuffer;
    private final BlockingQueue<ByteBuffer> writeQueue = new LinkedBlockingQueue<>();
    private InputStream inputStream;
    private OutputStream outputStream;

    public BtSocketHandler(BluetoothSocket socket, PacketParser parser, BtSocketListener listener) {
        this.socket = socket;
        this.parser = parser;
        BtSocketHandler.listener = listener;
        if (socket == null)
            return;
        if (socket.getRemoteDevice() != null) {
            device = SqAnDevice.findByBtMac(socket.getRemoteDevice().getAddress());
            CommsLog.log(CommsLog.Entry.Category.STATUS, "Connection established with " + socket.getRemoteDevice().getName());
        }

        HANDLER_MAP.put(id, this);
        START_TIME_MAP.put(id, System.currentTimeMillis());
        try {
            inputStream = socket.getInputStream();
            outputStream = socket.getOutputStream();
        } catch (IOException e) {
            if (listener != null)
                listener.onConnectionError("Unable to connect to socket input or output stream: "+e.getMessage());
            closeSocket();
        }
    }

    public static void removeUnresponsiveConnections() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, Long>> i = START_TIME_MAP.entrySet().iterator();
        while (i.hasNext()) {
            Map.Entry<Integer, Long> entry = i.next();
            if (now - entry.getValue() > RESPONSE_TIMEOUT) {
                BtSocketHandler h = HANDLER_MAP.get(entry.getKey());
                if (h == null)
                    Log.w(Config.TAG, "No such handler to kill: " + entry.getKey());
                else {
                    String warning = "Killing socket #" + entry.getKey() + " (no response)";
                    Log.w(Config.TAG, warning);
                    if (listener != null)
                        listener.onConnectionError(warning);
                    h.closeSocket();
                }
                i.remove();
            }
        }
        //if (listener != null)
        //    listener.onServerNumberOfConnections(HANDLER_MAP.size());
    }

    private void closeSocket() {
        try {
            try {
                if (inputStream != null) {
                    inputStream.close();
                    inputStream = null;
                }
            } catch (IOException ignore) {
            }
            try {
                if (outputStream != null) {
                    outputStream.close();
                    outputStream = null;
                }
            } catch (IOException ignore) {
            }
            if (socket != null) {
                socket.close();
                if (listener != null)
                    listener.onBtSocketDisconnected(device);
            }
        } catch (IOException ignore) {
        }
        HANDLER_MAP.remove(id);
    }

    public Integer getId() {
        return id;
    }

    private void queueReadBuffer() {
        ByteBuffer out = ByteBuffer.allocate(4 + readBuffer.limit());
        out.putInt(readBuffer.limit());
        out.put(readBuffer);
        out.flip();
        Log.d(Config.TAG, "#" + id + ": adding readBuffer to the outgoing queue");
        for (BtSocketHandler h : HANDLER_MAP.values()) {
            boolean send = !h.id.equals(this.id); // don't queue the incoming packet to myself
            if (send)
                h.writeQueue.add(out.duplicate());
        }
    }

    public static void addToWriteQue(ByteBuffer out, int address) {
        if (out != null) {
            for (BtSocketHandler h : HANDLER_MAP.values()) {
                boolean send = true;
                if (h.device != null)
                    send = AddressUtil.isApplicableAddress(h.device.getUUID(),address);
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
                //sizeBuffer.clear();
                //while (sizeBuffer.hasRemaining() && (inputStream.read(sizeBuffer) > 0)) {}
                int readBytes = inputStream.read(sizeBuffer);

                if (readBytes == 0) {
                    Log.d(Config.TAG, "#" + id + ": Nothing else to read for this socket");
                    return false;
                }

                int totalSize = ByteBuffer.wrap(sizeBuffer).getInt();
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
                        listener.onConnectionError(warning);
                    closeSocket();
                    return false;
                }
                readBuffer = ByteBuffer.allocate(totalSize);
            } else
                Log.d(Config.TAG,"readyBody(false)");

            byte[] tempData = new byte[1024];
            int bytesRead = 0;
            while ((bytesRead = inputStream.read(tempData, 0, tempData.length)) != -1) {
                readBuffer.put(tempData, 0, bytesRead);
            }

            if (readBuffer.hasRemaining()) {
                if (firstTime) {
                    String warning = "#" + id + ": Attempted to read "+readBuffer.capacity()+"b from body but nothing is available (closing)";
                    Log.w(Config.TAG, warning);
                    if (listener != null)
                        listener.onConnectionError(warning);
                    closeSocket();
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
                        listener.onConnectionError(warning);
                    closeSocket();
                    return false;
                }
                Log.d(Config.TAG, "#" + id + ": HEADER packet type: "+header.getType());
                if ((parser != null) && AddressUtil.isApplicableAddress(header.getDestination(), Config.getThisDevice().getUUID())) {
                    //this packet also applies to the server
                    readBuffer.position(0);
                    byte[] data = new byte[readBuffer.remaining()];
                    readBuffer.get(data);
                    parser.parse(data);
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
                    closeSocket(); //client requested termination
                    return false;
                }
                readBuffer = null;
                return false;
            }
        } catch (Exception e) {
            String reason = e.getMessage();
            String warning = "#" + id + " DROPPED PACKET: Error reading packet from connection #" + id + " (closing). " + ((reason==null)?"No reason provided":"Reason: "+reason);
            Log.w(Config.TAG, warning);
            if (listener != null)
                listener.onConnectionError(warning);
            closeSocket();
            return false;
        }
    }
}
