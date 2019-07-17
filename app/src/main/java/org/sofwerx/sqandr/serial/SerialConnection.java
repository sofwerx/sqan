package org.sofwerx.sqandr.serial;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;

import com.hoho.android.usbserial.driver.PlutoCdcAsmDriver;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.util.SerialInputOutputManager;

import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.util.Crypto;
import org.sofwerx.sqandr.util.Loader;
import org.sofwerx.sqandr.util.SqANDRLoaderListener;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class SerialConnection extends AbstractDataConnection implements SerialInputOutputManager.Listener {
    private final static String TAG = Config.TAG+".serial";
    private final static int MAX_BYTES_PER_SEND = 240;
    private final static int SERIAL_TIMEOUT = 100;
    private final static long DELAY_FOR_LOGIN_WRITE = 500l;
    private final static long DELAY_BEFORE_BLIND_LOGIN = 1000l * 5l;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    //private SerialListener listener;
    private String username;
    private String password;
    private LoginStatus status = LoginStatus.NEED_CHECK_LOGIN_STATUS;
    private SdrAppStatus sdrAppStatus = SdrAppStatus.OFF;
    private HandlerThread handlerThread;
    private Handler handler;
    private Context context;
    private byte[] SDR_START_COMMAND;
    private final static char HEADER_DATA_PACKET_OUTGOING_CHAR = '*';
    private final static byte HEADER_DATA_PACKET_OUTGOING = (byte) HEADER_DATA_PACKET_OUTGOING_CHAR;
    private final static char HEADER_DATA_PACKET_INCOMING_CHAR = '+';
    private final static byte HEADER_DATA_PACKET_INCOMING = (byte)HEADER_DATA_PACKET_INCOMING_CHAR; //+
    private final static char HEADER_SYSTEM_MESSAGE_CHAR = 'm';
    private final static byte HEADER_SYSTEM_MESSAGE = (byte)HEADER_SYSTEM_MESSAGE_CHAR;
    private final static byte HEADER_SEND_COMMAND = (byte)99; //c
    private final static byte HEADER_DEBUG_MESSAGE = (byte)100; //d
    private final static char HEADER_SHUTDOWN_CHAR = 'e'; //e
    private final static byte[] SHUTDOWN_BYTES = {(byte)0b10010110,(byte)0b01101001,(byte)0b00100010,(byte)0b01000100};
    private final static byte[] NO_DATA_HEARTBEAT = {(byte)0b00000001,(byte)0b00000010,(byte)0b00000011,(byte)0b00000100};
    private final static byte HEADER_SHUTDOWN = (byte) HEADER_SHUTDOWN_CHAR; //e
    private final static byte HEADER_BUSYBOX = (byte)'b';
    //private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR +"\n").getBytes(StandardCharsets.UTF_8);
    private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "00" +"\n").getBytes(StandardCharsets.UTF_8);
    //private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "00112233445566778899aabbccddeeff" +"\n").getBytes(StandardCharsets.UTF_8);

    private final static boolean CONCAT_SEGMENT_BURSTS = true; //true == try to send as many segments as possible to the SDR on each write

    public final static int TX_GAIN = 10; //Magnitude in (0 to 85dB)

    private final static long TIME_BETWEEN_KEEP_ALIVE_MESSAGES = 7l; //adjust as needed
    private long nextKeepAliveMessage = Long.MIN_VALUE;
    private long lastSqandrHeartbeat = Long.MIN_VALUE;
    private final static long SQANDR_HEARTBEAT_STALE_TIME = 1000l * 5l;

    private final static long TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION = 200l; //ms to wait if the USB is having problems sending all its data

    enum LoginStatus { NEED_CHECK_LOGIN_STATUS,CHECKING_LOGGED_IN,WAITING_USERNAME, WAITING_PASSWORD, WAITING_CONFIRMATION, ERROR, LOGGED_IN }
    enum SdrAppStatus { OFF, CHECKING_FOR_UPDATE, INSTALL_NEEDED,INSTALLING, NEED_START, STARTING, RUNNING, ERROR }

    private final static boolean USE_BIN_USB_IN = false; //send binary input to Pluto
    private final static boolean USE_BIN_USB_OUT = true; //use binary output from Pluto
    private final static boolean USE_PLUTO_ONBOARD_FILTER = true;

    public SerialConnection(String username, String password) {
        this.username = username;
        this.password = password;

        SDR_START_COMMAND = (Loader.SDR_APP_LOCATION+Loader.SQANDR_VERSION
                //FIXME +" -tx "+String.format("%.2f", SdrConfig.getTxFreq())
                //FIXME +" -rx "+String.format("%.2f",SdrConfig.getRxFreq())
                //FIXME +" -txgain "+TX_GAIN
                //+" -transmitRepeat 1"
                //+" -messageRepeat 5"
                //FIXME +(USE_PLUTO_ONBOARD_FILTER?" -fir":"")
                //+" -txsrate 4"
                //+" -rxsrate 4"
                //+" -rxSize 600"
                //+" -txSize 600"
                //+" -header" //this flag is now implemented by default in SqANDR
                //+" -nonBlock" //this flag is now implemented by default in SqANDR
                +(USE_BIN_USB_IN ?" -binI":"")
                +(USE_BIN_USB_OUT ?" -binO":"")
                +" -minComms"
                //+" -verbose"
                +" -txsrate 1 -rxsrate 1 -fir" //TODO for testing
                +"\n").getBytes(StandardCharsets.UTF_8);//*/
        handlerThread = new HandlerThread("SerialCon") {
            @Override
            protected void onLooperPrepared() {
                handler = new Handler(handlerThread.getLooper());
                handler.postDelayed(new LoginHelper(null),DELAY_BEFORE_BLIND_LOGIN); //if no prompt is received, try to login anyway
                handler.postDelayed(periodicHelper,DELAY_BEFORE_BLIND_LOGIN);
            }
        };
        handlerThread.start();
    }

    /**
     * Just used for testing segmenter
     */
    public static void testSerialConnection() {
        SerialConnection conn = new SerialConnection(null,null);
        conn.runTests();
    }

    /**
     * Run tests t check raw data through to digest pathway
     */
    private final void runTests() {
        //byte[] raw1 = StringUtils.toByteArray("085e55121b1520c42d5555574a55555466992cc1583ea69a9ec5555d0d782734989d374ca3d9d55555555555543ea69a59237a4e993194081214205454545414fe545424485454545454545454545454545454545454545454545454545454545454545d0d7827342c75636500000000000066993140989d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c121b1520c42d5555555515fed04a55555466992cc1583ea69a9e55555555555555555555555555555555555555555555555d0d7827342474636500314054d9d4545454545454f41414237a319408121420255555555515fed04a55555466992cc1583e5555555555555555555555555555555555555555555555555555555555555d0d7827342c75636500000000000066993140989d54374ca3d9d45454545554543ea69a88f5151459237a4e993195083c5e6255121b155515fed04a55555466992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d7827342c7563650000000000000000000066993140989d55374ca3d9d55555579a88f5151459237a4e9931");
        byte[] raw2 = StringUtils.toByteArray("000000000000000000000066993140989d553755543ea69a88f5151459237a4e993195083c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d782736240000000000000000000066993140989d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec555555555555555555555555555555555555555555d0d7827342c7563650000000000000000000066993140989d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d555555551402992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d7827342c7563650000000000000000000066993140989d55374ca3d9d55555555555543ea69a8c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d7827342c756365000000000000003e9d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec5555555555555555555555555555555555555555555557827342c756365");
        //byte[] raw2 = StringUtils.toByteArray("66993140989d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec555555555555555555555555555555555555555555d0d7827342c7563650000000000000000000066993140989d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d555555551402992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d7827342c7563650000000000000000000066993140989d55374ca3d9d55555555555543ea69a8c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec55555555555555555555555555555555555555555555555555555555555555d0d7827342c756365000000000000003e9d55374ca3d9d55555555555543ea69a88f5151459237a4e993195083c5e6255121b1520c42d5555555515fed04a55555466992cc1583ea69a9ec5555555555555555555555555555555555555555555557827342c756365");


        //byte[] raw1 = StringUtils.toByteArray("66993160bf9d55374ca3d9d55555555555543ea7a640521514592df67cb37895083c5ff673b440152195d6d5555555147ed04a555554");
        //byte[] raw2 = StringUtils.toByteArray("66992ce17e3ea7a6515d5555555555555555555555555555555555555555555555555555555555555d0d7827342c756365");
        //byte[] raw3 = StringUtils.toByteArray("66993100d6ad5508dffe8ad55555555555543ea6c78ae71514592d57ce5ab395083c5ffe21b6021520dd1b55555555151887a755555466992e817a3ea6c7b4855555555555555555555555555555555555555555555555555555555555555f023d3c263e302c756c64");

        //handleRawDatalinkInput(raw1);
        handleRawDatalinkInput(raw2);
        //handleRawDatalinkInput(raw3);
    }

    public void open(@NonNull Context context, UsbDevice usbDevice) {
        this.context = context;
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if ((manager == null) || (usbDevice == null)) {
            Log.e(TAG, "Cannot open, manager or device are null");
            return;
        }

        if (port != null) {
            Log.d(TAG,"SerialConnection already open, ignoring open call");
            return;
        }
        Log.d(TAG,"Opening...");
        Runnable initRunnable = () -> {
            UsbSerialDriver driver = new PlutoCdcAsmDriver(usbDevice);
            connection = manager.openDevice(driver.getDevice());
            List<UsbSerialPort> ports = driver.getPorts();
            port = ports.get(0);
            try {
                port.open(connection);
                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                port.setDTR(true);
                port.setRTS(true);
                ioManager = new SerialInputOutputManager(port, SerialConnection.this);
                Executors.newSingleThreadExecutor().submit(ioManager);
                Log.d(TAG,"Serial Connection thread started");
            } catch (IOException e) {
                Log.e(TAG,"Unable to read: "+e.getMessage());
                try {
                    port.close();
                } catch (IOException e2) {
                    Log.e(TAG,"Unable to close port: "+e2.getMessage());
                }
            }
        };
        if (handler == null)
            initRunnable.run();
        else
            handler.post(initRunnable);
    }

    private void restartFromError() {
        if (sdrAppStatus == SdrAppStatus.ERROR) {
            if (peripheralStatusListener != null)
                peripheralStatusListener.onPeripheralMessage("Trying to fix SDR...");
            sdrAppStatus = SdrAppStatus.OFF;
            handler.postDelayed(() -> launchSdrApp(),2000);
        }
    }

    private Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            if (sdrAppStatus == SdrAppStatus.ERROR) {
                handler.postDelayed(() -> restartFromError(), 2000);
                return;
            }
            if (USE_BIN_USB_OUT) {
                if (lastSqandrHeartbeat > 0l) {
                    if (System.currentTimeMillis() > (lastSqandrHeartbeat + SQANDR_HEARTBEAT_STALE_TIME)) {
                        if (sdrAppStatus != SdrAppStatus.ERROR) {
                            Log.w(TAG,"SqANDR app appears to be offline");
                            sdrAppStatus = SdrAppStatus.ERROR;
                            if (peripheralStatusListener != null)
                                peripheralStatusListener.onPeripheralError("SqANDR app on the SDR stopped working");
                        }
                    } else {
                        if (sdrAppStatus != SdrAppStatus.RUNNING) {
                            Log.d(TAG,"SqANDR app is now running");
                            sdrAppStatus = SdrAppStatus.RUNNING;
                            if (listener != null)
                                listener.onOperational();
                            if (peripheralStatusListener != null)
                                peripheralStatusListener.onPeripheralReady();
                        }
                    }
                }
            }
            /*ignore for now
            if (sdrAppStatus == SdrAppStatus.RUNNING) {
                if (USE_BIN_USB_IN) {
                    //ignore for now
                } else {
                   if (!isSdrConnectionCongested() && (System.currentTimeMillis() > nextKeepAliveMessage)) {
                   }
                }
            }*/

            if (handler != null)
                handler.postDelayed(this,TIME_BETWEEN_KEEP_ALIVE_MESSAGES);
        }
    };

    int inArow = 0;

    public void close() {
        Log.d(TAG,"Closing...");
        if (handlerThread != null) {
            handlerThread.quitSafely();
            handlerThread = null;
            handler = null;
        }
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }
        sdrAppStatus = SdrAppStatus.OFF;
        if (port != null) {
            try {
                byte[] exitCommand;
                if (USE_BIN_USB_IN) {
                    exitCommand = SHUTDOWN_BYTES;
                } else {
                    String formattedData = HEADER_SHUTDOWN_CHAR + "\n";
                    exitCommand = formattedData.getBytes(StandardCharsets.UTF_8);
                }
                try {
                    port.write(exitCommand, 100);
                } catch (IOException e) {
                    Log.w(TAG,"Unable to close SDR app before shutting down: "+e.getMessage());
                }
                port.setDTR(false);
                port.setRTS(false);
            } catch (Exception ignored) {
            }
            try {
                port.close();
            } catch (IOException e) {
                Log.e(TAG,"Unable to close port: "+e.getMessage());
            }
            port = null;
        }
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception e) {
                Log.d(TAG,"Unable to close connection: "+e.getMessage());
            }
            connection = null;
        }
    }

    public boolean isActive() {
        return (port != null);
    }

    /**
     * Burst adds any wrapping needed to communicate the data and then conducts
     * a write
     * @param data
     */
    public void burstPacket(byte[] data) {
        if (data == null)
            return;
        if (sdrAppStatus == SdrAppStatus.RUNNING) {
            final byte[] cipherData = Crypto.encrypt(data);
            if (Segment.isAbleToWrapInSingleSegment(cipherData)) {
                Segment segment = new Segment();
                segment.setData(cipherData);
                segment.setStandAlone();
                Log.d(TAG,"burstPacket()");
                if (USE_BIN_USB_IN) {
                    Log.d(TAG,"Outgoing: *"+StringUtils.toHex(segment.toBytes()));
                    write(segment.toBytes());
                } else
                    write(toSerialLinkFormat(segment.toBytes()));
            } else {
                Log.d(TAG, "This packet is larger than the SerialConnection output, segmenting...");
                ArrayList<Segment> segments = Segmenter.wrapIntoSegments(cipherData);
                if ((segments == null) || segments.isEmpty()) {
                    Log.e(TAG, "There was an unexpected problem that did not produce any segments from this packet");
                    return;
                } else
                    Log.d(TAG,"Segmenting "+cipherData.length+"b packet into "+segments.size()+" segments");
                byte[] currentSegBytes;
                ByteBuffer concatted = null;
                if (CONCAT_SEGMENT_BURSTS) {
                    concatted = ByteBuffer.allocate(2000);
                    concatted.clear();
                }
                for (Segment segment : segments) {
                    currentSegBytes = segment.toBytes();
                    if (CONCAT_SEGMENT_BURSTS) {
                        if (concatted.position() + currentSegBytes.length < MAX_BYTES_PER_SEND)
                            concatted.put(currentSegBytes);
                        else {
                            if (concatted.position() > 0) {
                                currentSegBytes = new byte[concatted.position()];
                                concatted.flip();
                                concatted.get(currentSegBytes);
                                if (USE_BIN_USB_IN) {
                                    Log.d(TAG,"Outgoing: *"+StringUtils.toHex(currentSegBytes));
                                    write(currentSegBytes);
                                } else
                                    write(toSerialLinkFormat(currentSegBytes));
                                concatted.clear();
                            }
                        }
                    } else {
                        if (USE_BIN_USB_IN) {
                            Log.d(TAG, "Outgoing: " + StringUtils.toHex(currentSegBytes));
                            write(currentSegBytes);
                        } else {
                            write(toSerialLinkFormat(currentSegBytes));
                        }
                    }
                }
                if (CONCAT_SEGMENT_BURSTS) {
                    //send the last part of the segment
                    if (concatted.position() > 0) {
                        currentSegBytes = new byte[concatted.position()];
                        concatted.flip();
                        concatted.get(currentSegBytes);
                        if (USE_BIN_USB_IN) {
                            Log.d(TAG,"Outgoing: *"+StringUtils.toHex(currentSegBytes));
                            write(currentSegBytes);
                        } else
                            write(toSerialLinkFormat(currentSegBytes));
                        concatted.clear();
                    }
                }

                /*byte[] tempOut;
                outgoingBuffer.clear();
                outgoingBuffer.put(Segment.HEADER_MARKER);
                for (Segment segment : segments) {
                    currentSegBytes = Crypto.encrypt(segment.toBytes());
                    if (currentSegBytes.length > outgoingBuffer.remaining()) {
                        tempOut = new byte[outgoingBuffer.position()];
                        outgoingBuffer.get(tempOut);
                        if (USE_BIN_USB_IN) {
                            Log.d(TAG,"Outgoing: "+StringUtils.toHex(tempOut));
                            write(tempOut);
                        } else {
                            write(toSerialLinkFormat(tempOut));
                        }
                        outgoingBuffer.clear();
                        outgoingBuffer.put(Segment.HEADER_MARKER);
                    }
                    tempOut = new byte[outgoingBuffer.position()];
                    outgoingBuffer.get(tempOut);
                    if (USE_BIN_USB_IN) {
                        Log.d(TAG,"Outgoing: "+StringUtils.toHex(tempOut));
                        write(tempOut);
                    } else {
                        write(toSerialLinkFormat(tempOut));
                    }
                }*/
                //write(KEEP_ALIVE_MESSAGE); //TODO for testing
            }
        } else
            Log.d(TAG,"Dropping "+data.length+"b packet as SqANDR is not yet running on the SDR");
    }

    //private final static String PADDING_BYTE = "00112233445566778899";
    private final static String PADDING_BYTE = "00000000000000000000";
    private byte[] toSerialLinkFormat(byte[] data) {
        if (data == null)
            return null;
        String formattedData = HEADER_DATA_PACKET_OUTGOING_CHAR + PADDING_BYTE +StringUtils.toHex(data)+"\n";
        return formattedData.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Converts from the format sent over the serial connection into the actual byte array
     * @param raw
     * @return
     */
    private byte[] parseSerialLinkFormat(byte[] raw) {
        if ((raw == null) || (raw.length < 3))
            return null;
        return parseSerialLinkFormat(new String(raw,StandardCharsets.UTF_8));
    }

    private byte[] parseSerialLinkFormat(String raw) {
        if ((raw == null) || (raw.length() < 3))
            return null;
        char[] chars = raw.toCharArray();
        if (chars.length%2 == 1) {
            int j = 1;
            byte[] out = new byte[(chars.length-1)/2];
            for (int i = 0; i < out.length; i++) {
                out[i] = StringUtils.toByte(chars[j],chars[j+1]);
                j += 2;
            }
            Log.d(TAG,"parseSerialLinkFormat parsed "+out.length+"b");
            return out;
        } else
            Log.e(TAG,"Received data is not the right length: "+raw);
        return null;
    }

    /**
     * Write sends raw bytes to the connection as opposed to burstPacket() which adds
     * any wrapping needed to communicate the data
     * @param data
     */
    public void write(final byte[] data) {
        if (data == null)
            return;
        //if ((port == null) || (ioManager == null)) {
        if (port == null) {
            Log.e(TAG,"Unable to write data - serial port not open");
            return;
        }
        handler.post(() -> {
            try {
                if (port == null)
                    return;
                //    ioManager.writeAsync(data);
                nextKeepAliveMessage = System.currentTimeMillis() + TIME_BETWEEN_KEEP_ALIVE_MESSAGES;

                //TODO testing
                //if ((data != null) && (data.length > 10))
                CommsLog.log(CommsLog.Entry.Category.SDR,"Outgoing: "+new String(data,StandardCharsets.UTF_8));
                //    Log.d(TAG,"Outgoing: "+new String(data,StandardCharsets.UTF_8));
                //TODO testing

                int bytesWritten = port.write(data,SERIAL_TIMEOUT);
                if (bytesWritten < data.length)
                    sdrConnectionCongestedUntil = System.currentTimeMillis()+TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION;
            } catch (IOException e) {
                Log.e(TAG,"Unable to write data: "+e.getMessage());
            }
        });
    }

    private int attempts = 0;
    private class LoginHelper implements Runnable {
        private byte[] data;

        public LoginHelper(byte[] data) {
            this.data = data;
        }

        @Override
        public void run() {
            final String message;
            if (data == null)
                message = null;
            else
                message = new String(data, StandardCharsets.UTF_8);
            if (attempts > 3) {
                status = LoginStatus.ERROR;
                if (listener != null)
                    listener.onConnectionError("Unable to login after 3 attempts: "+((message==null)?"":message));
                //    listener.onSerialError(new Exception("Unable to login after 3 attempts: "+((message==null)?"":message)));
                return;
            }
            if (message == null) {
                if (status == LoginStatus.NEED_CHECK_LOGIN_STATUS) {
                    Log.d(TAG, "No login prompt received, checking to see if already logged-in...");
                    write(("help\r\n").getBytes(StandardCharsets.UTF_8));
                    status = LoginStatus.CHECKING_LOGGED_IN;
                    handler.postDelayed(new LoginHelper(null),DELAY_BEFORE_BLIND_LOGIN);
                } else if (((status == LoginStatus.WAITING_USERNAME) || (status == LoginStatus.CHECKING_LOGGED_IN)) && (username != null)) {
                    Log.d(TAG, "No login prompt received, providing username anyway...");
                    write((username + "\r\n").getBytes(StandardCharsets.UTF_8));
                    attempts++;
                    status = LoginStatus.WAITING_PASSWORD;
                }
            } else {
                if (message.length() < 3) //ignore short echo back messages
                    return;
                if (status == LoginStatus.CHECKING_LOGGED_IN) {
                    if (isLoggedInAlready(message)) {
                        Log.d(TAG, "Already logged-in");
                        status = LoginStatus.LOGGED_IN;
                        launchSdrApp();
                        if (listener != null)
                            listener.onConnect();
                            //listener.onSerialConnect();
                        return;
                    } else {
                        if (isLoginPasswordRequested(message)) {
                            //we're not logged in so we'll provide a junk password and try again
                            handler.post(() -> write("\r\n".getBytes(StandardCharsets.UTF_8)));
                            status = LoginStatus.WAITING_PASSWORD;
                        } else {
                            if (isLoginPasswordError(message)) {
                                Log.d(TAG,"SDR rejected our login attempt");
                                handler.post(new LoginHelper(message.getBytes(StandardCharsets.UTF_8)));
                                status = LoginStatus.WAITING_USERNAME;
                                return;
                            }
                            //don't know what the terminal is asking, so throw an error
                            status = LoginStatus.ERROR;
                            if (listener != null)
                                listener.onConnectionError("Unable to login; unknown message received: "+message);
                                //listener.onSerialError(new Exception("Unable to login; unknown message received: "+message));
                        }
                    }
                }
                if (((status == LoginStatus.NEED_CHECK_LOGIN_STATUS) || (status == LoginStatus.WAITING_USERNAME))
                        && (username != null) && isLoginRequested(message)) {
                    Log.d(TAG, "Providing username...");
                    write((username + "\r\n").getBytes(StandardCharsets.UTF_8));
                    attempts++;
                    status = LoginStatus.WAITING_PASSWORD;
                } else if ((status == LoginStatus.WAITING_PASSWORD) && (password != null) && isLoginPasswordRequested(message)) {
                    Log.d(TAG, "Providing password...");
                    write((password + "\r\n").getBytes(StandardCharsets.UTF_8));
                    status = LoginStatus.WAITING_CONFIRMATION;
                } else if (isLoginSuccessMessage(message)) {
                    Log.d(TAG, "Terminal login successful");
                    status = LoginStatus.LOGGED_IN;
                    launchSdrApp();
                    if (listener != null)
                        listener.onConnect();
                        //listener.onSerialConnect();
                } else if (isLoginPasswordError(message)) {
                    status = LoginStatus.WAITING_USERNAME;
                    handler.post(new LoginHelper(null));
                } else if (isLoginErrorMessage(message)) {
                    status = LoginStatus.ERROR;
                    if (listener != null)
                        listener.onConnectionError(message);
                        //listener.onSerialError(new Exception(message));
                }
            }
        }
    };

    @Override
    public void onNewData(byte[] data) {
        if ((data == null) || (data.length < Segment.HEADER_MARKER.length))
            return;
        if (sdrAppStatus == SdrAppStatus.STARTING) {
            String message = new String(data,StandardCharsets.UTF_8);
            if (message.contains(Loader.SQANDR_VERSION+": not found")) {
                Log.d(TAG,"From SDR during app start: "+message);
                sdrAppStatus = SdrAppStatus.INSTALL_NEEDED;
                launchSdrApp();
                return;
            } else if (message.contains("Acquiring IIO context")) {
                reportAppAsRunning();
                return;
            }
        }
        if (USE_BIN_USB_OUT && ((sdrAppStatus == SdrAppStatus.RUNNING) || (sdrAppStatus == SdrAppStatus.STARTING))) {
            handler.post(() -> {
                boolean heartbeat = false;
                int heartbeatReportedReceived = 0;
                if ((data.length == NO_DATA_HEARTBEAT.length) || (data.length == NO_DATA_HEARTBEAT.length + 1)) {
                    heartbeat = true;
                    for (int i=0;i<NO_DATA_HEARTBEAT.length;i++) {
                        if (data[i] != NO_DATA_HEARTBEAT[i])
                            heartbeat = false;
                    }
                    if (data.length == NO_DATA_HEARTBEAT.length + 1)
                        heartbeatReportedReceived = data[NO_DATA_HEARTBEAT.length] & 0xFF;
                }
                if (heartbeat) {
                    if (heartbeatReportedReceived > 0)
                        Log.d(TAG, ((heartbeatReportedReceived == (byte)255)?">=255":heartbeatReportedReceived) + "b transmitted by SqANDR app");
                    else
                        Log.d(TAG,"Heartbeat received from SqANDR app");
                    reportAppAsRunning();
                    lastSqandrHeartbeat = System.currentTimeMillis();
                } else {
                    //TODO temp for testing
                    if (sdrAppStatus == SdrAppStatus.RUNNING) {
                        //if ((data[0] == (byte)42) || (data[0] == (byte)100) || (data[0] == (byte)109))
                        if ((data[0] == (byte) 54)) // 6 - like the start of the SqAN header
                            Log.d(TAG, "From SDR: " + StringUtils.toHex(data) + ":" + new String(data));
                        else if ((data[0] != (byte) 42)) //ignore echos of sent data
                            Log.d(TAG, "From SDR: " + StringUtils.toHex(data));
                    }
                    //else
                    //    Log.d(TAG,"From SDR: +"+StringUtils.toHex(data));
                    //TODO temp for testing

                    /*byte[] preserveHeader = new byte[Segment.HEADER_MARKER.length];
                    for (int i=0; i< preserveHeader.length; i++) {
                        preserveHeader[i] = data[i];
                    }
                    byte[] plaintext = Crypto.decrypt(data);
                    for (int i=0; i< preserveHeader.length; i++) { //restore the header after any encryption/decryption
                        plaintext[i] = preserveHeader[i];
                    }*/
                    handleRawDatalinkInput(data);
                }
            });
            return;
        }
        //Log.d(TAG,"onNewData("+data.length+"b): "+new String(data));
        if (status != LoginStatus.LOGGED_IN) {
            if (handler != null)
                handler.postDelayed(new LoginHelper(data), DELAY_FOR_LOGIN_WRITE);
            if (listener != null)
                listener.onReceiveCommandData(data);
                //listener.onConnectionError(new String(data,StandardCharsets.UTF_8));
                //listener.onSerialRead(data);
        } else {
            if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE) {
                String message = new String(data,StandardCharsets.UTF_8);
                /*if (message.contains("messages")) { //messages also occurs in the /var/tmp folder
                    Log.d(TAG,"Reply \""+message+"\" from the SDR should contain the contents of the "+Loader.SDR_APP_LOCATION+" directory");
                    if (message.contains(Loader.SQANDR_VERSION)) {
                        Log.d(TAG,Loader.SQANDR_VERSION+" found, start needed");
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("Current version of SqANDR found, starting...");
                        sdrAppStatus = SdrAppStatus.NEED_START;
                    } else {
                        Log.d(TAG,Loader.SQANDR_VERSION+" not found, update needed");
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("SqANDR update needed...");
                        sdrAppStatus = SdrAppStatus.INSTALL_NEEDED;
                    }
                    launchSdrApp();
                }*/
                Log.d(TAG,"From SDR during app check: "+message);
                //if (!message.contains("\"INSTALLED\"") && !message.contains("\"FAILED\"")) {
                /*    if (message.contains("INSTALLED\n")) { //messages also occurs in the /var/tmp folder
                        Log.d(TAG, Loader.SQANDR_VERSION + " found, start needed");
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("Current version of SqANDR found, starting...");
                        sdrAppStatus = SdrAppStatus.NEED_START;
                        launchSdrApp();
                    } else if (message.contains("FAILED\n")) {
                        Log.d(TAG, Loader.SQANDR_VERSION + " not found, update needed");
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("SqANDR update needed...");
                        sdrAppStatus = SdrAppStatus.INSTALL_NEEDED;
                        launchSdrApp();
                    }*/
                //}
            }
            if (data[0] == HEADER_DEBUG_MESSAGE)
                return;
            if (data[0] == HEADER_DATA_PACKET_OUTGOING)
                return;
            if (handler != null) {
                String value = new String(data,StandardCharsets.UTF_8);
                /*if (value.indexOf('\n') >= 0) {
                    String[] values = value.split("\\n");
                    if (values != null) {
                        Log.d(TAG,"multi-line input detected: split into "+values.length+" inputs");
                        for (String part:values) {
                            if (part.charAt(0) != HEADER_DEBUG_MESSAGE)
                                handler.post(new SdrAppHelper(part));
                        }
                    }
                } else {*/
                    if (data[0] != HEADER_DEBUG_MESSAGE)
                        handler.post(new SdrAppHelper(value));
                //}
            }
        }
    }

    /*private boolean isDatalinkData(byte[] data) {
        if ((data == null) || (data.length < 3))
            return false;
        return data[0] == HEADER_DATA_PACKET_INCOMING;
    }*/

    public boolean isTerminalLoggedIn() { return status == LoginStatus.LOGGED_IN; }

    private String[] LOGIN_TEST_PASSED_WORDS = {"Built-in"};
    private boolean isLoggedInAlready(String message) {
        if (message != null) {
            for (String word:LOGIN_TEST_PASSED_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }


    private String[] LOGIN_ERROR_WORDS = {"incorrect"};
    private boolean isLoginPasswordError(String message) {
        if (message != null) {
            for (String word:LOGIN_ERROR_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }
    private String[] LOGIN_WORDS = {"login"};
    private boolean isLoginRequested(String message) {
        if (message != null) {
            for (String word:LOGIN_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] PASSWORD_REQUEST_WORDS = {"password","Password"};
    private boolean isLoginPasswordRequested(String message) {
        if (message != null) {
            for (String word:PASSWORD_REQUEST_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] ERROR_WORDS = {"error","cannot","unable"};
    private boolean isLoginErrorMessage(String message) {
        if (message != null) {
            for (String word:ERROR_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] SUCCESS_WORDS = {"Welcome to:","logged","help"};
    private boolean isLoginSuccessMessage(String message) {
        if (message != null) {
            for (String word:SUCCESS_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] SDR_APP_WORDS = {"Starting"};
    private boolean isSdrAppSuccessMessage(String message) {
        if (message != null) {
            for (String word:SDR_APP_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    @Override
    public void onRunError(Exception e) {
        Log.e(TAG,"onRunError()");
        if (listener != null)
            listener.onConnectionError(e.getMessage());
    }

    private class SdrAppHelper implements Runnable {
        private String input;

        public SdrAppHelper(String input) {
            this.input = input;
        }

        @Override
        public void run() {
            if (input != null) {
                if (sdrAppStatus == SdrAppStatus.RUNNING) {
                    Log.d(TAG,"From SDR (SAH): "+input);
                    switch (input.charAt(0)) {
                        case HEADER_DATA_PACKET_INCOMING_CHAR:
                            Log.d(TAG,"SdrAppHelper - found Data Packet");
                            handleRawDatalinkInput(parseSerialLinkFormat(input));
                            return;

                        case HEADER_SYSTEM_MESSAGE_CHAR:
                            input = input.substring(1)+"\n";
                            break;

                        case HEADER_DEBUG_MESSAGE:
                            Log.d(TAG,"Ignoring debug message: "+input.substring(1));
                            return;

                        case HEADER_SHUTDOWN:
                            Log.d(TAG,"The shutdown command to the SDR was received back (this is meaningless for the Android end); ignoring.");
                            return;

                        case HEADER_DATA_PACKET_OUTGOING:
                            Log.d(TAG,"Our own outgoing packet was received back(this is meaningless for the Android end); ignoring.");
                            return;

                        default:
                            if (input.contains("-sh")) {
                                final String errorMessage = "The app on the SDR is having a problem: "+input;
                                sdrAppStatus = SdrAppStatus.ERROR;
                                if (listener != null)
                                    listener.onConnectionError(errorMessage);
                                if (peripheralStatusListener != null)
                                    peripheralStatusListener.onPeripheralError(errorMessage);
                            } else
                                input = "Unknown command: "+input+"\n";
                    }
                } else if (sdrAppStatus == SdrAppStatus.INSTALLING) {
                    return; //ignore all messages during install
                }
            }
            if (attempts > 3) {
                sdrAppStatus = SdrAppStatus.ERROR;
                final String errorMessage = "Unable to start app after 3 attempts: "+((input==null)?"":input);
                if (listener != null)
                    listener.onConnectionError(errorMessage);
                if (peripheralStatusListener != null)
                    peripheralStatusListener.onPeripheralError(errorMessage);
                return;
            }
            if (input == null) {
                if (sdrAppStatus == SdrAppStatus.NEED_START)
                    launchSdrApp();
            } else {
                if (input.length() < 3) //ignore short echo back messages
                    return;
                if (isSdrAppSuccessMessage(input)) {
                    if (USE_BIN_USB_IN) {
                        //Bin IO relies on the SqANDR heartbeat to know when it is connected
                    } else {
                        if (!USE_BIN_USB_OUT) {
                            CommsLog.log(CommsLog.Entry.Category.SDR, "SDR companion app is running");
                            reportAppAsRunning();
                        }
                    }
                } else {
                    if (USE_BIN_USB_IN) {
                        //ignore
                    } else {
                        if (sdrAppStatus == SdrAppStatus.RUNNING)
                            CommsLog.log(CommsLog.Entry.Category.SDR, "SDR input: " + input);
                        if (listener != null)
                            listener.onReceiveCommandData(input.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
        }
    }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        super.setPeripheralStatusListener(listener);
        if (listener != null) {
            switch (sdrAppStatus) {
                case OFF:
                case STARTING:
                case NEED_START:
                    listener.onPeripheralMessage("SDR is getting ready...");
                    break;

                case INSTALLING:
                case INSTALL_NEEDED:
                case CHECKING_FOR_UPDATE:
                    listener.onPeripheralMessage("SDR is checking for updated software...");
                    break;

                case RUNNING:
                    Log.d(TAG,"onPeripheralReady()");
                    listener.onPeripheralReady();
                    break;

                default:
                    listener.onPeripheralError("SDR has an error");
                    break;
            }
        }
    }

        private void startSdrApp() {
        if ((sdrAppStatus == SdrAppStatus.NEED_START) || (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE)) {
            sdrAppStatus = SdrAppStatus.STARTING;
            final String message = "Starting SDR companion app (SqANDR)";
            Log.d(TAG,message);
            if (peripheralStatusListener != null)
                peripheralStatusListener.onPeripheralMessage(message);
            attempts = 0;
            CommsLog.log(CommsLog.Entry.Category.SDR,"Initiating SDR App with command: "+new String(SDR_START_COMMAND,StandardCharsets.UTF_8));
            write(SDR_START_COMMAND);
        }
    }

    private void reportAppAsRunning() {
        if (sdrAppStatus != SdrAppStatus.RUNNING) {
            Log.d(TAG,"Reporting SDR app is now running");
            sdrAppStatus = SdrAppStatus.RUNNING;
            if (listener != null)
                listener.onOperational();
            if (peripheralStatusListener != null) {
                Log.d(TAG, "onPeripheralReady()");
                peripheralStatusListener.onPeripheralReady();
            }
        }
    }

    /**
     * Installs the SqANDR app on the SDR if needed ans starts the app
     */
    private void launchSdrApp() {
        Log.d(TAG, "launchSdrApp()");
        //if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE)
        //    return;
        if (sdrAppStatus == SdrAppStatus.OFF) {
            sdrAppStatus = SdrAppStatus.CHECKING_FOR_UPDATE;
            if (peripheralStatusListener != null)
                peripheralStatusListener.onPeripheralMessage("Checking if current version of SqANDR is installed...");
            //Loader.queryIsCurrentSqANDRInstalled(port);
            startSdrApp();
        } else if (sdrAppStatus == SdrAppStatus.NEED_START) {
            startSdrApp();
        } else {
            if (sdrAppStatus != SdrAppStatus.INSTALL_NEEDED)
                return; //already installing, ignore
            if (peripheralStatusListener != null)
                peripheralStatusListener.onPeripheralMessage("Updating SqANDR...");
            if (context == null) {
                final String errorMessage = "Cannot push the SqANDR app onto the SDR with a null context - this should never happen";
                Log.e(TAG,errorMessage);
                sdrAppStatus = SdrAppStatus.ERROR;
                if (listener != null)
                    listener.onConnectionError(errorMessage);
                if (peripheralStatusListener != null)
                    peripheralStatusListener.onPeripheralError(errorMessage);
                return;
            }
            sdrAppStatus = SdrAppStatus.INSTALLING;
            Loader.pushAppToSdr(context, port, new SqANDRLoaderListener() {
                @Override
                public void onSuccess() {
                    sdrAppStatus = SdrAppStatus.NEED_START;
                    if (peripheralStatusListener != null)
                        peripheralStatusListener.onPeripheralMessage("SqANDR updated.");
                    startSdrApp();
                }

                @Override
                public void onFailure(String message) {
                    Log.e(TAG,"Error installing SqANDR: "+message);
                    sdrAppStatus = SdrAppStatus.ERROR;
                    if (listener != null)
                        listener.onConnectionError(message);
                    if (peripheralStatusListener != null)
                        peripheralStatusListener.onPeripheralError(message);
                }

                @Override
                public void onProgressPercent(int percent) {
                    if (peripheralStatusListener != null)
                        peripheralStatusListener.onPeripheralMessage("Installing SqANDR: "+percent+"%...");
                }
            });
        }
    }
}
