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

import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.rf.SignalConverter;
import org.sofwerx.sqan.rf.SignalProcessingListener;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.rf.SignalProcessor;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.util.ContinuityGapSAR;
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

public class SerialConnection extends AbstractDataConnection implements SerialInputOutputManager.Listener, SignalProcessingListener {
    private final static String TAG = Config.TAG+".Serial";
    private final static boolean USE_LEAN_MODE = false; //TODO in development, Lean mode - sqandr provides raw data and uses the most efficient data structure; binIn,binOut, and tx/rx buffer sizes are set by SqANDR
    private final static boolean USE_BIN_USB_IN = true; //send binary input to Pluto
    private final static boolean USE_BIN_USB_OUT = true; //use binary output from Pluto
    private final static boolean PROCESS_ON_PLUTO = true; //true == signals processed on Pluto and bytes provided as output; false == raw IQ values provided by Pluto
    private final static float SAMPLE_RATE = 3.2f; //MiS/s - must be between 6.0 and 0.6 inclusive. 0.7 is min that will support streaming mdeia
    //private final static float SAMPLE_RATE = 1.0f; //MiS/s - must be between 6.0 and 0.6 inclusive. 0.7 is min that will support streaming media
    private final static int RX_BUFFER_SIZE = 17284;
    private final static int TX_BUFFER_SIZE = 17284;
    private final static int PERCENT_OF_LAST_AMPLITUDE = 5;
    private final static int MESSAGE_REPEAT = 1;
    private final static long MAX_CYCLE_TIME = (long) (1f/(SAMPLE_RATE * 1000f/RX_BUFFER_SIZE))+2l; //what is the max number of ms between cycles before data is lost

    private final static String OPTIMAL_FLAGS = "-txSize "+TX_BUFFER_SIZE+" -rxSize "+RX_BUFFER_SIZE+" -messageRepeat "+MESSAGE_REPEAT+" -rxsrate "+SAMPLE_RATE+" -txsrate "+SAMPLE_RATE+" -txbandwidth 2.3 -rxbandwidth 2.3 -perLast "+PERCENT_OF_LAST_AMPLITUDE+" -noHeader"+((SAMPLE_RATE<3.2f)?" -fir":"");
    private final static int MAX_BYTES_PER_SEND = AbstractDataConnection.USE_GAP_STRATEGY?220:252;
    private final static int SERIAL_TIMEOUT = 100;
    private final static long DELAY_FOR_LOGIN_WRITE = 500l;
    private final static long DELAY_BEFORE_BLIND_LOGIN = 1000l * 5l;
    private final static byte ESCAPE_BYTE = 0b01000000;
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
    private final static byte[] SHUTDOWN_BYTES = {(byte)0b00010000,(byte)0b00010000,(byte)0b00010000,(byte)0b00010000};
    private final static byte[] NO_DATA_HEARTBEAT = {(byte)0b00000001,(byte)0b00000010,(byte)0b00000011,(byte)0b00000100};
    private final static byte LESS_THAN_32 = 0b00011111;
    private final static byte CHAR_128 = (byte)0b10000000;
    private final static byte CHAR_127 = (byte)0b01111111;
    private final static byte CHAR_255 = (byte)0b11111111;
    private final static byte CHAR_64 = (byte)0b01000000;
    private final static byte HEADER_SHUTDOWN = (byte) HEADER_SHUTDOWN_CHAR; //e
    private final static byte HEADER_BUSYBOX = (byte)'b';
    private final static boolean USE_ESC_BYTES = true;

    private final static boolean CONCAT_SEGMENT_BURSTS = true; //true == try to send as many segments as possible to the SDR on each write

    public final static int TX_GAIN = 10; //Magnitude in (0 to 85dB)

    private final static long TIME_BETWEEN_KEEP_ALIVE_MESSAGES = 7l; //adjust as needed
    private long nextKeepAliveMessage = Long.MIN_VALUE;
    private final static long SQANDR_HEARTBEAT_STALE_TIME = 1000l * 5l;

    private final static long TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION = 200l; //ms to wait if the USB is having problems sending all its data

    enum LoginStatus { NEED_CHECK_LOGIN_STATUS,CHECKING_LOGGED_IN,WAITING_USERNAME, WAITING_PASSWORD, WAITING_CONFIRMATION, ERROR, LOGGED_IN }
    enum SdrAppStatus { OFF, CHECKING_FOR_UPDATE, INSTALL_NEEDED,INSTALLING, NEED_START, STARTING, RUNNING, ERROR }

    private ByteBuffer serialFormatBuf = ByteBuffer.allocate(MAX_BYTES_PER_SEND*4);
	private SignalProcessor signalProcessor;
	private long lastCycleTime = Long.MAX_VALUE;

	private final static long TIME_TO_CHECK_FOR_ECHO = 5l; //if something is received under this time since our last transmission, check to see if its an echo and ignore it
	private long echoSentTime = Long.MIN_VALUE;
    private byte[] lastSentData;

    private final static long BURST_LAG_WARNING = 1l;

