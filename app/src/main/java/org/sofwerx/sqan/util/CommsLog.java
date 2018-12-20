package org.sofwerx.sqan.util;

import android.util.Log;

import org.sofwerx.sqan.Config;

import java.io.StringWriter;
import java.util.ArrayList;

public class CommsLog {
    private final static int MAX_LOG_LENGTH = 200;
    private static ArrayList<Entry> entries = null;

    public static void clear() {
        entries = null;
    }

    public static class Entry {
        public Entry(long time, String message) {
            this.time = time;
            this.message = message;
        }

        public Entry(String message) {
            this(System.currentTimeMillis(),message);
        }

        public long time;
        public String message;
    }

    public static void log(String message) {
        Log.i(Config.TAG,message);
        if (entries == null)
            entries = new ArrayList<>();
        entries.add(new Entry(message));
        while (entries.size() > MAX_LOG_LENGTH)
            entries.remove(0);
    }

    public static ArrayList<Entry> getEntries() { return entries; }

    public static String getEntriesAsString() {
        StringWriter out = new StringWriter();

        if ((entries == null) || entries.isEmpty())
            out.append("No logs");
        else {
            boolean first = true;
            for (Entry entry:entries) {
                if (first)
                    first = false;
                else
                    out.append("\r\n");
                out.append(StringUtil.getFormattedTime(entry.time));
                out.append(": ");
                out.append(entry.message);
            }
        }

        return out.toString();
    }
}
