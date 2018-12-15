package org.sofwerx.sqan.receivers;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import org.sofwerx.sqan.Config;

public class PowerReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(Config.TAG,"PowerReceiver launched, action: "+intent.getAction());
        /*Intent mIntentForService = new Intent(context, MdxService.class);
        context.startService(mIntentForService);*/
    }
}
