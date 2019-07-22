package org.sofwerx.notdroid.util;

import static org.sofwerx.sqandr.Config.isAndroid;

public class Log {
    private Object androidLog;

    /**
     * Priority constant for the println method; use Log.v.
     */
    public static final int VERBOSE = 2;

    /**
     * Priority constant for the println method; use Log.d.
     */
    public static final int DEBUG = 3;

    /**
     * Priority constant for the println method; use Log.i.
     */
    public static final int INFO = 4;

    /**
     * Priority constant for the println method; use Log.w.
     */
    public static final int WARN = 5;

    /**
     * Priority constant for the println method; use Log.e.
     */
    public static final int ERROR = 6;

    private static String[] Levels = {
            "",
            "",
            "Verbose",
            "Debug",
            "Info",
            "WARNING",
            "ERROR",
            "Assertion FAILED"
    };

    public android.util.Log toAndroid() {
        return (android.util.Log) this.androidLog;
    }

    public static void log(String lvl, String tag, String msg) {
        System.out.println(lvl + ": " + tag + " - " + msg);
    }

    public static void e(String tag, String msg) {
        if (isAndroid()) {
            android.util.Log.e(tag, msg);
        } else {
            log(Levels[ERROR], tag, msg);
        }
    }

    public static void w(String tag, String msg) {
        if (isAndroid()) {
            android.util.Log.w(tag, msg);
        } else {
            log(Levels[WARN], tag, msg);
        }
    }

    public static void i(String tag, String msg) {
        if (isAndroid()) {
            android.util.Log.i(tag, msg);
        } else {
            log(Levels[INFO], tag, msg);
        }
    }

    public static void d(String tag, String msg) {
        if (isAndroid()) {
            android.util.Log.d(tag, msg);
        } else {
            log(Levels[DEBUG], tag, msg);
        }
    }

    public static void v(String tag, String msg) {
        if (isAndroid()) {
            android.util.Log.v(tag, msg);
        } else {
            log(Levels[VERBOSE], tag, msg);
        }
    }
}
