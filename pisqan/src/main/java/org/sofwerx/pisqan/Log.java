package org.sofwerx.pisqan;

public class Log {
    public enum LogLevel {ERROR, WARNING, INFO, DEBUG, VERBOSE};
    public static LogLevel LOG_LEVEL = LogLevel.VERBOSE;

    static public void e(String tag, String msg) { writeLog("ERROR", tag, msg); }

    static public void w(String tag, String msg) {
        if (LOG_LEVEL == LogLevel.WARNING || LOG_LEVEL == LogLevel.ERROR) {
            writeLog("Warning", tag, msg);
        }
    }

    static public void i(String tag, String msg) {
        if (LOG_LEVEL == LogLevel.INFO || (LOG_LEVEL != LogLevel.WARNING && LOG_LEVEL != LogLevel.ERROR)) {
            writeLog("Info", tag, msg);
        }
    }

    static public void d(String tag, String msg) {
        if (LOG_LEVEL == LogLevel.DEBUG || LOG_LEVEL == LogLevel.VERBOSE) {
            writeLog("Debug", tag, msg);
        }
    }
    static public void v(String tag, String msg) {
        if (LOG_LEVEL == LogLevel.VERBOSE) {
            writeLog("Verbose", tag, msg);
        }
    }


    static void writeLog(String level, String tag, String msg) {
        System.out.println(level + ": " + tag + " - " + msg);
    }
}