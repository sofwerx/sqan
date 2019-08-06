package org.sofwerx.pisqan;

import org.sofwerx.pisqan.Log.LogLevel;

public class Config {
    public final static String TAG = "piSqAN";
    public static LogLevel DFLT_LOG_LEVEL = LogLevel.DEBUG;

    public final static String OSX_OS_NAME = "Mac OS X"; // System.getProperty("os.name")
    public final static String OSX_TTY_PATTERN = ".*tty.usbmodem.*";

    public final static String LINUX_OS_NAME = "Linux"; // System.getProperty("os.name")
    public final static String LINUX_TTY_PATTERN = ".*ttyACM.*";

//    public final static String USE_TTY_PATTERN = null;   // null for auto-detect only
    public final static String USE_TTY_PATTERN = OSX_TTY_PATTERN;   // set one of these if auto-detect isn't working
//    public final static String USE_TTY_PATTERN = LINUX_TTY_PATTERN;   // set one of these if auto-detect isn't working

    public final static String TERMINAL_USERNAME = "root";
    public final static String TERMINAL_PASSWORD = "analog";

    public final static int START_POOL_SIZE = 1;
    public final static int CORE_POOL_SIZE = 5;
    public final static int MAX_POOL_SIZE = 10;

    public static void init() {

        return;
    }

}
