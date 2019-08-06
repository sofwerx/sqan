package org.sofwerx.notdroid.app;

public class PendingIntent {
    private Object androidIntent;

    public PendingIntent() {
        androidIntent = null;
    }

    public PendingIntent (android.app.Activity act) {
        androidIntent = act;
    }


    public android.app.PendingIntent toAndroid() {
        return (android.app.PendingIntent)this.androidIntent;
    }
}
