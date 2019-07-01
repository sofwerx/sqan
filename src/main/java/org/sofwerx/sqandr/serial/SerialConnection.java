package org.sofwerx.sqandr.serial;

import jssc.SerialPort;
import jssc.SerialPortException;
import jssc.SerialPortList;
import org.sofwerx.pisqan.Log;

import org.sofwerx.sqandr.sdr.AbstractDataConnection;

import org.sofwerx.pisqan.Config;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.util.Loader;
import org.sofwerx.sqandr.util.SqANDRLoaderListener;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;


import static java.lang.Thread.sleep;
import static java.util.concurrent.Executors.newCachedThreadPool;

public class SerialConnection extends AbstractDataConnection implements SerialInputOutputManager.Listener {
    private final static String TAG = Config.TAG + ".serial";
    private final static int SERIAL_TIMEOUT = 1000;
    private final static long DELAY_FOR_LOGIN_WRITE = 500l;
    private final static long DELAY_BEFORE_BLIND_LOGIN = 1000l * 5l;

    public static final Charset UTF8 = StandardCharsets.UTF_8;

    //    private UsbDeviceConnection connection;
    public SerialPort port = null;
    private SerialInputOutputManager ioManager;
    //    private SerialListener listener;
    private LoginStatus status = LoginStatus.NEED_CHECK_LOGIN_STATUS;
    private SdrAppStatus sdrAppStatus = SdrAppStatus.OFF;
    private ScheduledExecutorService handlerSched;
    private ExecutorService handler;
    //    private Context context;
    private byte[] SDR_START_COMMAND;
    private final static char HEADER_DATA_PACKET_OUTGOING_CHAR = '*';
    private final static byte HEADER_DATA_PACKET_OUTGOING = (byte) HEADER_DATA_PACKET_OUTGOING_CHAR;
    private final static char HEADER_DATA_PACKET_INCOMING_CHAR = '+';
    private final static byte HEADER_DATA_PACKET_INCOMING = (byte) HEADER_DATA_PACKET_INCOMING_CHAR; //+
    private final static char HEADER_SYSTEM_MESSAGE_CHAR = 'm';
    private final static byte HEADER_SYSTEM_MESSAGE = (byte) HEADER_SYSTEM_MESSAGE_CHAR;
    private final static byte HEADER_SEND_COMMAND = (byte) 99; //c
    private final static byte HEADER_DEBUG_MESSAGE = (byte) 100; //d
    private final static char HEADER_SHUTDOWN_CHAR = 'e'; //e
    private final static byte HEADER_SHUTDOWN = (byte) HEADER_SHUTDOWN_CHAR; //e
    private final static byte HEADER_BUSYBOX = (byte) 'b';
    private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "\n").getBytes(UTF8);
    //private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "00" +"\n").getBytes(UTF8);
    //private final static byte[] KEEP_ALIVE_MESSAGE = (HEADER_DATA_PACKET_OUTGOING_CHAR + "00112233445566778899aabbccddeeff" +"\n").getBytes(UTF8);

    public final static byte ETX = 0x03;
    public final static byte EOT = 0x04;
    public static final byte LF = 0x12;


    private final static long TIME_BETWEEN_KEEP_ALIVE_MESSAGES = 75l; //adjust as needed
    private long nextKeepAliveMessage = Long.MIN_VALUE;

    private final static long TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION = 200l; //ms to wait if the USB is having problems sending all its data

    enum LoginStatus {NEED_CHECK_LOGIN_STATUS, CHECKING_LOGGED_IN, WAITING_USERNAME, WAITING_PASSWORD, WAITING_CONFIRMATION, ERROR, LOGGED_IN}

    enum SdrAppStatus {OFF, CHECKING_FOR_UPDATE, INSTALL_NEEDED, INSTALLING, NEED_START, STARTING, RUNNING, ERROR}

    public SerialConnection() {
        SDR_START_COMMAND = (Loader.SDR_APP_LOCATION + Loader.SQANDR_VERSION
                + " -tx " + String.format("%.2f", SdrConfig.getTxFreq())
                + " -rx " + String.format("%.2f", SdrConfig.getRxFreq())
                + " -txgain 10"
                + " -listen"
//                + " -minComms"
                + "\n"
        ).getBytes(UTF8);

        handlerSched = new ScheduledThreadPoolExecutor(Config.CORE_POOL_SIZE);

        handler = newCachedThreadPool();
    }


    public void open() {
        Runnable initRunnable = () -> {
            String pattern = Config.USE_TTY_PATTERN;
            if (pattern == null || pattern == "") {
                switch (System.getProperty("os.name")) {
                    case Config.OSX_OS_NAME:
                        pattern = Config.OSX_TTY_PATTERN;
                        break;
                    case Config.LINUX_OS_NAME:
                        pattern = Config.LINUX_TTY_PATTERN;
                        break;
                    default:
                        pattern = Config.LINUX_TTY_PATTERN;
                }
            }

            String sdrPort;
            try {
                Pattern sdrDevMatch = Pattern.compile(pattern);
                String[] portList = SerialPortList.getPortNames(sdrDevMatch);
                if (portList.length > 0) {
                    sdrPort = portList[0];
                } else {
                    Log.e(TAG, "No ports found matching '" + pattern + "'");
                    return;
                }

                port = new SerialPort(sdrPort);
                if (!port.openPort()) {
                    Log.e(TAG, "Could not open " + sdrPort + "\n" + port.toString());
                    return;
                }
                port.setParams(115200, SerialPort.DATABITS_8, SerialPort.STOPBITS_1, SerialPort.PARITY_NONE);
                port.setDTR(true);
                port.setRTS(true);

                Log.d(TAG, "Opened " + port.getPortName());

                ioManager = new SerialInputOutputManager(port, this);

                Executors.newSingleThreadExecutor().submit(ioManager);
                Log.d(TAG, "Serial Connection thread started");
            } catch (Exception e) {
                Log.e(TAG, "Unable to read: " + e.getMessage());
                try {
                    port.closePort();
                } catch (Exception e2) {
                    Log.e(TAG, "Unable to close port after error: " + e2.getMessage());
                }
            }
            writeAttnSeq(port);
        };

        if (handler == null) {
            initRunnable.run();
        } else {
            handler.submit(initRunnable);
        }
        if (handlerSched != null) {
            handlerSched.schedule(new LoginHelper(null), DELAY_BEFORE_BLIND_LOGIN, TimeUnit.MILLISECONDS);
            handlerSched.schedule(periodicHelper, DELAY_BEFORE_BLIND_LOGIN, TimeUnit.MILLISECONDS);
        }
    }


    private Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            if (sdrAppStatus == SdrAppStatus.RUNNING) {
                if (!isSdrConnectionCongested() && (System.currentTimeMillis() > nextKeepAliveMessage))
                    try {
                        port.writeBytes(KEEP_ALIVE_MESSAGE);
                    } catch (Exception e) {
                        Log.w(TAG, "Could not write keep alive message: " + e.getMessage());
                    }
            }

            if (handlerSched != null) {
                handlerSched.schedule(this, TIME_BETWEEN_KEEP_ALIVE_MESSAGES, TimeUnit.SECONDS);
            }
        }
    };


    public void close() {
        Log.d(TAG, "Closing...");
        if (ioManager != null) {
            ioManager.setListener(null);
            ioManager.stop();
            ioManager = null;
        }

        if (port != null) {
            try {
                try {
                    String fmtStr = HEADER_SHUTDOWN_CHAR + "\n";
                    port.writeBytes(fmtStr.getBytes(UTF8));
                    sdrAppStatus = SdrAppStatus.OFF;
                } catch (Exception e) {
                    Log.w(TAG, "Unable to close SDR app before shutting down: " + e.getMessage());
                }
                port.setDTR(false);
                port.setRTS(false);
            } catch (Exception ignored) {
            }

            try {
                port.closePort();
            } catch (Exception e) {
                Log.w(TAG, "Could not close port: " + e.getMessage());
            }
            port = null;
        }
    }


    @Override
    public boolean isActive() {
        return (port != null);
    }


    /**
     * Burst adds any wrapping needed to communicate the data and then conducts
     * a write
     *
     * @param data
     */
    @Override
    public void burstPacket(final byte[] data) {
        if (data == null)
            return;
        if (sdrAppStatus == SdrAppStatus.RUNNING) {
            if (Segment.isAbleToWrapInSingleSegment(data)) {
                Segment segment = new Segment();
                segment.setData(data);
                segment.setStandAlone();
                //Log.d(TAG, "Outgoing: " + new String(toSerialLinkFormat(segment.toBytes()), UTF8)); //FIXME for testing only
                write(toSerialLinkFormat(segment.toBytes()));
            } else {
                Log.d(TAG, "This packet is larger than the SerialConnection output, segmenting...");
                ArrayList<Segment> segments = Segmenter.wrapIntoSegments(data);
                if ((segments == null) || segments.isEmpty()) {
                    Log.e(TAG, "There was an unexpected problem that did not produce any segments from this packet");
                    return;
                }
                for (Segment segment : segments) {
                    write(toSerialLinkFormat(segment.toBytes()));
                }
            }
        } else
            Log.d(TAG, "Dropping " + data.length + "b packet as SqANDR is not yet running on the SDR");
    }

    private final static String PADDING_BYTE = "00112233445566778899";
    //private final static String PADDING_BYTE = "";

    private byte[] toSerialLinkFormat(byte[] data) {
        if (data == null)
            return null;
        String formattedData = HEADER_DATA_PACKET_OUTGOING_CHAR + PADDING_BYTE + StringUtils.toHex(data) + "\n";
        return formattedData.getBytes(UTF8);
    }


    /**
     * Converts from the format sent over the serial connection into the actual byte array
     *
     * @param raw
     * @return
     */
    private byte[] parseSerialLinkFormat(byte[] raw) {
        if ((raw == null) || (raw.length < 3))
            return null;
        return parseSerialLinkFormat(new String(raw, UTF8));
    }

    private byte[] parseSerialLinkFormat(String raw) {
        if ((raw == null) || (raw.length() < 3))
            return null;
        char[] chars = raw.toCharArray();
        if (chars.length % 2 == 1) {
            int j = 1;
            byte[] out = new byte[(chars.length - 1) / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = StringUtils.toByte(chars[j], chars[j + 1]);
                j += 2;
            }
            Log.d(TAG, "parseSerialLinkFormat parsed " + out.length + "b");
            return out;
        } else
            Log.e(TAG, "Received data is not the right length: " + raw);
        return null;
    }

    /**
     * Write sends raw bytes to the connection as opposed to burstPacket() which adds
     * any wrapping needed to communicate the data
     *
     * @param data
     */
    public void write(final byte[] data) {
        if (data == null)
            return;
        //if ((port == null) || (ioManager == null)) {
        if (port == null) {
            Log.e(TAG, "Unable to write data - serial port not open");
            return;
        }

        handler.submit(() -> {
            try {
                //    ioManager.writeAsync(data);
                nextKeepAliveMessage = System.currentTimeMillis() + TIME_BETWEEN_KEEP_ALIVE_MESSAGES;

                //TODO testing
                if ((data != null) && (data.length > 40))
                    Log.d(TAG, "Outgoing: " + new String(data, UTF8));
                //TODO testing

                try {
                    port.writeBytes(data);
                } catch (Exception e) {
                    Log.w(TAG, "Unable to write data: " + e.getMessage());
                }
                if (port.getOutputBufferBytesCount() <= 0) {
                    sdrConnectionCongestedUntil = System.currentTimeMillis() + TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION;
                }
            } catch (Exception e) {
                Log.w(TAG, "Problem writing data: " + e.getMessage());
            }
        });
    }

    private void writeAttnSeq(SerialPort port) {
        byte[] attnSeq = {ETX, LF, EOT, LF};
        try {
            port.writeBytes(attnSeq);
        }
        catch (Exception  e) {
            Log.w(TAG, "Problem writing to port: " + e.getMessage());
        }
    }


    private int attempts = 0;

    private class LoginHelper implements Runnable {
        private byte[] data;
        String username = Config.TERMINAL_USERNAME;
        String password = Config.TERMINAL_PASSWORD;

        public LoginHelper(byte[] data) {
            this.data = data;
        }

        @Override
        public void run() {
            final String message;
            if (data == null)
                message = null;
            else
                message = new String(data, UTF8);
            if (attempts > 3) {
                status = LoginStatus.ERROR;
                if (listener != null)
                    listener.onConnectionError("Unable to login after 3 attempts: " + ((message == null) ? "" : message));
                //    listener.onSerialError(new Exception("Unable to login after 3 attempts: "+((message==null)?"":message)));
                return;
            }
            if (message == null) {
                if (status == LoginStatus.NEED_CHECK_LOGIN_STATUS) {
                    Log.d(TAG, "No login prompt received, checking to see if already logged-in...");
                    write(("help\n").getBytes(UTF8));
                    status = LoginStatus.CHECKING_LOGGED_IN;
                    handlerSched.schedule(new LoginHelper(null), DELAY_BEFORE_BLIND_LOGIN, TimeUnit.MILLISECONDS);
                } else if (((status == LoginStatus.WAITING_USERNAME) || (status == LoginStatus.CHECKING_LOGGED_IN)) && (username != null)) {
                    Log.d(TAG, "No login prompt received, providing username anyway...");
                    writeAttnSeq(port);
                    write((username + "\n").getBytes(UTF8));
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
                        new SdrAppHelper(null).launchSdrApp();
                        if (listener != null)
                            listener.onConnect();
                        //listener.onSerialConnect();
                        return;
                    } else {
                        if (isLoginPasswordRequested(message)) {
                            //we're not logged in so we'll provide a junk password and try again
                            handler.submit(() -> write("\n".getBytes(UTF8)));
                            status = LoginStatus.WAITING_PASSWORD;
                        } else {
                            if (isLoginPasswordError(message)) {
                                Log.d(TAG, "SDR rejected our login attempt");
                                handler.submit(new LoginHelper(message.getBytes(UTF8)));
                                status = LoginStatus.WAITING_USERNAME;
                                return;
                            }
                            //don't know what the terminal is asking, so throw an error
                            status = LoginStatus.ERROR;
                            if (listener != null)
                                listener.onConnectionError("Unable to login; unknown message received: " + message);
                            //listener.onSerialError(new Exception("Unable to login; unknown message received: "+message));
                        }
                    }
                }
                if (((status == LoginStatus.NEED_CHECK_LOGIN_STATUS) || (status == LoginStatus.WAITING_USERNAME))
                        && (username != null) && isLoginRequested(message)) {
                    Log.d(TAG, "Providing username...");
                    write((username + "\n").getBytes(UTF8));
                    attempts++;
                    status = LoginStatus.WAITING_PASSWORD;
                } else if ((status == LoginStatus.WAITING_PASSWORD) && (password != null) && isLoginPasswordRequested(message)) {
                    Log.d(TAG, "Providing password...");
                    write((password + "\n").getBytes(UTF8));
                    status = LoginStatus.WAITING_CONFIRMATION;
                } else if (isLoginSuccessMessage(message)) {
                    Log.d(TAG, "Terminal login successful");
                    status = LoginStatus.LOGGED_IN;
                    new SdrAppHelper(null).launchSdrApp();
                    if (listener != null)
                        listener.onConnect();
                    //listener.onSerialConnect();
                } else if (isLoginPasswordError(message)) {
                    status = LoginStatus.WAITING_USERNAME;
                    handler.submit(new LoginHelper(null));
                } else if (isLoginErrorMessage(message)) {
                    status = LoginStatus.ERROR;
                    if (listener != null)
                        listener.onConnectionError(message);
                    //listener.onSerialError(new Exception(message));
                }
            }
        }
    }




    public void onNewData(byte[] data) {
        if ((data == null) || (data.length < 3))
            return;
        Log.d(TAG,"onNewData("+data.length+"b): "+new String(data));
        if (status != LoginStatus.LOGGED_IN) {
            if (handler != null)
                handlerSched.schedule(new LoginHelper(data), DELAY_FOR_LOGIN_WRITE, TimeUnit.MILLISECONDS);
            if (listener != null)
                listener.onReceiveCommandData(data);
            //listener.onConnectionError(new String(data,UTF8));
            //listener.onSerialRead(data);
        } else {
            if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE) {
                String message = new String(data, UTF8);
                if (message.contains("messages")) { //messages also occurs in the /var/tmp folder
                    Log.d(TAG, "Reply \"" + message + "\" from the SDR should contain the contents of the " + Loader.SDR_APP_LOCATION + " directory");
                    if (message.contains(Loader.SQANDR_VERSION)) {
                        Log.d(TAG, Loader.SQANDR_VERSION + " found, start needed");
//                        if (peripheralStatusListener != null)
//                            peripheralStatusListener.onPeripheralMessage("Current version of SqANDR found, starting...");
                        sdrAppStatus = SdrAppStatus.NEED_START;
                    } else {
                        Log.d(TAG, Loader.SQANDR_VERSION + " not found, update needed");
//                        if (peripheralStatusListener != null)
//                            peripheralStatusListener.onPeripheralMessage("SqANDR update needed...");
                        sdrAppStatus = SdrAppStatus.INSTALL_NEEDED;
                    }
                    new SdrAppHelper(null).launchSdrApp();  // this might be the wrong way to call this...
                }
            }
            if (data[0] == HEADER_DEBUG_MESSAGE)
                return;
            if (data[0] == HEADER_DATA_PACKET_OUTGOING)
                return;
            if (handler != null) {
                String value = new String(data, UTF8);
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
                    handler.submit(new SdrAppHelper(value));
                //}
            }
        }
    }


    /*private boolean isDatalinkData(byte[] data) {
        if ((data == null) || (data.length < 3))
            return false;
        return data[0] == HEADER_DATA_PACKET_INCOMING;
    }*/

    public boolean isTerminalLoggedIn() {
        return status == LoginStatus.LOGGED_IN;
    }

    private String[] LOGIN_TEST_PASSED_WORDS = {"Built-in"};

    private boolean isLoggedInAlready(String message) {
        if (message != null) {
            for (String word : LOGIN_TEST_PASSED_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }


    private String[] LOGIN_ERROR_WORDS = {"incorrect"};

    private boolean isLoginPasswordError(String message) {
        if (message != null) {
            for (String word : LOGIN_ERROR_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] LOGIN_WORDS = {"login"};

    private boolean isLoginRequested(String message) {
        if (message != null) {
            for (String word : LOGIN_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] PASSWORD_REQUEST_WORDS = {"password", "Password"};

    private boolean isLoginPasswordRequested(String message) {
        if (message != null) {
            for (String word : PASSWORD_REQUEST_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] ERROR_WORDS = {"error", "cannot", "unable"};

    private boolean isLoginErrorMessage(String message) {
        if (message != null) {
            for (String word : ERROR_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] SUCCESS_WORDS = {"Welcome to:", "logged", "help"};

    private boolean isLoginSuccessMessage(String message) {
        if (message != null) {
            for (String word : SUCCESS_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    private String[] SDR_APP_WORDS = {"Starting"};

    private boolean isSdrAppSuccessMessage(String message) {
        if (message != null) {
            for (String word : SDR_APP_WORDS) {
                if (message.contains(word))
                    return true;
            }
        }
        return false;
    }

    public void onRunError(Exception e) {
        Log.e(TAG, "onRunError()");
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
                    Log.d(TAG, "From SDR: " + input);
                    switch (input.charAt(0)) {
                        case HEADER_DATA_PACKET_INCOMING_CHAR:
                            Log.d(TAG, "SdrAppHelper - found Data Packet");
                            handleRawDatalinkInput(parseSerialLinkFormat(input));
                            return;

                        case HEADER_SYSTEM_MESSAGE_CHAR:
                            input = input.substring(1) + "\n";
                            break;

                        case HEADER_DEBUG_MESSAGE:
                            Log.d(TAG, "Ignoring debug message: " + input.substring(1));
                            return;

                        case HEADER_SHUTDOWN:
                            Log.d(TAG, "The shutdown command to the SDR was received back (this is meaningless for the Android end); ignoring.");
                            return;

                        case HEADER_DATA_PACKET_OUTGOING:
                            Log.d(TAG, "Our own outgoing packet was received back(this is meaningless for the Android end); ignoring.");
                            return;

                        default:
                            if (input.contains("-sh")) {
                                final String errorMessage = "The app on the SDR is having a problem: " + input;
                                sdrAppStatus = SdrAppStatus.ERROR;
                                if (listener != null)
                                    listener.onConnectionError(errorMessage);
//                                if (peripheralStatusListener != null)
//                                    peripheralStatusListener.onPeripheralError(errorMessage);
                            } else
                                input = "Unknown command: " + input + "\n";
                    }
                } else if (sdrAppStatus == SdrAppStatus.INSTALLING) {
                    /*Log.d(TAG,"From SDR (app installing): "+input);
                    switch (input.charAt(0)) {
                        case HEADER_BUSYBOX: //ignore calls to busybox
                            return;
                    }*/
                    return; //ignore all messages during install
                }
            }
            if (attempts > 3) {
                sdrAppStatus = SdrAppStatus.ERROR;
                final String errorMessage = "Unable to start app after 3 attempts: " + ((input == null) ? "" : input);
                if (listener != null)
                    listener.onConnectionError(errorMessage);
//                if (peripheralStatusListener != null)
//                    peripheralStatusListener.onPeripheralError(errorMessage);
                return;
            }
            if (input == null) {
                if (sdrAppStatus == SdrAppStatus.NEED_START)
                    launchSdrApp();
            } else {
                if (input.length() < 3) //ignore short echo back messages
                    return;
                if (isSdrAppSuccessMessage(input)) {
//                    CommsLog.log(CommsLog.Entry.Category.SDR,"SDR companion app is running");
                    sdrAppStatus = SdrAppStatus.RUNNING;
//                    if (peripheralStatusListener != null) {
//                        Log.d(TAG, "onPeripheralReady()");
//                        peripheralStatusListener.onPeripheralReady();
//                    }
//                } else {
//                    CommsLog.log(CommsLog.Entry.Category.SDR,"SDR input: "+input);
                    if (listener != null)
                        listener.onReceiveCommandData(input.getBytes(UTF8));
//                }
                }
            }
        }

//    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
//        super.setPeripheralStatusListener(listener);
//        if (listener != null) {
//            switch (sdrAppStatus) {
//                case OFF:
//                case STARTING:
//                case NEED_START:
//                    listener.onPeripheralMessage("SDR is getting ready...");
//                    break;
//
//                case INSTALLING:
//                case INSTALL_NEEDED:
//                case CHECKING_FOR_UPDATE:
//                    listener.onPeripheralMessage("SDR is checking for updated software...");
//                    break;
//
//                case RUNNING:
//                    Log.d(TAG,"onPeripheralReady()");
//                    listener.onPeripheralReady();
//                    break;
//
//                default:
//                    listener.onPeripheralError("SDR has an error");
//                    break;
//            }
//        }
//    }

        private void startSdrApp() {
            if (sdrAppStatus == SdrAppStatus.NEED_START) {
                sdrAppStatus = SdrAppStatus.STARTING;
                final String message = "Starting SDR companion app";
                Log.d(TAG, message);
//            if (peripheralStatusListener != null)
//                peripheralStatusListener.onPeripheralMessage(message);
                attempts = 0;
//            CommsLog.log(CommsLog.Entry.Category.SDR,"Initiating SDR App with command: "+new String(SDR_START_COMMAND,UTF8));
                write(SDR_START_COMMAND);
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
                sdrAppStatus = SdrAppStatus.NEED_START;
//            sdrAppStatus = SdrAppStatus.CHECKING_FOR_UPDATE;
//            if (peripheralStatusListener != null)
//                peripheralStatusListener.onPeripheralMessage("Checking if current version of SqANDR is installed...");
//            Loader.queryIsCurrentSqANDRInstalled(port);
//        } elseif (sdrAppStatus == SdrAppStatus.NEED_START)
            }
            if (sdrAppStatus == SdrAppStatus.NEED_START) {
                startSdrApp();
            } else {
                if (sdrAppStatus != SdrAppStatus.INSTALL_NEEDED)
                    return; //already installing, ignore
//            if (peripheralStatusListener != null)
//                peripheralStatusListener.onPeripheralMessage("Updating SqANDR...");
                sdrAppStatus = SdrAppStatus.INSTALLING;
//            if (context == null) {
                final String errorMessage = "Cannot push the SqANDR app onto the SDR with a null context - this should never happen";
                Log.e(TAG, errorMessage);
                sdrAppStatus = SdrAppStatus.ERROR;
                if (listener != null)
                    listener.onConnectionError(errorMessage);
//                if (peripheralStatusListener != null)
//                    peripheralStatusListener.onPeripheralError(errorMessage);
                return;
//            }
//            Loader.pushAppToSdr(context, port, new SqANDRLoaderListener() {
//                @Override
//                public void onSuccess() {
//                    sdrAppStatus = SdrAppStatus.NEED_START;
////                    if (peripheralStatusListener != null)
////                        peripheralStatusListener.onPeripheralMessage("SqANDR updated.");
//                    startSdrApp();
//                }

//                @Override
//                public void onFailure(String message) {
//                    Log.e(TAG,"Error installing SqANDR: "+message);
//                    sdrAppStatus = SdrAppStatus.ERROR;
//                    if (listener != null)
//                        listener.onConnectionError(message);
////                    if (peripheralStatusListener != null)
////                        peripheralStatusListener.onPeripheralError(message);
//                }
            }
        }
    }
}

//
//        String instr, outstr, sdrPort;
//

//
//            instr = null;
//            Log.d(TAG, "Looking for login:");
//
//            while (((instr = port.readString()) == null) || (!instr.endsWith("login: "))) {
//                if (instr == null) {
//                    System.out.print(".");
//                } else {
//                    System.out.println("Read: " + instr);
//                }
//                port.writeByte(ETX);
//                port.writeByte(LF);
//
//                port.writeByte(EOT);
//                port.writeByte(LF);
//
//                sleep(250);
//            }
//            System.out.println("Read: " + instr);
//
//            outstr = Config.TERMINAL_USERNAME + "\n";
//            port.writeString(outstr);
//            System.out.println("Wrote: " + outstr);
//
//            instr = null;
//            Log.d(TAG, "Looking for password:");
//
//            while (((instr = port.readString()) == null) || (!instr.endsWith("assword: "))) {
//
//                if (instr == null) {
//                    System.out.print(".");
//                } else {
//                    System.out.println("Read: " + instr);
//                }
//                sleep(100);
//            }
//            System.out.println("Read: " + instr);
//
//            outstr = Config.TERMINAL_PASSWORD + "\n";
//            port.writeString(outstr);
//            System.out.println("Wrote: " + outstr);
//
//            instr = null;
//            Log.d(TAG, "Looking for #");
//
//            while (((instr = port.readString()) == null) || (!instr.endsWith("# "))) {
//                if (instr == null) {
//                    System.out.print(".");
//                } else {
//                    System.out.println("Read: " + instr);
//                }
//                sleep(100);
//            }
//            System.out.println("Read: " + instr);
//
//            outstr = Config.SDR_CMD + "\n";
//            port.writeString(outstr);
//            System.out.println("Wrote: " + outstr);
//
////            while (((instr = port.readString()) == null) || (!instr.contains("Input:"))) {
////                if (instr == null) {
////                    System.out.print(".");
////                } else {
////                    System.out.println("Read: " + instr);
////                }
//                sleep(100);
////            }
//            sdrAppStatus = SdrAppStatus.RUNNING;
//            status = LoginStatus.LOGGED_IN;
//        }
//        catch (Exception e) {
//            Log.e(TAG, e.getMessage());
//        }
//
//
//
////        SDR_START_COMMAND = (Loader.SDR_APP_LOCATION+Loader.SQANDR_VERSION
////                +" -tx "+String.format("%.2f", SdrConfig.getTxFreq())
////                +" -rx "+String.format("%.2f",SdrConfig.getRxFreq())
////                +" -txgain 10"
////                //+" -header" //ignore any traffic that doesn't start with the first byte of the SqAN SDR packet
////                +" -minComms\n").getBytes(UTF8);
////        handlerThread = new HandlerThread("SerialCon") {
////            @Override
////            protected void onLooperPrepared() {
////                handler = new Handler(handlerThread.getLooper());
////                handler.postDelayed(new LoginHelper(null),DELAY_BEFORE_BLIND_LOGIN); //if no prompt is received, try to login anyway
////                handler.postDelayed(periodicHelper,DELAY_BEFORE_BLIND_LOGIN);
////            }
////        };
////        handlerThread.start();
//    }
//
////    public void open(@NonNull Context context, UsbDevice usbDevice) {
////        this.context = context;
////        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
////        if ((manager == null) || (usbDevice == null)) {
////            Log.e(TAG, "Cannot open, manager or device are null");
////            return;
////        }
////
////        if (port != null) {
////            Log.d(TAG,"SerialConnection already open, ignoring open call");
////            return;
////        }
////        Log.d(TAG,"Opening...");
////        Runnable initRunnable = () -> {
////            UsbSerialDriver driver = new PlutoCdcAsmDriver(usbDevice);
////            connection = manager.openDevice(driver.getDevice());
////            List<UsbSerialPort> ports = driver.getPorts();
////            port = ports.get(0);
////            try {
////                port.open(connection);
////                port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
////                port.setDTR(true);
////                port.setRTS(true);
////                ioManager = new SerialInputOutputManager(port, SerialConnection.this);
////                Executors.newSingleThreadExecutor().submit(ioManager);
////                Log.d(TAG,"Serial Connection thread started");
////            } catch (IOException e) {
////                Log.e(TAG,"Unable to read: "+e.getMessage());
////                try {
////                    port.close();
////                } catch (IOException e2) {
////                    Log.e(TAG,"Unable to close port: "+e2.getMessage());
////                }
////            }
////        };
////        if (handler == null)
////            initRunnable.run();
////        else
////            handler.post(initRunnable);
////
////    }
//
////    private Runnable periodicHelper = new Runnable() {
////        @Override
////        public void run() {
////            if (sdrAppStatus == SdrAppStatus.RUNNING) {
////                if (!isSdrConnectionCongested() && (System.currentTimeMillis() > nextKeepAliveMessage))
////                    write(KEEP_ALIVE_MESSAGE);
////            }
////            if (handler != null)
////                handler.postDelayed(this,TIME_BETWEEN_KEEP_ALIVE_MESSAGES);
////        }
////    };
//
//    //public void setListener(SerialListener listener) { this.listener = listener; }
//
//    public void close() {
//        Log.d(TAG,"Closing...");
////        if (ioManager != null) {
////            ioManager.setListener(null);
////            ioManager.stop();
////            ioManager = null;
////        }
//        sdrAppStatus = SdrAppStatus.OFF;
//        if (port != null) {
//            try {
//                //if (sdrAppStatus == SdrAppStatus.RUNNING) {
//                    String formattedData = HEADER_SHUTDOWN_CHAR + "\n";
//                    try {
//                        port.writeBytes(formattedData.getBytes(UTF8));
//                    } catch (SerialPortException e) {
//                        Log.w(TAG,"Unable to close SDR app before shutting down: "+e.getMessage());
//                    }
//                //}
//                port.setDTR(false);
//                port.setRTS(false);
//            } catch (Exception ignored) {
//            }
//            try {
//                port.closePort();
//            } catch (SerialPortException e) {
//                Log.e(TAG,"Unable to close port: "+e.getMessage());
//            }
//            port = null;
//        }
////        if (connection != null) {
////            try {
////                connection.close();
////            } catch (Exception e) {
////                Log.d(TAG,"Unable to close connection: "+e.getMessage());
////            }
////            connection = null;
////        }
//    }
//
//    public boolean isActive() {
//        return (port != null);
//    }
//
//    /**
//     * Burst adds any wrapping needed to communicate the data and then conducts
//     * a write
//     * @param data
//     */
//    public void burstPacket(final byte[] data) {
//        if (data == null)
//            return;
//        if (sdrAppStatus == SdrAppStatus.RUNNING) {
//            if (Segment.isAbleToWrapInSingleSegment(data)) {
//                Segment segment = new Segment();
//                segment.setData(data);
//                segment.setStandAlone();
//                Log.d(TAG, "Outgoing: " + new String(toSerialLinkFormat(segment.toBytes()), UTF8)); //FIXME for testing only
//                write(toSerialLinkFormat(segment.toBytes()));
//            } else {
//                Log.d(TAG, "This packet is larger than the SerialConnection output, segmenting...");
//                ArrayList<Segment> segments = Segmenter.wrapIntoSegments(data);
//                if ((segments == null) || segments.isEmpty()) {
//                    Log.e(TAG, "There was an unexpected problem that did not produce any segments from this packet");
//                    return;
//                }
//                for (Segment segment : segments) {
//                    write(toSerialLinkFormat(segment.toBytes()));
//                }
//            }
//        } else
//            Log.d(TAG,"Dropping "+data.length+"b packet as SqANDR is not yet running on the SDR");
//    }
//
//    private final static String PADDING_BYTE = "00112233445566778899";
//    //private final static String PADDING_BYTE = "";
//    private byte[] toSerialLinkFormat(byte[] data) {
//        if (data == null)
//            return null;
//        String formattedData = HEADER_DATA_PACKET_OUTGOING_CHAR + PADDING_BYTE +StringUtils.toHex(data)+"\n";
//        return formattedData.getBytes(UTF8);
//    }
//
//    /**
//     * Converts from the format sent over the serial connection into the actual byte array
//     * @param raw
//     * @return
//     */
//    private byte[] parseSerialLinkFormat(byte[] raw) {
//        if ((raw == null) || (raw.length < 3))
//            return null;
//        return parseSerialLinkFormat(new String(raw,UTF8));
//    }
//
//    private byte[] parseSerialLinkFormat(String raw) {
//        if ((raw == null) || (raw.length() < 3))
//            return null;
//        char[] chars = raw.toCharArray();
//        if (chars.length%2 == 1) {
//            int j = 1;
//            byte[] out = new byte[(chars.length-1)/2];
//            for (int i = 0; i < out.length; i++) {
//                out[i] = StringUtils.toByte(chars[j],chars[j+1]);
//                j += 2;
//            }
//            Log.d(TAG,"parseSerialLinkFormat parsed "+out.length+"b");
//            return out;
//        } else
//            Log.e(TAG,"Received data is not the right length: "+raw);
//        return null;
//    }
//
//    /**
//     * Write sends raw bytes to the connection as opposed to burstPacket() which adds
//     * any wrapping needed to communicate the data
//     * @param data
//     */
//    public void write(final byte[] data) {
//        if (data == null)
//            return;
//        //if ((port == null) || (ioManager == null)) {
//        if (port == null) {
//            Log.e(TAG,"Unable to write data - serial port not open");
//            return;
//        }
//
//        try {
//            port.writeBytes(data);
//        }
//        catch (SerialPortException e) {
//            Log.e(TAG,"Unable to write data: " + e.getMessage());
//        }
//
////        handler.post(() -> {
////            try {
////                //    ioManager.writeAsync(data);
////                nextKeepAliveMessage = System.currentTimeMillis() + TIME_BETWEEN_KEEP_ALIVE_MESSAGES;
////
////                //TODO testing
////                if ((data != null) && (data.length > 40))
////                    Log.d(TAG,"Outgoing: "+new String(data,UTF8));
////                //TODO testing
////
////                int bytesWritten = port.write(data,SERIAL_TIMEOUT);
////                if (bytesWritten < data.length)
////                    sdrConnectionCongestedUntil = System.currentTimeMillis()+TIME_FOR_USB_BACKLOG_TO_ADD_TO_CONGESTION;
////            } catch (IOException e) {
////                Log.e(TAG,"Unable to write data: "+e.getMessage());
////            }
////        });
//    }
//
//    private int attempts = 0;
////    private class LoginHelper implements Runnable {
////        private byte[] data;
////
////        public LoginHelper(byte[] data) {
////            this.data = data;
////        }
////
////        @Override
////        public void run() {
////            final String message;
////            if (data == null)
////                message = null;
////            else
////                message = new String(data, UTF8);
////            if (attempts > 3) {
////                status = LoginStatus.ERROR;
////                if (listener != null)
////                    listener.onConnectionError("Unable to login after 3 attempts: "+((message==null)?"":message));
////                //    listener.onSerialError(new Exception("Unable to login after 3 attempts: "+((message==null)?"":message)));
////                return;
////            }
////            if (message == null) {
////                if (status == LoginStatus.NEED_CHECK_LOGIN_STATUS) {
////                    Log.d(TAG, "No login prompt received, checking to see if already logged-in...");
////                    write(("help\r\n").getBytes(UTF8));
////                    status = LoginStatus.CHECKING_LOGGED_IN;
////                    handler.postDelayed(new LoginHelper(null),DELAY_BEFORE_BLIND_LOGIN);
////                } else if (((status == LoginStatus.WAITING_USERNAME) || (status == LoginStatus.CHECKING_LOGGED_IN)) && (username != null)) {
////                    Log.d(TAG, "No login prompt received, providing username anyway...");
////                    write((username + "\r\n").getBytes(UTF8));
////                    attempts++;
////                    status = LoginStatus.WAITING_PASSWORD;
////                }
////            } else {
////                if (message.length() < 3) //ignore short echo back messages
////                    return;
////                if (status == LoginStatus.CHECKING_LOGGED_IN) {
////                    if (isLoggedInAlready(message)) {
////                        Log.d(TAG, "Already logged-in");
////                        status = LoginStatus.LOGGED_IN;
////                        launchSdrApp();
////                        if (listener != null)
////                            listener.onConnect();
////                            //listener.onSerialConnect();
////                        return;
////                    } else {
////                        if (isLoginPasswordRequested(message)) {
////                            //we're not logged in so we'll provide a junk password and try again
////                            handler.post(() -> write("\r\n".getBytes(UTF8)));
////                            status = LoginStatus.WAITING_PASSWORD;
////                        } else {
////                            if (isLoginPasswordError(message)) {
////                                Log.d(TAG,"SDR rejected our login attempt");
////                                handler.post(new LoginHelper(message.getBytes(UTF8)));
////                                status = LoginStatus.WAITING_USERNAME;
////                                return;
////                            }
////                            //don't know what the terminal is asking, so throw an error
////                            status = LoginStatus.ERROR;
////                            if (listener != null)
////                                listener.onConnectionError("Unable to login; unknown message received: "+message);
////                                //listener.onSerialError(new Exception("Unable to login; unknown message received: "+message));
////                        }
////                    }
////                }
////                if (((status == LoginStatus.NEED_CHECK_LOGIN_STATUS) || (status == LoginStatus.WAITING_USERNAME))
////                        && (username != null) && isLoginRequested(message)) {
////                    Log.d(TAG, "Providing username...");
////                    write((username + "\r\n").getBytes(UTF8));
////                    attempts++;
////                    status = LoginStatus.WAITING_PASSWORD;
////                } else if ((status == LoginStatus.WAITING_PASSWORD) && (password != null) && isLoginPasswordRequested(message)) {
////                    Log.d(TAG, "Providing password...");
////                    write((password + "\r\n").getBytes(UTF8));
////                    status = LoginStatus.WAITING_CONFIRMATION;
////                } else if (isLoginSuccessMessage(message)) {
////                    Log.d(TAG, "Terminal login successful");
////                    status = LoginStatus.LOGGED_IN;
////                    launchSdrApp();
////                    if (listener != null)
////                        listener.onConnect();
////                        //listener.onSerialConnect();
////                } else if (isLoginPasswordError(message)) {
////                    status = LoginStatus.WAITING_USERNAME;
////                    handler.post(new LoginHelper(null));
////                } else if (isLoginErrorMessage(message)) {
////                    status = LoginStatus.ERROR;
////                    if (listener != null)
////                        listener.onConnectionError(message);
////                        //listener.onSerialError(new Exception(message));
////                }
////            }
////        }
////    };
//
////    @Override
//    public void onNewData(byte[] data) {
//        if ((data == null) || (data.length < 3)) { return; }
//        //Log.d(TAG,"onNewData("+data.length+"b): "+new String(data));
////        if (status != LoginStatus.LOGGED_IN) {
////            if (handler != null)
////                handler.postDelayed(new LoginHelper(data), DELAY_FOR_LOGIN_WRITE);
////            if (listener != null)
////                listener.onReceiveCommandData(data);
////                //listener.onConnectionError(new String(data,UTF8));
////                //listener.onSerialRead(data);
////        } else {
////            if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE) {
////                String message = new String(data,UTF8);
////                if (message.contains("messages")) { //messages also occurs in the /var/tmp folder
////                    Log.d(TAG,"Reply \""+message+"\" from the SDR should contain the contents of the "+Loader.SDR_APP_LOCATION+" directory");
////                    if (message.contains(Loader.SQANDR_VERSION)) {
////                        Log.d(TAG,Loader.SQANDR_VERSION+" found, start needed");
////                        if (peripheralStatusListener != null)
////                            peripheralStatusListener.onPeripheralMessage("Current version of SqANDR found, starting...");
////                        sdrAppStatus = SdrAppStatus.NEED_START;
////                    } else {
////                        Log.d(TAG,Loader.SQANDR_VERSION+" not found, update needed");
////                        if (peripheralStatusListener != null)
////                            peripheralStatusListener.onPeripheralMessage("SqANDR update needed...");
////                        sdrAppStatus = SdrAppStatus.INSTALL_NEEDED;
////                    }
////                    launchSdrApp();
////                }
////            }
//            if (data[0] == HEADER_DEBUG_MESSAGE) { return; }
//            if (data[0] == HEADER_DATA_PACKET_OUTGOING) { return; }
////            if (handler != null) {
//            String value = new String(data,UTF8);
//                int hdpp = value.indexOf(HEADER_DATA_PACKET_INCOMING_CHAR);
//                if (hdpp > 0) { value = value.substring(hdpp); }
////                value = value.replaceFirst("\\s","");
//                /*if (value.indexOf('\n') >= 0) {
//                    String[] values = value.split("\\n");
//                    if (values != null) {
//                        Log.d(TAG,"multi-line input detected: split into "+values.length+" inputs");
//                        for (String part:values) {
//                            if (part.charAt(0) != HEADER_DEBUG_MESSAGE)
//                                handler.post(new SdrAppHelper(part));
//                        }
//                    }
//                } else {*/
//
//                String[] parts = value.split("[\\n\\r]+");
//                for (String p:parts) {
////                    if (data[0] != HEADER_DEBUG_MESSAGE) {
//                        new SdrAppHelper(p);
//    //                  handler.post(new SdrAppHelper(value));
////                   }
//            //  }
//                }
////        }
//    }
//
//    /*private boolean isDatalinkData(byte[] data) {
//        if ((data == null) || (data.length < 3))
//            return false;
//        return data[0] == HEADER_DATA_PACKET_INCOMING;
//    }*/
//
//    public boolean isTerminalLoggedIn() { return status == LoginStatus.LOGGED_IN; }
//
//    private String[] LOGIN_TEST_PASSED_WORDS = {"Built-in"};
//    private boolean isLoggedInAlready(String message) {
//        if (message != null) {
//            for (String word:LOGIN_TEST_PASSED_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
//
//    private String[] LOGIN_ERROR_WORDS = {"incorrect"};
//    private boolean isLoginPasswordError(String message) {
//        if (message != null) {
//            for (String word:LOGIN_ERROR_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//    private String[] LOGIN_WORDS = {"login"};
//    private boolean isLoginRequested(String message) {
//        if (message != null) {
//            for (String word:LOGIN_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
//    private String[] PASSWORD_REQUEST_WORDS = {"password","Password"};
//    private boolean isLoginPasswordRequested(String message) {
//        if (message != null) {
//            for (String word:PASSWORD_REQUEST_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
//    private String[] ERROR_WORDS = {"error","cannot","unable"};
//    private boolean isLoginErrorMessage(String message) {
//        if (message != null) {
//            for (String word:ERROR_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
//    private String[] SUCCESS_WORDS = {"Welcome to:","logged","help"};
//    private boolean isLoginSuccessMessage(String message) {
//        if (message != null) {
//            for (String word:SUCCESS_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
//    private String[] SDR_APP_WORDS = {"Starting"};
//    private boolean isSdrAppSuccessMessage(String message) {
//        if (message != null) {
//            for (String word:SDR_APP_WORDS) {
//                if (message.contains(word))
//                    return true;
//            }
//        }
//        return false;
//    }
//
////    @Override
//    public void onRunError(Exception e) {
//        Log.e(TAG,"onRunError()");
////        if (listener != null)
////            listener.onConnectionError(e.getMessage());
//    }
//
//    private class SdrAppHelper { // implements Runnable {
//        private String input;
//
//        public SdrAppHelper(String input) {
//            this.input = input;
////        }
//
////        @Override
////        public void run() {
//            if (input != null) {
//
//                if (sdrAppStatus == SdrAppStatus.RUNNING) {
////                    Log.d(TAG,"From SDR: "+input);
//                    switch (input.charAt(0)) {
//                        case HEADER_DATA_PACKET_INCOMING_CHAR:
//                            Log.d(TAG,"SdrAppHelper - found Data Packet");
//                            handleRawDatalinkInput(parseSerialLinkFormat(input));
//                            return;
//
//                        case HEADER_SYSTEM_MESSAGE_CHAR:
//                            input = input.substring(1)+"\n";
//                            break;
//
//                        case HEADER_DEBUG_MESSAGE:
//                            Log.d(TAG,"Ignoring debug message: "+input.substring(1));
//                            return;
//
//                        case HEADER_SHUTDOWN:
//                            Log.d(TAG,"The shutdown command to the SDR was received back (this is meaningless for the Android end); ignoring.");
//                            return;
//
//                        case HEADER_DATA_PACKET_OUTGOING:
//                            Log.d(TAG,"Our own outgoing packet was received back(this is meaningless for the Android end); ignoring.");
//                            return;
//
//                        default:
//                            if (input.contains("-sh")) {
//                                final String errorMessage = "The app on the SDR is having a problem: "+input;
//                                sdrAppStatus = SdrAppStatus.ERROR;
////                                if (listener != null)
////                                    listener.onConnectionError(errorMessage);
////                                if (peripheralStatusListener != null)
////                                    peripheralStatusListener.onPeripheralError(errorMessage);
//                            } else
//                                input = "Unknown command: "+input+"\n";
//                    }
//                } else if (sdrAppStatus == SdrAppStatus.INSTALLING) {
//                    /*Log.d(TAG,"From SDR (app installing): "+input);
//                    switch (input.charAt(0)) {
//                        case HEADER_BUSYBOX: //ignore calls to busybox
//                            return;
//                    }*/
//                    return; //ignore all messages during install
//                }
//            }
////            if (attempts > 3) {
////                sdrAppStatus = SdrAppStatus.ERROR;
////                final String errorMessage = "Unable to start app after 3 attempts: "+((input==null)?"":input);
////                if (listener != null)
////                    listener.onConnectionError(errorMessage);
////                if (peripheralStatusListener != null)
////                    peripheralStatusListener.onPeripheralError(errorMessage);
////                return;
////            }
////            if (input == null) {
////                if (sdrAppStatus == SdrAppStatus.NEED_START)
////                    launchSdrApp();
////            } else {
////                if (input.length() < 3) //ignore short echo back messages
////                    return;
////                if (isSdrAppSuccessMessage(input)) {
////                    CommsLog.log(CommsLog.Entry.Category.SDR,"SDR companion app is running");
////                    sdrAppStatus = SdrAppStatus.RUNNING;
////                    if (peripheralStatusListener != null) {
////                        Log.d(TAG, "onPeripheralReady()");
////                        peripheralStatusListener.onPeripheralReady();
////                    }
////                } else {
////                    CommsLog.log(CommsLog.Entry.Category.SDR,"SDR input: "+input);
////                    if (listener != null)
////                        listener.onReceiveCommandData(input.getBytes(UTF8));
////                }
////            }
//        }
//    }
//
////    @Override
////    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
////        super.setPeripheralStatusListener(listener);
////        if (listener != null) {
////            switch (sdrAppStatus) {
////                case OFF:
////                case STARTING:
////                case NEED_START:
////                    listener.onPeripheralMessage("SDR is getting ready...");
////                    break;
////
////                case INSTALLING:
////                case INSTALL_NEEDED:
////                case CHECKING_FOR_UPDATE:
////                    listener.onPeripheralMessage("SDR is checking for updated software...");
////                    break;
////
////                case RUNNING:
////                    Log.d(TAG,"onPeripheralReady()");
////                    listener.onPeripheralReady();
////                    break;
////
////                default:
////                    listener.onPeripheralError("SDR has an error");
////                    break;
////            }
////        }
////    }
//
//        private void startSdrApp() {
//        if (sdrAppStatus == SdrAppStatus.NEED_START) {
//            sdrAppStatus = SdrAppStatus.STARTING;
//            final String message = "Starting SDR companion app";
//            Log.d(TAG,message);
////            if (peripheralStatusListener != null)
////                peripheralStatusListener.onPeripheralMessage(message);
//            attempts = 0;
////            CommsLog.log(CommsLog.Entry.Category.SDR,"Initiating SDR App with command: "+new String(SDR_START_COMMAND,UTF8));
//            write(SDR_START_COMMAND);
//        }
//    }
//
//    /**
//     * Installs the SqANDR app on the SDR if needed ans starts the app
//     */
//    private void launchSdrApp() {
//        Log.d(TAG, "launchSdrApp()");
//        //if (sdrAppStatus == SdrAppStatus.CHECKING_FOR_UPDATE)
//        //    return;
//        if (sdrAppStatus == SdrAppStatus.OFF) {
//            sdrAppStatus = SdrAppStatus.CHECKING_FOR_UPDATE;
////            if (peripheralStatusListener != null)
////                peripheralStatusListener.onPeripheralMessage("Checking if current version of SqANDR is installed...");
////            Loader.queryIsCurrentSqANDRInstalled(port);
//        } else if (sdrAppStatus == SdrAppStatus.NEED_START)
//            startSdrApp();
//        else {
//            if (sdrAppStatus != SdrAppStatus.INSTALL_NEEDED)
//                return; //already installing, ignore
////            if (peripheralStatusListener != null)
////                peripheralStatusListener.onPeripheralMessage("Updating SqANDR...");
//            sdrAppStatus = SdrAppStatus.INSTALLING;
////            if (context == null) {
////                final String errorMessage = "Cannot push the SqANDR app onto the SDR with a null context - this should never happen";
////                Log.e(TAG,errorMessage);
////                sdrAppStatus = SdrAppStatus.ERROR;
////                if (listener != null)
////                    listener.onConnectionError(errorMessage);
////                if (peripheralStatusListener != null)
////                    peripheralStatusListener.onPeripheralError(errorMessage);
////                return;
////            }
////            Loader.pushAppToSdr(context, port, new SqANDRLoaderListener() {
////                @Override
////                public void onSuccess() {
////                    sdrAppStatus = SdrAppStatus.NEED_START;
////                    if (peripheralStatusListener != null)
////                        peripheralStatusListener.onPeripheralMessage("SqANDR updated.");
////                    startSdrApp();
////                }
//
////                @Override
////                public void onFailure(String message) {
////                    Log.e(TAG,"Error installing SqANDR: "+message);
////                    sdrAppStatus = SdrAppStatus.ERROR;
////                    if (listener != null)
////                        listener.onConnectionError(message);
////                    if (peripheralStatusListener != null)
////                        peripheralStatusListener.onPeripheralError(message);
////                }
////            });
//        }
//    }
//}