    public SerialConnection(String username, String password) {
        this.username = username;
        this.password = password;
		if (!PROCESS_ON_PLUTO)
            signalProcessor = new SignalProcessor(this,USE_LEAN_MODE);

        SDR_START_COMMAND = (Loader.SDR_APP_LOCATION+Loader.SQANDR_VERSION
                +" -tx "+String.format("%.2f", SdrConfig.getTxFreq())
                +" -rx "+String.format("%.2f",SdrConfig.getRxFreq())
                +(USE_BIN_USB_IN ?" -binI":"")
                +(USE_BIN_USB_OUT ?" -binO":"")
                + ((PROCESS_ON_PLUTO || !USE_BIN_USB_OUT) ? "" : " -rawOut")
                + " " + OPTIMAL_FLAGS
                +"\n").getBytes(StandardCharsets.UTF_8);
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
            if (USE_BIN_USB_OUT && PROCESS_ON_PLUTO) {
                if (lastSqandrHeartbeat > 0l) {
                    if (System.currentTimeMillis() > (lastSqandrHeartbeat + SQANDR_HEARTBEAT_STALE_TIME)) {
                        if (sdrAppStatus != SdrAppStatus.OFF) {
                            sdrAppStatus = SdrAppStatus.OFF;
                        //if (sdrAppStatus != SdrAppStatus.ERROR) {
                            Log.w(TAG,"SqANDR app appears to be offline");
                        //    sdrAppStatus = SdrAppStatus.ERROR;
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
        if (gapSar == null) {
            gapSar.close();
            gapSar = null;
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
            ManetOps.addBytesToTransmittedTally(data.length);
            final byte[] cipherData = Crypto.encrypt(data);
            if (Segment.isAbleToWrapInSingleSegment(cipherData)) {
                Segment segment = new Segment();
                segment.setData(cipherData);
                segment.setStandAlone();
                if (USE_BIN_USB_IN) {
                    byte[] outgoingBytes = segment.toBytes();
                    Log.d(TAG,"Outgoing (burst, standalone): *"+StringUtils.toHex(outgoingBytes));

                    //FIXME for testing
                    //outgoingBytes = StringUtils.toByteArray("00112233445566778899AABBCCDDEEFFFFEEDDCCBBAA9988776655443322110000112233445566778899AABBCCDDEEFFFFEEDDCCBBAA99887766554433221100");
                    //FIXME for testing

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
    private static byte[] toSerialLinkFormat(byte[] data) {
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
        if (USE_GAP_STRATEGY && (gapSar != null))
            data = ContinuityGapSAR.formatForOutput(data);
        int values = 0;
        serialFormatBuf.clear();

        //Look for illegal byte values and then provide an escaped value and marking. Specifically,
        //anything below 32 is considered illegal. 64 is used as a marker that the next byte
        //has been altered from an illegal byte value. For consistency, marking illegal bytes
        //is done by flipping the 64 bit. If 64 is provided as the value, then it is sent as
        //64 then 128. This is a work-around to Pluto intercepting some byte values (like 13,
        //which is ASCII for carriage return) and then altering the stream in response to those values
        if (USE_LEAN_MODE)
            serialFormatBuf.put(SignalConverter.SQAN_HEADER);
        for (int i=0;i<data.length;i++) {
            if (((data[i] & LESS_THAN_32) == data[i]) || (data[i] == CHAR_64) || (data[i] == CHAR_127)) {
                serialFormatBuf.put(CHAR_64);
                serialFormatBuf.put((byte)(data[i]^CHAR_255));
                values++;
            } else
                serialFormatBuf.put(data[i]);
            values++;
        }

        serialFormatBuf.put((byte)0b00001010); //new line character
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
        boolean escNext = false;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == ESCAPE_BYTE)
                escNext = true;
            else {
                if (escNext) {
                    out[index] = (byte)(data[i] ^ CHAR_255);
                    escNext = false;
                } else
                    out[index] = data[i];
                index++;
            }
        }
        //Log.d(TAG,"Removed "+escapeBytes+" escape bytes in "+StringUtils.toHex(data)+" to "+StringUtils.toHex(out));
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
        echoSentTime = System.currentTimeMillis() + TIME_TO_CHECK_FOR_ECHO;
        lastSentData = data;
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
                        Log.d(TAG, ((heartbeatReportedReceived == 255)?">254":heartbeatReportedReceived) + "b transmitted by SqANDR app");
                    //else
                    //    Log.d(TAG,"Heartbeat received from SqANDR app");
                    reportAppAsRunning();
                    lastSqandrHeartbeat = System.currentTimeMillis();
                    if (lastCycleTime < System.currentTimeMillis()) {
                        long cycledTime = System.currentTimeMillis() - lastCycleTime;
                        if (cycledTime > MAX_CYCLE_TIME)
                            Log.w(TAG, "Pluto is cycling slower (" + (System.currentTimeMillis() - lastCycleTime)+ "ms) than required ("+MAX_CYCLE_TIME+"ms), data is being lost");
                    }
                    lastCycleTime = System.currentTimeMillis();
                } else {
                    if (sdrAppStatus == SdrAppStatus.RUNNING) {
                        if (PROCESS_ON_PLUTO) {
                            boolean isEcho = false;
                            if (System.currentTimeMillis() < echoSentTime) {
                                if (lastSentData != null) {
                                    if (lastSentData.length == data.length-1) {
                                        isEcho = true;
                                        for (int i=0;i<lastSentData.length-1;i++) {
                                            if (lastSentData[i] != data[i])
                                                isEcho = false;
                                        }
                                    }
                                }
                            }
                            if (isEcho)
                                Log.d(TAG, "From SDR (echo): " + StringUtils.toHex(data));
                            else {
                                if (data.length > 10) //FIXME for testing
                                    Log.d(TAG, "From SDR: " + StringUtils.toHex(data));
                                if (USE_ESC_BYTES) {
                                    byte[] processData = separateEscapedCharacters(data);
                                    handleRawDatalinkInput(processData);
                                } else {
                                    handleRawDatalinkInput(data);
                                }
                            }
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
                private int lastReportedPercent = 0;
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
                    if (lastReportedPercent != percent) {
                        lastReportedPercent = percent;
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("Installing SqANDR: " + percent + "%...");
                    }
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