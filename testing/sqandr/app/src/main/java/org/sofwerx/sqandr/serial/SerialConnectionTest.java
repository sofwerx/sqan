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

import org.sofwerx.sqan.rf.SignalConverter;
import org.sofwerx.sqan.rf.SignalProcessingListener;
import org.sofwerx.sqan.rf.SignalProcessor;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.testing.PlutoStatus;
import org.sofwerx.sqandr.testing.SqandrStatus;
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

/**
 * Test version of the SerialConnection class for configuration testing of SQANDR
 */
public class SerialConnectionTest extends AbstractDataConnection implements SerialInputOutputManager.Listener, SignalProcessingListener {
    private final static String TAG = Config.TAG+".Serial";
    private final static boolean USE_LEAN_MODE = false; //TODO in development, Lean mode - sqandr provides raw data and uses the most efficient data structure; binIn,binOut, and tx/rx buffer sizes are set by SqANDR
    private final static boolean USE_BIN_USB_IN = true; //send binary input to Pluto
    private final static boolean USE_BIN_USB_OUT = true; //use binary output from Pluto
    //TODO this is the good setting: private final static String OPTIMAL_FLAGS = "-txSize 120000 -rxSize 40000 -messageRepeat 22 -rxsrate 3.3 -txsrate 3.3 -txbandwidth 2.3 -rxbandwidth 2.3";
    private final static String OPTIMAL_FLAGS = "-txSize 21000 -rxSize 21000 -messageRepeat 4 -rxsrate 3.3 -txsrate 3.3 -txbandwidth 2.3 -rxbandwidth 2.3";
    private final static int MAX_BYTES_PER_SEND = 252;
    private final static int SERIAL_TIMEOUT = 100;
    private final static long DELAY_FOR_LOGIN_WRITE = 500l;
    private final static long DELAY_BEFORE_BLIND_LOGIN = 1000l * 5l;
    private final static boolean USE_ESC_BYTES = true;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    //private SerialListener listener;
    private final String username = "root";
    private final String password = "analog";
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
    private final static byte[] SHUTDOWN_BYTES = {(byte)0b00010000,(byte)0b00010000,(byte)0b00010000,(byte)0b00010000};
    private final static byte[] NO_DATA_HEARTBEAT = {(byte)0b00000001,(byte)0b00000010,(byte)0b00000011,(byte)0b00000100};
    private final static byte LESS_THAN_32 = 0b00011111;
    private final static byte HEADER_SHUTDOWN = (byte) HEADER_SHUTDOWN_CHAR; //e
    private final static byte HEADER_BUSYBOX = (byte)'b';
    //private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR +"\n").getBytes(StandardCharsets.UTF_8);
    private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "00" +"\n").getBytes(StandardCharsets.UTF_8);

    private final static boolean CONCAT_SEGMENT_BURSTS = true; //true == try to send as many segments as possible to the SDR on each write

    public final static int TX_GAIN = 10; //Magnitude in (0 to 85dB)

    private final static long TIME_BETWEEN_KEEP_ALIVE_MESSAGES = 7l; //adjust as needed
    private long nextKeepAliveMessage = Long.MIN_VALUE;
    private final static long SQANDR_HEARTBEAT_STALE_TIME = 1000l * 5l;

    private final static long TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION = 2000l; //ms to wait if the USB is having problems sending all its data

    enum LoginStatus { NEED_CHECK_LOGIN_STATUS,CHECKING_LOGGED_IN,WAITING_USERNAME, WAITING_PASSWORD, WAITING_CONFIRMATION, ERROR, LOGGED_IN }
    enum SdrAppStatus { OFF, CHECKING_FOR_UPDATE, INSTALL_NEEDED,INSTALLING, NEED_START, STARTING, RUNNING, ERROR }

    private ByteBuffer serialFormatBuf = ByteBuffer.allocate(MAX_BYTES_PER_SEND*2);
    private boolean processOnPluto = false;
    private SignalProcessor signalProcessor;

    private final static long BURST_LAG_WARNING = 1l;

    public SerialConnectionTest(String commands) {
        if (!processOnPluto)
            signalProcessor = new SignalProcessor(this,USE_LEAN_MODE);

        setCommandFlags(commands);
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

    public void setCommandFlags(String flags) {
        if (USE_LEAN_MODE) {
            SDR_START_COMMAND = (Loader.SDR_APP_LOCATION + Loader.SQANDR_VERSION
                    + " -lean"
                    + ((flags == null) ? "" : " " + flags)
                    + "\n").getBytes(StandardCharsets.UTF_8);
        } else {
            SDR_START_COMMAND = (Loader.SDR_APP_LOCATION + Loader.SQANDR_VERSION
                    + (USE_BIN_USB_IN ? " -binI" : "")
                    + (USE_BIN_USB_OUT ? " -binO" : "")
                    + ((processOnPluto || !USE_BIN_USB_OUT) ? "" : " -rawOut")
                    + ((flags == null) ? "" : " " + flags)
                    + "\n").getBytes(StandardCharsets.UTF_8);
        }
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
                ioManager = new SerialInputOutputManager(port, SerialConnectionTest.this);
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
            if (listener != null)
                listener.onPlutoStatus(PlutoStatus.ERROR,"Trying to fix SDR...");
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
            if (USE_BIN_USB_OUT && processOnPluto) {
                if (lastSqandrHeartbeat > 0l) {
                    if (System.currentTimeMillis() > (lastSqandrHeartbeat + SQANDR_HEARTBEAT_STALE_TIME)) {
                        if (sdrAppStatus != SdrAppStatus.OFF) {
                            sdrAppStatus = SdrAppStatus.OFF;
                            if (listener != null)
                                listener.onSqandrStatus(SqandrStatus.OFF,null);
                        }
                    } else {
                        if (sdrAppStatus != SdrAppStatus.RUNNING) {
                            Log.d(TAG,"SqANDR app is now running");
                            sdrAppStatus = SdrAppStatus.RUNNING;
                            if (listener != null)
                                listener.onSqandrStatus(SqandrStatus.RUNNING,null);
                        }
                    }
                }
            }

            if (handler != null)
                handler.postDelayed(this,TIME_BETWEEN_KEEP_ALIVE_MESSAGES);
        }
    };

    public void stopApp() {
        Log.d(TAG,"Stopping SqANDR...");
        sdrAppStatus = SdrAppStatus.OFF;
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
            Log.w(TAG,"Unable to stop SqANDR: "+e.getMessage());
        }
        if (listener != null)
            listener.onSqandrStatus(SqandrStatus.OFF,"SqANDR stopped");
    }

    public void close() {
        if (handlerThread != null) {
            Log.d(TAG,"Closing...");
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
        if (signalProcessor != null) {
            signalProcessor.shutdown();
            signalProcessor = null;
        }
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
        //Log.d(TAG,"burstPacket wrapping "+StringUtils.toHex(data));
        if (sdrAppStatus == SdrAppStatus.RUNNING) {
            final byte[] cipherData = Crypto.encrypt(data);
            if (Segment.isAbleToWrapInSingleSegment(cipherData)) {
                Segment segment = new Segment();
                segment.setData(cipherData);
                segment.setStandAlone();
                if (USE_BIN_USB_IN) {
                    byte[] outgoingBytes = segment.toBytes();
                    Log.d(TAG,"Outgoing (burst, standalone): *"+StringUtils.toHex(outgoingBytes));
                    write(toSerialLinkBinFormat(outgoingBytes));
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
                                byte[] bytesToSend = new byte[concatted.position()];
                                concatted.flip();
                                concatted.get(bytesToSend);
                                if (USE_BIN_USB_IN) {
                                    Log.d(TAG,"Outgoing: *"+StringUtils.toHex(currentSegBytes));
                                    write(toSerialLinkBinFormat(bytesToSend));
                                } else
                                    write(toSerialLinkFormat(bytesToSend));
                                concatted.clear();
                                concatted.put(currentSegBytes);
                            } else
                                Log.w(TAG,"Current segment size ("+currentSegBytes.length+"b) > max bytes per send ("+MAX_BYTES_PER_SEND+"b)");
                        }
                    } else {
                        if (USE_BIN_USB_IN) {
                            Log.d(TAG, "Outgoing: " + StringUtils.toHex(currentSegBytes));
                            write(toSerialLinkBinFormat(currentSegBytes));
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
                            write(toSerialLinkBinFormat(currentSegBytes));
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
            //if (signalProcessor != null)
            //    signalProcessor.turnOnDetailedIq();
        } else
            Log.d(TAG,"Dropping "+data.length+"b packet as SqANDR is not yet running on the SDR");
    }

    //private final static String PADDING_BYTE = "00000000000000000000";
    private final static String PADDING_BYTE = "";

    /**
     * Provides a text output format that SqANDR will intake and translate into binary
     * @param data
     * @return
     */
    private byte[] toSerialLinkFormat(byte[] data) {
        if (data == null)
            return null;
        String formattedData = HEADER_DATA_PACKET_OUTGOING_CHAR + PADDING_BYTE +StringUtils.toHex(data)+"\n";
        return formattedData.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Provides a binary output format that adjusts the stream based on the restrictions
     * Pluto has on specific byte values in stdin
     * @param data
     * @return
     */
    private byte[] toSerialLinkBinFormat(byte[] data) {
        if (data == null)
            return null;
        byte[] out;
        int values = 0;
        serialFormatBuf.clear();

        //Look for illegal byte values and then provide an escaped value and marking. Specifically,
        //anything below 32 is considered illegal. 64 is used as a marker that the next byte
        //has been altered from an illegal byte value. For consistency, marking illegal bytes
        //is done by adding 64 to the byte. This is a work-around to Pluto intercepting some
        //byte values (like 13, which is ASCII for carriage return) and then altering the
        //stream in response to those values
        if (USE_LEAN_MODE)
            serialFormatBuf.put(SignalConverter.SQAN_HEADER); //FIXME for testing
        for (int i=0;i<data.length;i++) {
            if ((data[i] & LESS_THAN_32) == data[i]) {
                serialFormatBuf.put((byte)64);
                serialFormatBuf.put((byte)(64+data[i]));
                values++;
            } else
                serialFormatBuf.put(data[i]);
            values++;
        }

        //serialFormatBuf.put((byte)0b00001010); //new line character
        serialFormatBuf.put("\n".getBytes(StandardCharsets.UTF_8)); //new line character
        values++;

        serialFormatBuf.flip();
        if (values == 0)
            return null;
        out = new byte[values];
        serialFormatBuf.get(out);
        Log.d(TAG,"Outgoing(serialFormatBuf): "+StringUtils.toHex(out));
        return out;
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

    private final static byte ESCAPE_BYTE = 0b01000000;

    /**
     * Used to remove special byte escaping done to prevent Pluto from modifying certain bytes
     * when Pluto fails to open stdout in binary mode
     * @param data
     * @return
     */
    private static byte[] separateEscapedCharacters(byte[] data) {
        if (data == null)
            return null;
        int escapeBytes = 0;
        for (int i=0;i<data.length;i++) {
            if (data[i] == ESCAPE_BYTE)
                escapeBytes++;
        }

        if (escapeBytes == 0)
            return data;
        byte[] out = new byte[data.length-escapeBytes];
        int index = 0;
        try {
            for (int i = 0; i < data.length; i++) {
                if (data[i] == ESCAPE_BYTE) {
                    i++;
                    if (i < data.length)
                        out[index] = (byte)(data[i] - ESCAPE_BYTE);
                    else
                        Log.w(TAG,"An escape byte was detected at the end of the data stream; this should not happen but means that the next byte stream received will start with a character that is incorrect");
                } else
                    out[index] = data[i];
                index++;
            }
        } catch (Exception e) {
            Log.e(TAG,"Error removing escaped characters from data stream: "+StringUtils.toHex(data));
        }
        Log.d(TAG,"Removed "+escapeBytes+" escape bytes in "+StringUtils.toHex(data)+" to "+StringUtils.toHex(out));
        return out;
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
                final long start = System.currentTimeMillis();
                if (port == null)
                    return;
                //    ioManager.writeAsync(data);
                nextKeepAliveMessage = System.currentTimeMillis() + TIME_BETWEEN_KEEP_ALIVE_MESSAGES;

                if (!USE_BIN_USB_IN)
                    Log.d(TAG,"Outgoing: "+new String(data,StandardCharsets.UTF_8));

                int bytesWritten = port.write(data,SERIAL_TIMEOUT);
                if (bytesWritten < data.length)
                    sdrConnectionCongestedUntil = System.currentTimeMillis()+TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION;
                long lag = System.currentTimeMillis() - start;
                if (lag > BURST_LAG_WARNING)
                    Log.d(TAG,"WARNING: write lag "+lag+"ms");
            } catch (IOException e) {
                Log.e(TAG,"Unable to write data: "+e.getMessage());
                sdrConnectionCongestedUntil = System.currentTimeMillis()+TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION;
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
                    listener.onPlutoStatus(PlutoStatus.ERROR,"Unable to login after 3 attempts: "+((message==null)?"":message));
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
                        //launchSdrApp();
                        if (listener != null)
                            listener.onPlutoStatus(PlutoStatus.LOGGED_IN,"Already logged in to Pluto");
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
                                listener.onPlutoStatus(PlutoStatus.ERROR,"Unable to login; unknown message received: "+message);
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
                    //launchSdrApp();
                    if (listener != null)
                        listener.onPlutoStatus(PlutoStatus.LOGGED_IN,null);
                    //listener.onSerialConnect();
                } else if (isLoginPasswordError(message)) {
                    status = LoginStatus.WAITING_USERNAME;
                    handler.post(new LoginHelper(null));
                } else if (isLoginErrorMessage(message)) {
                    status = LoginStatus.ERROR;
                    if (listener != null)
                        listener.onPlutoStatus(PlutoStatus.ERROR,message);
                }
            }
        }
    }

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
                    if (sdrAppStatus == SdrAppStatus.RUNNING) {
                        if (processOnPluto) {
                            Log.d(TAG, "From SDR: " + StringUtils.toHex(data) + ":" + new String(data));
                            if (USE_ESC_BYTES)
                                handleRawDatalinkInput(separateEscapedCharacters(data));
                            else
                                handleRawDatalinkInput(data);
                        } else {
                            if (signalProcessor != null)
                                signalProcessor.consumeIqData(data);
                        }
                    } else {
                        if (data.length > 1000)
                            reportAppAsRunning();
                        //Log.d(TAG, "From SDR: " + new String(data, StandardCharsets.UTF_8));
                    }
                }
            });
            return;
        }
        if (status != LoginStatus.LOGGED_IN) {
            if (handler != null)
                handler.postDelayed(new LoginHelper(data), DELAY_FOR_LOGIN_WRITE);
        } else {
            if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE) {
                String message = new String(data,StandardCharsets.UTF_8);
                Log.d(TAG,"From SDR during app check: "+message);
            }
            if (data[0] == HEADER_DEBUG_MESSAGE)
                return;
            if (data[0] == HEADER_DATA_PACKET_OUTGOING)
                return;
            if (handler != null) {
                String value = new String(data,StandardCharsets.UTF_8);
                if (data[0] != HEADER_DEBUG_MESSAGE)
                    handler.post(new SdrAppHelper(value));
            }
        }
    }

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

    private String[] SUCCESS_WORDS = {"Welcome to:","logged","help","v0.3"};
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
            listener.onPlutoStatus(PlutoStatus.ERROR,e.getMessage());
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
                                    listener.onSqandrStatus(SqandrStatus.ERROR,errorMessage);
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
                    listener.onSqandrStatus(SqandrStatus.ERROR,errorMessage);
                return;
            }
            if (input == null) {
                //if (sdrAppStatus == SdrAppStatus.NEED_START)
                //    launchSdrApp();
            } else {
                if (input.length() < 3) //ignore short echo back messages
                    return;
                if (isSdrAppSuccessMessage(input)) {
                    if (USE_BIN_USB_IN) {
                        //Bin IO relies on the SqANDR heartbeat to know when it is connected
                    } else {
                        if (!USE_BIN_USB_OUT) {
                            Log.d(TAG, "SDR companion app is running");
                            reportAppAsRunning();
                        }
                    }
                } else {
                    if (USE_BIN_USB_IN) {
                        //ignore
                    } else {
                        if (sdrAppStatus == SdrAppStatus.RUNNING)
                            Log.d(TAG, "SDR input: " + input);
                    }
                }
            }
        }
    }

    public void startSdrApp() {
        if ((sdrAppStatus == SdrAppStatus.NEED_START) || (sdrAppStatus == SdrAppStatus.OFF) || (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE)) {
            sdrAppStatus = SdrAppStatus.STARTING;
            final String message = "Starting SDR companion app (SqANDR)";
            Log.d(TAG,message);
            attempts = 0;
            setCommandFlags(OPTIMAL_FLAGS); //FIXME for testing
            Log.d(TAG,"Initiating SDR App with command: "+new String(SDR_START_COMMAND,StandardCharsets.UTF_8));
            try {
                port.write(SDR_START_COMMAND,1000);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void reportAppAsRunning() {
        if (listener != null)
            listener.onSqandrStatus(SqandrStatus.RUNNING,null);
        if (sdrAppStatus != SdrAppStatus.RUNNING) {
            Log.d(TAG,"Reporting SDR app is now running");
            sdrAppStatus = SdrAppStatus.RUNNING;
        }
    }

    /**
     * Installs the SqANDR app on the SDR if needed ans starts the app
     */
    public void launchSdrApp() {
        Log.d(TAG, "launchSdrApp()");
        if (sdrAppStatus == SdrAppStatus.OFF) {
            sdrAppStatus = SdrAppStatus.CHECKING_FOR_UPDATE;
            if (listener != null)
                listener.onSqandrStatus(SqandrStatus.PENDING,"Checking if current version of SqANDR is installed...");
        } else if (sdrAppStatus == SdrAppStatus.NEED_START) {
            if (listener != null)
                listener.onSqandrStatus(SqandrStatus.OFF,null);
        } else {
            if (sdrAppStatus != SdrAppStatus.INSTALL_NEEDED)
                return; //already installing, ignore
            if (listener != null)
                listener.onPlutoStatus(PlutoStatus.INSTALLING,"Updating SqANDR...");
            if (context == null) {
                final String errorMessage = "Cannot push the SqANDR app onto the SDR with a null context - this should never happen";
                Log.e(TAG,errorMessage);
                sdrAppStatus = SdrAppStatus.ERROR;
                if (listener != null)
                    listener.onPlutoStatus(PlutoStatus.ERROR,errorMessage);
                return;
            }
            sdrAppStatus = SdrAppStatus.INSTALLING;
            Loader.pushAppToSdr(context, port, new SqANDRLoaderListener() {
                @Override
                public void onSuccess() {
                    sdrAppStatus = SdrAppStatus.NEED_START;
                    if (listener != null) {
                        listener.onPlutoStatus(PlutoStatus.UP,"SqANDR installed");
                        listener.onSqandrStatus(SqandrStatus.OFF,null);
                    }
                }

                @Override
                public void onFailure(String message) {
                    Log.e(TAG,"Error installing SqANDR: "+message);
                    sdrAppStatus = SdrAppStatus.ERROR;
                    if (listener != null) {
                        listener.onPlutoStatus(PlutoStatus.ERROR,"Error installing SqANDR: "+message);
                        listener.onSqandrStatus(SqandrStatus.OFF,null);
                    }
                }

                @Override
                public void onProgressPercent(int percent) {
                    if (listener != null)
                        listener.onPlutoStatus(PlutoStatus.INSTALLING,"Installing SqANDR: "+percent+"%...");
                }
            });
        }
    }

    @Override
    public void onSignalDataExtracted(byte[] data) {
        handleRawDatalinkInput(data);
    }

    @Override
    public void onSignalDataOverflow() {
        Log.w(TAG,"Signal Processor is unable to keep up with data intake");
    }
}