package org.sofwerx.sqandr.util;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class PermissionsHelper {
    public final static int PERMISSIONS_CHECK = 1043;
    private final static String[] NEEDED = new String[]{
            Manifest.permission.WRITE_EXTERNAL_STORAGE};

    public static void checkForPermissions(Activity context) {
        ArrayList<String> neededPermissions = new ArrayList<>();
        for (String perm:NEEDED) {
            if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED)
                neededPermissions.add(perm);
        }
        if (neededPermissions.isEmpty())
            return;
        String[] perms = new String[neededPermissions.size()];
        for (int i=0;i<perms.length;i++) {
            perms[i] = neededPermissions.get(i);
        }
        ActivityCompat.requestPermissions(context,perms, PERMISSIONS_CHECK );
    }
}
