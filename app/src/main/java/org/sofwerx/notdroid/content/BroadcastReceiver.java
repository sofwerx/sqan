package org.sofwerx.notdroid.content;

import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.Intent;
import android.os.Handler;

public class BroadcastReceiver {
    private Object androidBcastRcvr;

    public BroadcastReceiver() {
        this.androidBcastRcvr = null;
    }

    public void onReceive(org.sofwerx.notdroid.content.Context context, Intent intent) {

    }

    public void onReceive(android.content.Context context, Intent intent) {

    }

}
