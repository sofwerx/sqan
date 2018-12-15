package org.sofwerx.sqan.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.preference.PreferenceManager;

public class PackageUtil {
    public static boolean doesPackageExist(Context context, String packageToFind){
        if ((context != null) && (packageToFind != null)){
            PackageManager pm = context.getPackageManager();
            try {
                pm.getPackageInfo(packageToFind, PackageManager.GET_META_DATA);
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
            return true;
        } else
            return false;
    }
}
