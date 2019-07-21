package org.sofwerx.sqantest.util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;

import androidx.core.content.ContextCompat;

import org.sofwerx.sqantest.ui.ReportActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;

public class FileUtil {
    public static File save(Context context, String filename, String data) {
        if ((context == null) || (filename == null) || (data == null))
            return null;
        File file = null;
        try {
            File reportDir = getReportDir(context);
            if (reportDir != null) {
                file = new File(reportDir,filename);
                file.createNewFile();
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data.getBytes("UTF-8"));
                fos.close();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return file;
    }

    public static File getReportDir(Context context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            File sqanDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SqAN");
            File testDir = new File(sqanDir, "test");
            testDir.mkdirs();
            return testDir;
        }
        return null;
    }

    public static String getText(File file) {
        if ((file != null) && file.exists()) {
            try {
                StringBuilder text = new StringBuilder();
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line;

                while ((line = br.readLine()) != null) {
                    text.append(line);
                    text.append('\n');
                }
                br.close();
                return text.toString();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }
}
