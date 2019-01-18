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
        public enum Category {
            PROBLEM,
            STATUS,
            COMMS
        }

        public Entry(Category category, long time, String message) {
            this.category = category;
            this.time = time;
            this.message = message;
        }

        public Entry(Category category, String message) {
            this(category, System.currentTimeMillis(),message);
        }

        public long time;
        public String message;
        private Category category;

        @Override
        public String toString() {
            StringWriter out = new StringWriter();
            out.append(StringUtil.getFormattedTime(time));
            out.append(" ");
            if (category == Category.PROBLEM)
                out.append("[Problem] ");
            if (message == null)
                out.append("NSTR");
            else
                out.append(message);
            return out.toString();
        }
    }

    public static void log(Entry.Category category, String message) {
        Log.i(Config.TAG,message);
        if (entries == null)
            entries = new ArrayList<>();
        entries.add(new Entry(category,message));
        while (entries.size() > MAX_LOG_LENGTH)
            entries.remove(0);
    }

    public static ArrayList<Entry> getEntries() { return entries; }

    public static Entry getLastProblemOrStatus() {
        if ((entries != null) && !entries.isEmpty()) {
            for (int i=entries.size()-1;i>=0;i--) {
                Entry.Category cat = entries.get(i).category;
                if ((cat == Entry.Category.PROBLEM) || (cat == Entry.Category.STATUS))
                    return entries.get(i);
            }
        }
        return null;
    }

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
                out.append(entry.toString());
            }
        }

        return out.toString();
    }
}
