package org.sofwerx.sqan.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(Config.TAG,"BootReceiver launched, action: "+intent.getAction());
        String action = intent.getAction();
        if (action != null) {
            if (action.equalsIgnoreCase(Intent.ACTION_BOOT_COMPLETED)) {
                if (Config.isStartOnReboot(context)) {
                    Intent mIntentForService = new Intent(context, SqAnService.class);
                    mIntentForService.setAction(action);
                    context.startService(mIntentForService);
                }
            } else if (action.equalsIgnoreCase(Intent.ACTION_SHUTDOWN)) {
                //TODO send an intent to the service so it can send the hangup signal
            }
        }
    }
}
