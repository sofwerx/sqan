package org.sofwerx.notdroid.os;

public class Handler {
    private android.os.Handler androidHandler;

    public Handler() {
        this.androidHandler = null;
    }

    public Handler (android.os.Handler hndlr) {
        this.androidHandler = hndlr;
    }

    public android.os.Handler toAndroid() {
        return this.androidHandler;
    }

    public void post(Runnable r) {
        r.run();
    }
}
