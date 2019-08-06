package org.sofwerx.notdroid.os;

import java.util.concurrent.TimeoutException;

public class Handler {
    private android.os.Handler androidHandler;
    private Thread handlerThread = null;

    public Handler() {
        this.androidHandler = null;
    }

    public Handler(Thread hndlrThread) { this.handlerThread = hndlrThread; }

    public Handler (android.os.Handler hndlr) {
        this.androidHandler = hndlr;
    }

    public android.os.Handler toAndroid() {
        return this.androidHandler;
    }

    public void post(Runnable r) {
        r.run();
    }

    public void removeCallbacks(Object obj) {}

    public void removeCallbacksAndMessages(Object obj) {}

    public void postDelayed(Runnable r, long delay) {
        try {
            r.wait(delay);
        }
        catch (InterruptedException e) {
            System.out.println(e);
        }
        r.run();
    }
}
