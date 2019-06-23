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

import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

public class SerialConnection extends AbstractDataConnection implements SerialInputOutputManager.Listener {
    private final static String TAG = Config.TAG+".serial";
    private final static int SERIAL_TIMEOUT = 1000;
    private final static long DELAY_FOR_LOGIN_WRITE = 500l;
    private final static long DELAY_FOR_SDR_APP_START = 500l;
    private final static long DELAY_BEFORE_BLIND_LOGIN = 1000l * 5l;
    private UsbDeviceConnection connection;
    private UsbSerialPort port;
    private SerialInputOutputManager ioManager;
    //private SerialListener listener;
    private String username;
    private String password;
    private LoginStatus status = LoginStatus.NEED_CHECK_LOGIN_STATUS;
    private SdrAppStatus sdrAppStatus = SdrAppStatus.NEED_START;
    private HandlerThread handlerThread;
    private Handler handler;
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
    private final static byte HEADER_SHUTDOWN = (byte) HEADER_SHUTDOWN_CHAR; //e
    private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR +"\n").getBytes(StandardCharsets.UTF_8);

    private final static long TIME_BETWEEN_KEEP_ALIVE_MESSAGES = 100l; //TODO
    private long nextKeepAliveMessage = Long.MIN_VALUE;

    enum LoginStatus { NEED_CHECK_LOGIN_STATUS,CHECKING_LOGGED_IN,WAITING_USERNAME, WAITING_PASSWORD, WAITING_CONFIRMATION, ERROR, LOGGED_IN };
    enum SdrAppStatus { NEED_START, STARTING, RUNNING, ERROR, OFF };

    public SerialConnection(String username, String password) {
        this.username = username;
        this.password = password;
        SDR_START_COMMAND = ("/var/tmp/sqandr -tx "+String.format("%.2f", SdrConfig.getTxFreq())+" -rx "+String.format("%.2f",SdrConfig.getTxFreq())+" -minComms\n").getBytes(StandardCharsets.UTF_8);
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
        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if ((manager == null) || (usbDevice == null)) {
            Log.e(TAG, "Cannot open, manager or device are null");
            return;
        }

        if (port != null) {
            Log.d(TAG,"SerialConnection already open, ignoring open call");
            return;
        }
        //if (context instanceof SerialListener)
        //    listener = (SerialListener)context;
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

    private Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            if (sdrAppStatus == SdrAppStatus.RUNNING) {
                if (System.currentTimeMillis() > nextKeepAliveMessage)
                    write(KEEP_ALIVE_MESSAGE);
            }
            if (handler != null)
                handler.postDelayed(this,TIME_BETWEEN_KEEP_ALIVE_MESSAGES);
        }
    };

    //public void setListener(SerialListener listener) { this.listener = listener; }

    public void close() {
        Log.d(TAG,"Closing...");
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }
        if (port != null) {
            try {
                if (sdrAppStatus == SdrAppStatus.RUNNING) {
                    sdrAppStatus = SdrAppStatus.OFF;
                    String formattedData = HEADER_SHUTDOWN_CHAR + "\n";
                    try {
                        port.write(formattedData.getBytes(StandardCharsets.UTF_8), 100);
                    } catch (IOException e) {
                        Log.w(TAG,"Unable to close SDR app before shutting down: "+e.getMessage());
                    }
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
    public void burstPacket(final byte[] data) {
        if (data == null)
            return;
        if (Segment.isAbleToWrapInSingleSegment(data)) {
            Segment segment = new Segment();
            segment.setData(data);
            write(toSerialLinkFormat(segment.toBytes()));
        } else {
            Log.d(TAG,"This packet is larger than the SerialConnection output, segmenting...");
            ArrayList<Segment> segments = Segmenter.wrapIntoSegments(data);
            if ((segments == null) || segments.isEmpty()) {
                Log.e(TAG,"There was an unexpected problem that did not produce any segments from this packet");
                return;
            }
            for (Segment segment:segments) {
                write(toSerialLinkFormat(segment.toBytes()));
            }
        }
    }

    private byte[] toSerialLinkFormat(byte[] data) {
        if (data == null)
            return null;
        String formattedData = HEADER_DATA_PACKET_OUTGOING_CHAR +StringUtils.toHex(data)+"\n";
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
                //    ioManager.writeAsync(data);
                nextKeepAliveMessage = System.currentTimeMillis() + TIME_BETWEEN_KEEP_ALIVE_MESSAGES;

                //TODO testing
                Log.d(TAG,"Outgoing: "+new String(data,StandardCharsets.UTF_8));
                //TODO testing

                port.write(data,SERIAL_TIMEOUT);
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
                        startSdrApp();
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
                    startSdrApp();
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
        if ((data == null) || (data.length < 3))
            return;
        //Log.d(TAG,"onNewData("+data.length+"b): "+new String(data));
        if (status != LoginStatus.LOGGED_IN) {
            if (handler != null)
                handler.postDelayed(new LoginHelper(data), DELAY_FOR_LOGIN_WRITE);
            if (listener != null)
                listener.onReceiveCommandData(data);
                //listener.onConnectionError(new String(data,StandardCharsets.UTF_8));
                //listener.onSerialRead(data);
        } else {
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

    private String[] SUCCESS_WORDS = {"Welcome to:","logged"};
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

    //TODO testing
    //private boolean runOnceTest = true;
    //TODO testing

    private class SdrAppHelper implements Runnable {
        //private byte[] data;
        private String input;

        public SdrAppHelper(String input) {
            this.input = input;
            //Unknown commandLog.d(TAG,"SdrAppHelper received: "+input);
            //Log.d(TAG,"SdrAppHelper received "+((data==null)?"null ":data.length)+"b");
            //this.data = data;
        }

        @Override
        public void run() {
            //TODO testing
            /*if (runOnceTest) {
                runOnceTest = false;
                handleRawDatalinkInput(parseSerialLinkFormat("*6699F0803D5468697320697320612061206D756368206C6F6E676572207061636B657420746861742077696C6C20646566696E6974656C79206E65656420736F6D65206B696E64206F66207365676D656E746174696F6E20616E64207265617373656D626C792E20486F706566756C6C7920736F6D657468696E67206C6F6E6720656E6F7567682074686174206974207265616C6C7920746573747320746865207365676D656E74657220636C6173732E2049206D65616E2049206E656564207468697320746F206265207265616C6C79206C6F6E672C20646566696E6974656C79206C6F6E676572207468616E20746865206F74".getBytes()));
                handleRawDatalinkInput(parseSerialLinkFormat("*6699078103686572206F6E65".getBytes()));
                handleRawDatalinkInput(parseSerialLinkFormat("*669901826D55".getBytes()));
            }*/
            //TODO testing

            /*String message = null;
            if (data != null) {
                if (sdrAppStatus == SdrAppStatus.RUNNING) {
                    switch (data[0]) {
                        case HEADER_DATA_PACKET_INCOMING:
                            Log.d(TAG,"SdrAppHelper - found Data Packet");
                            handleRawDatalinkInput(parseSerialLinkFormat(data));
                            return;

                        case HEADER_SYSTEM_MESSAGE:
                            message = new String(data,StandardCharsets.UTF_8).substring(1)+"\n";
                            break;

                        case HEADER_DEBUG_MESSAGE:
                            Log.d(TAG,"Ignoring debug message: "+new String(data,StandardCharsets.UTF_8).substring(1));
                            return;

                        case HEADER_SHUTDOWN:
                            Log.d(TAG,"The shutdown command to the SDR was received back (this is meaningless for the Android end); ignoring.");
                            return;

                        case HEADER_DATA_PACKET_OUTGOING:
                            Log.d(TAG,"Our own outgoing packet was received back(this is meaningless for the Android end); ignoring.");
                            return;

                        default:
                            message = "Unknown command: "+new String(data,StandardCharsets.UTF_8)+"\n";
                    }
                } else
                    message = new String(data, StandardCharsets.UTF_8);
            }*/
            if (input != null) {
                if (sdrAppStatus == SdrAppStatus.RUNNING) {
                    Log.d(TAG,"From SDR: "+input);
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
                                sdrAppStatus = SdrAppStatus.ERROR;
                                if (listener != null)
                                    listener.onConnectionError("The app on the SDR is having a problem: "+input);
                            } else
                                input = "Unknown command: "+input+"\n";
                    }
                }
            }
            if (attempts > 3) {
                sdrAppStatus = SdrAppStatus.ERROR;
                if (listener != null)
                    listener.onConnectionError("Unable to start app after 3 attempts: "+((input==null)?"":input));
                return;
            }
            if (input == null) {
                if (sdrAppStatus == SdrAppStatus.NEED_START)
                    startSdrApp();
            } else {
                if (input.length() < 3) //ignore short echo back messages
                    return;
                if (isSdrAppSuccessMessage(input)) {
                    CommsLog.log(CommsLog.Entry.Category.SDR,"SDR companion app is running");
                    sdrAppStatus = SdrAppStatus.RUNNING;
                } else {
                    CommsLog.log(CommsLog.Entry.Category.SDR,input);
                    if (listener != null)
                        listener.onReceiveCommandData(input.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
    };

    private void startSdrApp() {
        if (sdrAppStatus == SdrAppStatus.NEED_START) {
            Log.d(TAG,"Starting SDR companion app");
            sdrAppStatus = SdrAppStatus.STARTING;
            attempts = 0;
            CommsLog.log(CommsLog.Entry.Category.SDR,"Initiating SDR App with command: "+new String(SDR_START_COMMAND,StandardCharsets.UTF_8));
            write(SDR_START_COMMAND);
        }
    }
}
