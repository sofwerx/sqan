package org.sofwerx.notdroid.content;

import static org.sofwerx.sqandr.Config.isAndroid;

public class Context {
    private Object androidContext;

    public Context() {
        this.androidContext = null;
    }

    public Context (android.content.Context ctx) {
        this.androidContext = ctx;
    }

    public android.content.Context toAndroid() {
        return (android.content.Context) this.androidContext;
    }

    public android.content.Context getApplicationContext() {
        return this.toAndroid().getApplicationContext();
    }

    public android.content.pm.PackageManager getPackageManager() {
        return this.toAndroid().getPackageManager();
    }

    public void startActivity(android.content.Intent intent) {
        this.toAndroid().startActivity(intent);
    }

    public android.content.Intent registerReceiver(android.content.BroadcastReceiver rcvr, android.content.IntentFilter filter) {
        return this.toAndroid().registerReceiver(rcvr, filter);
    }

    public void unregisterReceiver(android.content.BroadcastReceiver rcvr) {
        this.toAndroid().unregisterReceiver(rcvr);
        return;
    }

}
