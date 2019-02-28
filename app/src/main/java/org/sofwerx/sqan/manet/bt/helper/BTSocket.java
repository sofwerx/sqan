package org.sofwerx.sqan.manet.bt.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.sockets.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;

public class BTSocket {
    private final static long MIN_TIME_BEFORE_TESTING_STALE = 1000l * 10l;
    private final static long MAX_TIME_BEFORE_STALE = 1000l * 60l * 5l;
    private static final boolean READ_ONCE = true;
    private final static String TAG = Config.TAG+".BTSocket";
    private static AtomicInteger connectionCounter = new AtomicInteger(0);

    private BluetoothSocket socket;
    private InputStream inStream;
    private OutputStream outStream;
    private MacAddress mac;
    private SqAnDevice device;
    private Role thisDeviceEndpointRole;
    private ReadListener readListener;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);
    private int id = connectionCounter.incrementAndGet();
    private long lastConnect = Long.MIN_VALUE;

    public void setDeviceIfNull(SqAnDevice device) {
        if (this.device == null)
            this.device = device;
    }

    public static enum Role {SERVER,CLIENT};

    private static ExecutorService readThread;
    private static ExecutorService writeThread;

    private static final Object readThreadLock = new Object();
    private static final Object writeThreadLock = new Object();

    public BTSocket(BluetoothSocket socket, Role role, final ReadListener readListener) {
        this.thisDeviceEndpointRole = role;
        this.readListener = readListener;
        if (socket==null)
            throw new RuntimeException(getLogHeader()+" BluetoothSocket param cannot be null!");
        if (socket.getRemoteDevice() == null)
            Log.d(TAG,getLogHeader()+" added ("+((role==Role.SERVER)?"Server":"Client")+"), unexpected null MAC");
        else
            Log.d(TAG,getLogHeader()+" added ("+((role==Role.SERVER)?"Server":"Client")+") MAC:"+socket.getRemoteDevice().getAddress());        this.socket = socket;
        if (socket.getRemoteDevice() != null)
            mac = MacAddress.build(socket.getRemoteDevice().getAddress());
        device = SqAnDevice.findByBtMac(mac);
        if (device == null) {
            Config.SavedTeammate teammate = Config.getTeammateByBtMac(mac);
            if (teammate != null) {
                Log.d(TAG,getLogHeader()+" match to saved teammate info found");
                device = new SqAnDevice(teammate.getSqAnAddress(),teammate.getCallsign());
                device.setNetworkId(teammate.getNetID());
                device.setBluetoothMac(mac.toString());
                SqAnDevice.add(device);
            }
        } else {
            if (device.getCallsign() == null) {
                Config.SavedTeammate teammate = Config.getTeammateByBtMac(mac);
                if (teammate != null)
                    device.setCallsign(teammate.getCallsign());
            }
            Log.d(TAG, getLogHeader()+" match to existing device "+((device.getCallsign()==null)?"":"("+device.getCallsign()+") ")+"found");
        }
        Core.registerForCleanup(this);
        lastConnect = System.currentTimeMillis();
    }

    public void startConnections() {
        try {
            try {
                inStream = socket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, getLogHeader()+" Error at getInputStream: " + e);
                close();
                throw new IOException(getLogHeader()+" getInputStream");
            }

            try {
                outStream = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, getLogHeader()+" Error at getOutputStream: " + e);
                close();
                throw new IOException(getLogHeader()+" getOutputStream");
            }

            readAsyncPacket(mReadListener);
            //downlink = new DownlinkThread(getInputStream());
            //downlink.start();
        } catch (Exception e) {
            Log.e(TAG,getLogHeader()+" Unable to open streams: "+e.getMessage());
        }
    }

    private ReadListener mReadListener = new ReadListener() {
        @Override
        public void onSuccess(AbstractPacket packet) {
            if (readListener != null)
                readListener.onSuccess(packet);
            if (keepGoing.get())
                readAsyncPacket(this);
        }

        @Override
        public void onError(int totalNumBytes, IOException e) {
            if (readListener != null)
                readListener.onError(totalNumBytes, e);
        }
    };

    public int getBtSocketIdNum() {
        return id;
    }

    public boolean isApplicableToThisDevice(int destination) {
        if (device == null)
            device = SqAnDevice.findByBtMac(mac);
        if (device != null)
            return AddressUtil.isApplicableAddress(device.getUUID(), destination);
        return false;
    }

    /**
     * Is the source the same as the device that is connected to this socket (used to prevent
     * circular reporting)
     * @param source
     * @return true == source is the same as the device connected to this socket
     */
    public boolean isThisDeviceOrigin(int source) {
        if (device == null)
            device = SqAnDevice.findByBtMac(mac);
        if (device != null)
            return device.getUUID() == source;
        return false;
    }

    public SqAnDevice getDevice() {
        return device;
    }

    public MacAddress getMac() {
        return mac;
    }

    /**
     * Getter for the raw BluetoothSocket inner obj
     */
    public BluetoothSocket getBluetoothSocket() {
        return socket;
    }

    /**
     * Gets the role that this device plays on this socket (i.e. if this device is
     * the server, then the role on this socket is SERVER)
     * @return
     */
    public Role getRole() {
        return thisDeviceEndpointRole;
    }

    /**
     * Getter for the socket's InputStream.
     * Note that this method will open the InputStream if it has not yet
     * been initialized (requires autoOpenStreams==true)
     */
    /*public InputStream getInputStream() throws IOException {
        openStreamsIfNeeded();
        return inStream;
    }*/

    /**
     * Getter for the socket's OutputStream.
     * Note that this method will open the OutputStream if it has not yet
     * been initialized (requires autoOpenStreams==true)
     */
    /*public OutputStream getOutputStream() throws IOException {
        openStreamsIfNeeded();
        return outStream;
    }*/

    /*protected void openStreamsIfNeeded() throws IOException {
        if (inStream != null && outStream != null)
            return;
        try {
            inStream = socket.getInputStream();
        } catch (IOException e) {
            Log.e(TAG, getLogHeader()+" Error at getInputStream: " + e);
            close();
            throw new IOException("getInputStream");
        }

        try {
            outStream = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, getLogHeader()+" Error at getOutputStream: " + e);
            close();
            throw new IOException("getOutputStream");
        }
    }*/

    public void readAsyncPacket(final ReadListener readListener) {
        synchronized (readThreadLock) {
            if (readThread==null) {
                readThread = Executors.newWorkStealingPool(Core.MAX_NUM_CONNECTIONS);
                Log.d(TAG, getLogHeader()+" readThread created");
            }
        }
        Log.d(TAG,getLogHeader()+" readAsyncPacket() called");
        readThread.execute(() -> {
            Log.d(TAG,getLogHeader()+" readAsyncPacket() readThread.execute() called");
            int totalNumBytes = 0;
            while(keepGoing.get()) {
                Log.d(TAG,getLogHeader()+" readAsyncPacket() readThread.execute() while(keepGoing) loop top");
                try {
                    byte[] data = readPacketData();
                    if (data == null)
                        Log.e(TAG,getLogHeader()+" readPacketData produced null data");
                    else {
                        Log.d(TAG, getLogHeader()+" readPacketData returned "+data.length+"b");
                        lastConnect = System.currentTimeMillis();
                        final AbstractPacket packet = AbstractPacket.newFromBytes(data);
                        if (packet == null) {
                            if (readListener != null)
                                readListener.onError(data.length,new IOException("Unable to processPacketAndNotifyManet Packet"));
                        } else {
                            SqAnDevice device = PacketParser.process(packet);
                            if (device != null)
                                setDeviceIfNull(device);
                            if (mReadListener != null)
                                mReadListener.onSuccess(packet);
                            if (thisDeviceEndpointRole == Role.SERVER) {
                                Log.d(TAG,getLogHeader()+" relaying packet to BT clients connected to this device");
                                packet.incrementHopCount();
                                data = packet.toByteArray();
                                Core.send(data,packet.getSqAnDestination(),packet.getOrigin());
                            }
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, getLogHeader()+" read error: " + e);
                    if (readListener != null)
                        readListener.onError(totalNumBytes, e);
                    return;
                }
            }
        });
    }

    /**
     * Read a single byte from stream (blocking)
     */
    public int read() throws IOException {
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot read as inStream is null");
        return inStream.read(); // read a single byte
    }

    public boolean isActive() {
        if ((socket != null) && socket.isConnected())
            return (inStream != null) && (outStream != null);
        return false;
    }

    /**
     * Read an Int from stream (blocking)
     * @return int
     * @throws IOException
     */
    private int readInt() throws IOException {
        Log.d(TAG,getLogHeader()+" readInt()");
        byte[] byteInt = new byte[4];
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot readInt(), inStream is null");
        try {
            inStream.read(byteInt);
        } catch (Exception e) {
            throw new IOException(getLogHeader()+" Error in readInt(): "+e.getMessage());
        }
        Log.d(TAG,getLogHeader()+" readInt() received data");
        return ByteBuffer.wrap(byteInt).getInt();
    }

    /**
     * Read a packet of raw byte array data from stream (blocking)
     * @return
     * @throws IOException
     */
    private byte[] readPacketData() throws IOException {
        Log.d(TAG,getLogHeader()+" readPacketData()");
        int size = readInt();
        if (size > 0) {
            byte[] data = new byte[size];
            Log.d(TAG,getLogHeader()+" readPacketData() looking for "+size+"b payload");
            inStream.read(data);
            lastConnect = System.currentTimeMillis();
            Log.d(TAG,getLogHeader()+" readPacketData() received data");
            return data;
        } else {
            String message = getLogHeader()+" readPacketData failed as reported data size is "+size+"b";
            Log.e(TAG,message);
            throw new IOException(message);
        }
    }

    /**
     * Equivalent to read(buffer, 0, buffer.length) (blocking)
     */
    public int read(byte[] buffer) throws IOException {
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot read as inStream is null");
        return inStream.read(buffer);
    }

    /**
     * Reads at most length bytes from this stream and stores them in the byte
     * array buffer starting at offset (blocking)
     */
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot read as inStream is null");
        return inStream.read(buffer, offset, length);
    }


    /**
     * Equivalent to write(buffer, 0, buffer.length).
     */
    public void write(byte[] buffer) throws IOException {
        //openStreamsIfNeeded();
        if (outStream == null)
            throw new IOException(getLogHeader()+" Cannot write as outStream is null");
        outStream.write(buffer);
        lastConnect = System.currentTimeMillis();
    }

    /**
     * Writes a single byte to this stream. (might block)
     */
    public void write(int oneByte) throws IOException {
        //openStreamsIfNeeded();
        if (outStream == null)
            throw new IOException(getLogHeader()+" Cannot write as outStream is null");
        outStream.write(oneByte);
        lastConnect = System.currentTimeMillis();
    }

    /**
     * Writes count bytes from the byte array buffer starting at position
     * offset to this stream. (might block)
     */
    public void write(byte[] buffer, int offset, int count) throws IOException {
        //openStreamsIfNeeded();
        if (outStream == null)
            throw new IOException(getLogHeader()+" Cannot write as outStream is null");
        outStream.write(buffer, offset, count);
        lastConnect = System.currentTimeMillis();
    }

    /**
     * Writes a string to stream (blocking)
     */
    public void write(String str) throws IOException {
        if (str==null) {
            str = "";
        }
        byte[] buffer = str.getBytes();
        write(buffer);
    }

    /**
     * Asynchronously write a buffer to stream
     */
    public void writeAsync(byte[] buffer) {
        writeAsync(buffer, null);
    }

    /**
     * Asynchronously write a buffer to stream. Activate writeListener at error/complete
     */
    public void writeAsync(byte[] buffer, WriteListener writeListener) {
        writeAsync(buffer, 0, buffer.length, writeListener);
    }

    /**
     * Asynchronously write count bytes from buffer starting at offset.
     * Activate writeListener at error/complete
     */
    public void writeAsync(final byte[] buffer, final int offset,
                           final int count, final WriteListener writeListener) {
        synchronized (writeThreadLock) {
            if (writeThread==null) {
                writeThread = Executors.newSingleThreadExecutor();
                Log.e(TAG, getLogHeader()+" writeThread created");
            }
        }
        writeThread.execute(() -> {
            try {
                write(buffer, offset, count);
                writeListener.onSuccess();
            }
            catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, getLogHeader()+" write error: " + e);
                if (writeListener != null) {
                    writeListener.onError(e);
                }
            }
        });
    }


    /**
     * Attempt to connect to a remote BT device (blocking).
     * Note that this method will cancel a running discovery session, if any.
     */
    public boolean connect(Context context) throws IOException {
        Log.i(TAG, getLogHeader()+" connecting..");
        boolean success = false;
        try {
            Core.markConnecting(true);
            socket.connect();
            success = true;
        }
        finally {
            Core.markConnecting(false);
            if (success)
                Log.i(TAG, getLogHeader()+" connect success");
            else
                Log.e(TAG, getLogHeader()+" connect failure");
        }
        return success;
    }

    /**
     * Closes socket releasing all attached system resources
     */
    public void close() {
        Log.d(TAG,getLogHeader()+" close()");
        keepGoing.set(false);
        if (socket != null) {
            try {
                socket.close();
            } catch (Exception e) {
                // no op
            }
            socket = null;
        }
        inStream = null;
        outStream = null;
    }


    static void closeIOThreads() {
        synchronized (writeThreadLock) {
            if (writeThread != null) {
                try { writeThread.shutdown(); } catch(Exception e) {}
                writeThread = null;
            }
        }
        synchronized (readThreadLock) {
            if (readThread != null) {
                try { readThread.shutdown(); } catch(Exception e) {}
                readThread = null;
            }
        }

    }

    /**
     * Get remote device object
     */
    public BluetoothDevice getRemoteDevice() {
        return socket.getRemoteDevice();
    }


    /**
     * Get remote device name
     */
    public String getName() {
        return socket.getRemoteDevice().getName();
    }

    private String tag = null;
    private String getLogHeader() {
        if (tag == null) {
            StringWriter out = new StringWriter();
            out.append("Socket #"+id);
            out.append(" (");
            switch (thisDeviceEndpointRole) {
                case SERVER:
                    out.append("Server");
                    break;
                case CLIENT:
                    out.append("Client");
                    break;
            }
            out.append("):");
            tag = out.toString();
        }
        return tag;
    }

    /**
     * Is this connection stale (i.e has never fully connected or last passed data a while ago)
     * @return
     */
    public boolean isStale() {
        if (lastConnect > 0l) {
            if (System.currentTimeMillis() > lastConnect + MIN_TIME_BEFORE_TESTING_STALE) {
                if ((outStream == null) || (inStream == null))
                    return true;
                return (System.currentTimeMillis() > lastConnect + MAX_TIME_BEFORE_STALE);
            }
        }
        return false;
    }
}
