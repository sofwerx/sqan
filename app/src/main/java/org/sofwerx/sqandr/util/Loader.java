package org.sofwerx.sqandr.util;

import android.util.Log;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.CommsLog;

import java.io.File;
import java.io.FileInputStream;

/**
 * Helper class to assist in loading SqANDR on to Pluto and to get it running
 */
public class Loader {
    private final static String TAG = Config.TAG+".Loader";

    public static boolean pushAppToSdr() {
        boolean success = false;
        FTPClient con;
        try {
            con = new FTPClient();
            con.connect("192.168.2.57");

            if (con.login("Administrator", "KUjWbk")) {
                con.enterLocalPassiveMode(); // only passive mode is supported on Android
                con.setFileType(FTP.BINARY_FILE_TYPE);
                String data = "/sdcard/vivekm4a.m4a";

                FileInputStream in = new FileInputStream(new File(data));
                success = con.storeFile("/vivekm4a.m4a", in);
                in.close();
                if (success)
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"App loaded on Pluto");
                con.logout();
                con.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return success;
    }
}
