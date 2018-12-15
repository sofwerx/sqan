package org.sofwerx.sqan.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.util.NetworkUtil;

public class ConnectivityReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent intentForService = new Intent(context, SqAnService.class);
        String action = intent.getAction();
        if (action != null) {
            Log.d(Config.TAG, "ConnectivityReceiver launched, action: " + action);
            //if (action.equalsIgnoreCase(NetworkUtil.INTENT_CONNECTIVITY_CHANGED) || action.equalsIgnoreCase(NetworkUtil.INTENT_WIFI_CHANGED)) {
            if (action.equalsIgnoreCase(NetworkUtil.INTENT_CONNECTIVITY_CHANGED)) {
                intentForService.setAction(intent.getAction());
                context.startService(intentForService);
            }
        }
    }
}
