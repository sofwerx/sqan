package org.sofwerx.sqandr.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.sofwerx.sqan.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

public class FileUtils {
    public final static int PERMISSION_WRITE_EXTERNAL_STORAGE = 1011;
    private static File defaultDirectory = null;

    public static boolean hasFilePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                if (context instanceof Activity)
                    ((Activity)context).requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, PERMISSION_WRITE_EXTERNAL_STORAGE);
                return false;
            } else
                return true;
        } else
            return true;
    }

    public static void setDefaultDirectory(Context context, File dir) {
        if (hasFilePermission(context)) {
            if (dir == null) {
                File base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
                defaultDirectory = new File(base,"SqANDR");
            } else
                defaultDirectory = dir;
        }
    }

    public static File getDefaultDirectory(Context context) {
        if (defaultDirectory == null)
            setDefaultDirectory(context,null);
        return defaultDirectory;
    }

    public static void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory())
            for (File child : fileOrDirectory.listFiles())
                deleteRecursive(child);

        fileOrDirectory.delete();
    }

    public static String getFileSafeName(String name) {
        if (name == null)
            return null;
        name = name.replaceAll("[^a-zA-Z0-9\\.\\-]", "_");
        if (name.length() < 1)
            name = null;
        return name;
    }

    public static File writeTextToFile(Context context, File file, String text) {
        if ((context == null) || (file == null)) {
            Log.d(Config.TAG,"Unable to write to a null file or with a null context");
            return null;
        }
        try {
            if (!file.exists())
                file.createNewFile();
            if (text != null) {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(text.getBytes(StandardCharsets.UTF_8));
                fos.close();
            }
            return file;
        } catch (IOException e) {
            Log.d(Config.TAG,"Unable to write text to file: "+e.getMessage());
        }
        return null;
    }
}
