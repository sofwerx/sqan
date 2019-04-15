package org.sofwerx.sqan.manet.bt.helper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.issues.PacketDropIssue;
import org.sofwerx.sqan.manet.common.packet.AbstractPacket;
import org.sofwerx.sqan.manet.common.packet.PacketDropException;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.manet.common.sockets.PacketParser;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;

public class BTSocket {
    public final static int MAX_PACKET_SIZE = 65400; //FIXME arbitrary picked size
    private final static int POOL_WARNING_SIZE = 1000; //warn if the write queue gets bigger than this
    private final static long MIN_TIME_BEFORE_TESTING_STALE = 1000l * 10l;
    private final static long MAX_TIME_BEFORE_STALE = 1000l * 60l * 5l;
    private final static String TAG = Config.TAG+".BTSocket";
    private static AtomicInteger connectionCounter = new AtomicInteger(0);
    private final static byte ALIGNMENT_BYTE_A = 0b0100100;
    private final static byte ALIGNMENT_BYTE_B = 0b1011011;
    private final static byte ALIGNMENT_BYTE_C = 0b1011110;
    private final static byte ALIGNMENT_BYTE_D = 0b1100000;

    private BluetoothSocket socket;
    private InputStream inStream;
    private OutputStream outStream;
    private MacAddress mac;
    private SqAnDevice device;
    private Role thisDeviceEndpointRole;
    private ReadListener readListener;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);
    private int id = connectionCounter.incrementAndGet();
    private long lastConnectInbound = Long.MIN_VALUE;
    private long lastConnectOutbound = Long.MIN_VALUE;
    private static AtomicInteger poolCount = new AtomicInteger(0);

    public SqAnDevice setDeviceIfNull(SqAnDevice device) {
        if (this.device == null) {
            if ((mac != null) && (device != null)) {
                device.setBluetoothMac(mac.toString());
                SavedTeammate teammate = Config.getTeammateByBtMac(mac);
                if (teammate != null) {
                    if (device.getCallsign() != null)
                        teammate.setCallsign(device.getCallsign());
                    if (device.isUuidKnown())
                        teammate.setSqAnId(device.getUUID());
                }
                CommsLog.log(CommsLog.Entry.Category.COMMS,"BT connection established with "+((device.getCallsign()==null)?device.getUUID():device.getCallsign()));
            }
            this.device = device;
            Config.updateSavedTeammates();
            return this.device;
        } else if (!this.device.isUuidKnown()) {
            this.device.update(device);
            SavedTeammate teammate = Config.getTeammateByBtMac(mac);
            if (teammate != null) {
                if (device.getCallsign() != null)
                    teammate.setCallsign(device.getCallsign());
                if (device.isUuidKnown())
                    teammate.setSqAnId(device.getUUID());
            }
            Config.updateSavedTeammates();
            return this.device;
        }
        return device;
    }

    public enum Role {SERVER,CLIENT};

    private Thread readThread;
    private static ArrayList<Thread> readThreads = new ArrayList();
    private static ExecutorService writeThread;

    //private static final Object readThreadLock = new Object();
    private static final Object writeThreadLock = new Object();

    public BTSocket(BluetoothSocket socket, Role role, final ReadListener readListener) {
        this.thisDeviceEndpointRole = role;
        this.readListener = readListener;
        if (socket==null)
            throw new RuntimeException(getLogHeader()+" BluetoothSocket param cannot be null!");
        if (socket.getRemoteDevice() == null)
            Log.e(TAG,getLogHeader()+" added ("+((role==Role.SERVER)?"Server":"Client")+"), unexpected null MAC");
        else
            Log.e(TAG,getLogHeader()+" added ("+((role==Role.SERVER)?"Server":"Client")+") MAC:"+socket.getRemoteDevice().getAddress());
        this.socket = socket;
        if (socket.getRemoteDevice() != null)
            mac = MacAddress.build(socket.getRemoteDevice().getAddress());
        device = SqAnDevice.findByBtMac(mac);
        if (device == null) {
            SavedTeammate teammate = Config.getTeammateByBtMac(mac);
            if (teammate != null) {
                CommsLog.log(CommsLog.Entry.Category.COMMS,"BT connection match to "+((teammate.getCallsign()==null)?"saved teammate":teammate.getCallsign())+" info found");
                device = new SqAnDevice(teammate.getSqAnAddress(),teammate.getCallsign());
                //device.setNetworkId(teammate.getNetID());
                device.setBluetoothMac(mac.toString());
                if (SqAnDevice.add(device))
                    teammate.setSqAnId(device.getUUID());
            }
        } else {
            if (device.getCallsign() == null) {
                SavedTeammate teammate = Config.getTeammateByBtMac(mac);
                if (teammate != null)
                    device.setCallsign(teammate.getCallsign());
            }
            Log.d(TAG,getLogHeader()+ " match to existing device "+((device.getCallsign()==null)?"":"("+device.getCallsign()+") ")+"found");
        }
        Core.registerForCleanup(this);
        lastConnectInbound = System.currentTimeMillis();
        lastConnectOutbound = System.currentTimeMillis();
    }

    public static boolean isCongested() {
        return poolCount.get() > POOL_WARNING_SIZE;
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
        } catch (Exception e) {
            Log.e(TAG,getLogHeader()+" Unable to open streams: "+e.getMessage());
        }
    }

    private ReadListener mReadListener = new ReadListener() {
        @Override
        public void onSuccess(AbstractPacket packet) {
            if (readListener != null)
                readListener.onSuccess(packet);
            //if (keepGoing.get())
            //    readAsyncPacket(this);
        }

        @Override
        public void onError(IOException e) {
            if (readListener != null)
                readListener.onError(e);
        }

        @Override
        public void onPacketDropped() {
            if (readListener != null)
                readListener.onPacketDropped();
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

    public void readAsyncPacket(final ReadListener readListener) {
        if (readThread == null) {
            readThread = new Thread() {
                @Override
                public void run() {
                    Log.d(TAG, getLogHeader()+" readThread created");
                    while (keepGoing.get()) {
                        try {
                            byte[] data = readPacketData();
                            if (data == null)
                                Log.e(TAG, getLogHeader() + " readPacketData produced null data");
                            else {
                                Log.d(TAG, getLogHeader() + " readPacketData returned " + data.length + "b");
                                lastConnectInbound = System.currentTimeMillis();
                                final AbstractPacket packet = AbstractPacket.newFromBytes(data);
                                if (packet == null) {
                                    if (readListener != null)
                                        readListener.onError(new IOException("Unable to processPacketAndNotifyManet Packet"));
                                } else {
                                    SqAnDevice device = PacketParser.process(packet);
                                    if (device != null) {
                                        device.addToDataTally(data.length);
                                        device = setDeviceIfNull(device);
                                    }
                                    if (mReadListener != null)
                                        mReadListener.onSuccess(packet);
                                    final int hopCount = packet.getCurrentHopCount()+1;
                                    PacketHeader.setHopCount(hopCount,data);
                                    if ((hopCount <= SqAnDevice.getActiveConnections())) {

                                        //relay logic

                                        if (thisDeviceEndpointRole == Role.SERVER) {
                                            if ((hopCount == 1) || AddressUtil.isApplicableAddress(device.getUUID(), packet.getSqAnDestination()) && (hopCount < device.getHopsToDevice(packet.getOrigin()))) {
                                                if (packet.isLossy() && BTSocket.isCongested())
                                                    CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " not relaying (as server) " + packet.getClass().getSimpleName() + "(origin " + packet.getOrigin() + ", " + hopCount + " hops) due to network congestion and packet marked as lossyOk");
                                                else {
                                                    CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " relaying (as server) " + packet.getClass().getSimpleName() + "(origin " + packet.getOrigin()+", " + hopCount + " hops)");
                                                    Core.send(data, packet.getSqAnDestination(), packet.getOrigin(), true, true);
                                                }
                                            }
                                        } else { //when this device isnt in server mode, check all other connections and send based on hop comparisons
                                            if (packet.isLossy() && BTSocket.isCongested()) {
                                                CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " " + packet.getClass().getSimpleName() + "(origin " + packet.getOrigin()+", " + hopCount + " hops) not evaluated for relay since network is congested and packet marked as lossyOk");
                                                continue;
                                            }
                                            ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
                                            if (devices != null) {
                                                for (SqAnDevice tgt : devices) {
                                                    if (tgt.getUUID() != packet.getOrigin()) { //avoid circular reporting
                                                        if (tgt.getHopsAway() == 0) { //for directly connected devices
                                                            if ((hopCount <= tgt.getHopsToDevice(packet.getOrigin())) //when our hop count is same or better
                                                                    || (tgt.getActiveRelays() < 2)) { //or when this is the target's only connection
                                                                CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " relaying (as client) " + packet.getClass().getSimpleName() +"(origin " + packet.getOrigin()+", " + hopCount + " hops) to "+tgt.getCallsign()+" ("+tgt.getUUID()+")");
                                                                tgt.setLastForward();
                                                                Core.send(data, tgt.getUUID(), packet.getOrigin());
                                                            } else
                                                                CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " is not relaying (as client) [hop count to originator "+tgt.getHopsToDevice(packet.getOrigin())+", active relays "+tgt.getActiveRelays()+"] " + packet.getClass().getSimpleName() +"(origin " + packet.getOrigin()+", " + hopCount + " hops) to "+tgt.getCallsign()+" ("+tgt.getUUID()+")");
                                                        } else
                                                            CommsLog.log(CommsLog.Entry.Category.COMMS, getLogHeader() + " is not relaying (as client) "+((tgt.getHopsAway() > 1000)?"[unreachable]":("[hops away "+tgt.getHopsAway()+"]")));
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (PacketDropException e) {
                            if (device != null)
                                device.addIssue(new PacketDropIssue());
                            Log.e(TAG, getLogHeader()+" read error: " + e.getMessage());
                            if (readListener != null)
                                readListener.onPacketDropped();
                        } catch (IOException e) {
                            Log.e(TAG, getLogHeader()+" read error: " + e.getMessage());
                            if (readListener != null)
                                readListener.onError(e);
                        }
                    }
                    Log.d(TAG,getLogHeader()+" readThread closing...");
                }
            };
            readThreads.add(readThread);
            readThread.start();
        }
    }

    private boolean lastReportedIsActive = false;
    public boolean isActive() {
        boolean active;
        if ((socket != null) && socket.isConnected())
            active = (inStream != null) && (outStream != null);
        else
            active = false;

        if (active != lastReportedIsActive) {
            lastReportedIsActive = active;
            CommsLog.log(CommsLog.Entry.Category.STATUS,"BT Socket #"+id+" is "+(active?"active":"not active"));
        }

        return active;
    }

    /**
     * Read an Int from stream (blocking)
     * @return int
     * @throws IOException
     */
    private int readInt() throws IOException {
        //Log.d(TAG,getLogHeader()+" readInt()");
        byte[] byteInt = new byte[4];
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot readInt(), inStream is null");
        try {
            inStream.read(byteInt);
        } catch (Exception e) {
            throw new IOException(getLogHeader()+" Error in readInt(): "+e.getMessage());
        }
        //Log.d(TAG,getLogHeader()+" readInt() received data");
        return ByteBuffer.wrap(byteInt).getInt();
    }

    /**
     * Reads until a specific byte is found; should be the first byte read, but this is implemented
     * to get the stream back in sync if noise is introduced
     * @throws IOException
     */
    private void readAlignmentByte() throws IOException {
        Log.d(TAG,getLogHeader()+" readAlignmentByte()");
        if (inStream == null)
            throw new IOException(getLogHeader()+" Cannot readAlignmentByte(), inStream is null");
        try {
            int shift = 0;
            boolean found = false;
            while (!found) {
                while (inStream.read() != ALIGNMENT_BYTE_A) {
                    shift++;
                }
                found = inStream.read() == ALIGNMENT_BYTE_B;
            }
            if (shift > 0) {
                Log.w(TAG, getLogHeader() + " alignment byte not found where expected; packet start shifted by " + shift + "b");
                if (device != null)
                    device.addIssue(new PacketDropIssue());
            }
        } catch (Exception e) {
            keepGoing.set(false);
            close();
            throw new IOException(getLogHeader()+" Error in readAlignmentByte(): "+e.getMessage());
        }
    }

    /**
     * Read a packet of raw byte array data from stream (blocking)
     * @return
     * @throws IOException
     */
    private byte[] readPacketData() throws IOException, PacketDropException {
        //Log.d(TAG,getLogHeader()+" readPacketData()");
        readAlignmentByte();
        int size = readInt();
        if (size > 0) {
            if (size > MAX_PACKET_SIZE) {
                String message = getLogHeader()+" readPacketData() is reporting a packet size of "+size+" which seems invalid. Packet being dropped...";
                Log.e(TAG, message);
                throw new IOException(message);
            }
            byte[] data = new byte[size];
            //Log.d(TAG,getLogHeader()+" readPacketData() looking for "+size+"b payload");
            inStream.read(data);
            byte checksum = (byte)inStream.read();
            byte calculatedChecksum = NetUtil.getChecksum(data);
            if (checksum != calculatedChecksum) {
                throw new PacketDropException(getLogHeader() + " received a packet that did not have the proper checksum (" + calculatedChecksum + " expected but " + checksum + " received)");
            } else {
                //Log.d(TAG, getLogHeader() + " readPacketData() received data");
                return data;
            }
        } else {
            String message = getLogHeader()+" readPacketData failed as reported data size is "+size+"b";
            CommsLog.log(CommsLog.Entry.Category.PROBLEM,message);
            throw new IOException(message);
        }
    }

    /**
     * Writes the raw data but also wraps it in the alignment byte, the length of the data, and checksum needed for reading
     * and error checking
     * @param data
     * @throws IOException
     */
    public void write(final byte[] data) {
        synchronized (writeThreadLock) {
            if (writeThread==null) {
                writeThread = Executors.newSingleThreadExecutor();
                Log.d(TAG, getLogHeader()+"writeThread created");
            }
        }
        poolCount.incrementAndGet();
        writeThread.execute(() -> {
            try {
                if (data == null)
                    throw new IOException(getLogHeader()+" Cannot write as data is null");
                if (outStream == null)
                    throw new IOException(getLogHeader()+" Cannot write as outStream is null");
                outStream.write(ALIGNMENT_BYTE_A);
                outStream.write(ALIGNMENT_BYTE_B);
                byte[] length = NetUtil.intToByteArray(data.length);
                //outStream.write(ALIGNMENT_BYTE_C);
                //outStream.write(ALIGNMENT_BYTE_D);
                outStream.write(length);
                outStream.write(data);
                byte checksum = NetUtil.getChecksum(data);
                outStream.write(checksum);
                lastConnectOutbound = System.currentTimeMillis();
            } catch (IOException e) {
                Log.d(TAG, getLogHeader()+" writeThread error: "+e.getMessage());
                if (e != null) {
                    String message = e.getMessage();
                    if ((message != null) && message.contains("Broken")) {
                        keepGoing.set(false);
                        close();
                    }
                }
            } finally {
                if (poolCount.decrementAndGet() > POOL_WARNING_SIZE)
                    CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Warning, BTSocket write queue is "+poolCount.get());
            }
        });
    }

    /**
     * Attempt to connect to a remote BT device (blocking).
     */
    public boolean connect() throws IOException {
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
        if (lastConnectInbound > 0l) {
            if (System.currentTimeMillis() > lastConnectInbound + MIN_TIME_BEFORE_TESTING_STALE) {
                if (!isActive())
                    return true;
                return (System.currentTimeMillis() > lastConnectInbound + MAX_TIME_BEFORE_STALE) ||
                        (System.currentTimeMillis() > lastConnectOutbound + MAX_TIME_BEFORE_STALE);
            }
        }
        return false;
    }
}
