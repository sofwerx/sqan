package org.sofwerx.sqantest.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;

import java.util.ArrayList;

public class PermissionsHelper {
    public final static int PERMISSIONS_CHECK = 1043;

    public static void checkForPermissions(Activity context) {
        ArrayList<String> neededPermissions = new ArrayList<>();
        //if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        //    neededPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            neededPermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (neededPermissions.isEmpty())
            return;
        String[] perms = new String[neededPermissions.size()];
        for (int i=0;i<perms.length;i++) {
            perms[i] = neededPermissions.get(i);
        }
        ActivityCompat.requestPermissions(context,perms, PERMISSIONS_CHECK );
    }
}
