package org.sofwerx.notdroid.content;

import org.sofwerx.notdroid.hardware.usb.UsbManager;

import java.util.HashMap;

import static org.sofwerx.sqandr.Config.isAndroid;

public class Context {
    public final static String PREFIX = "notdroid.";
    public final static String USB_SERVICE = PREFIX + android.content.Context.USB_SERVICE;

    private Object androidContext;

    private HashMap<String,Object> systemSvcs;
    private UsbManager usbManager;

    public Context() {
        this.androidContext = null;
    }

    public Context (android.content.Context ctx) {
        this.androidContext = ctx;
    }

    public android.content.Context toAndroid() {
        return (android.content.Context) this.androidContext;
    }


    public Object getSystemService(String name) {
        this.systemSvcs.put(name, null);
        switch (name) {
            case USB_SERVICE:
                this.systemSvcs.put(name, new UsbManager());
            case android.content.Context.USB_SERVICE:
                this.systemSvcs.put(name, new UsbManager((android.hardware.usb.UsbManager)this.toAndroid().getSystemService(name)));
        }

        return this.systemSvcs.get(name);
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
