package org.sofwerx.sqan.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.util.Log;

import org.sofwerx.sqan.Config;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.core.content.ContextCompat;

public class CommsLog {
    private final static int MAX_LOG_LENGTH = 20;
    private static ArrayList<Entry> entries = null;
    private static FileOutputStream fos;
    private static OutputStreamWriter oswriter;
    private static BufferedWriter bwriter;
    private static AtomicBoolean isRunning = new AtomicBoolean(false);

    public static void clear() {
        entries = null;
    }

    public static void init(Context context) {
        if (Config.isLoggingEnabled()) {
            try {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
                    File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),"SqAN");
                    logDir.mkdirs();
                    File file1 = new File(logDir,StringUtil.getFilesafeTime(System.currentTimeMillis())+".txt");
                    file1.createNewFile();
                    fos = new FileOutputStream(file1);
                    oswriter = new OutputStreamWriter(fos);
                    bwriter = new BufferedWriter(oswriter);
                    isRunning.set(true);
                }
            } catch (Exception e) {
                e.printStackTrace();
                isRunning.set(false);
            }
        } else
            close();
    }

    public static void close() {
        isRunning.set(false);
        clear();
        try {
            if (bwriter != null) {
                bwriter.close();
                bwriter = null;
            }
        } catch (IOException ignore) {
        }
        try {
            if (oswriter != null) {
                oswriter.close();
                oswriter = null;
            }
        } catch (IOException ignore) {
        }
        try {
            if (fos != null) {
                fos.close();
                fos = null;
            }
        } catch (IOException ignore) {
        }
    }

    public static class Entry {
        public enum Category {
            PROBLEM,
            STATUS,
            COMMS,
            CONNECTION
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
            out.append(StringUtil.getFormattedJustTime(time));
            out.append(' ');
            switch (category) {
                case PROBLEM:
                    out.append("[PROBLEM]");
                    break;

                case CONNECTION:
                    out.append("[CONNECTION]");
                    break;
            }
            out.append(' ');
            if (message == null)
                out.append("NSTR");
            else
                out.append(message);
            return out.toString();
        }
    }

    public static void log(String message) {
        if (isRunning.get()) {
            if (bwriter != null) {
                try {
                    bwriter.append(message);
                    bwriter.newLine();
                } catch (IOException ignore) {
                }
            }
        }
    }

    public static void log(Entry.Category category, String message) {
        if (isRunning.get()) {
            Log.i(Config.TAG, message);
            if (entries == null)
                entries = new ArrayList<>();
            Entry entry = new Entry(category, message);
            entries.add(entry);
            while (entries.size() > MAX_LOG_LENGTH)
                entries.remove(0);
            if (bwriter != null) {
                try {
                    bwriter.append(entry.toString());
                    bwriter.newLine();
                } catch (IOException ignore) {
                }
            }
        }
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
