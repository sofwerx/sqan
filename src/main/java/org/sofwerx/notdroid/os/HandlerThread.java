package org.sofwerx.notdroid.os;

public class HandlerThread extends Thread {

    public HandlerThread(String name) {
        super(name);
    }

    public HandlerThread(String name, int priority) {
        super(name);
    }

    protected void onLooperPrepared() {
        return;
    }

    public Thread getLooper() {
        return this.getLooper();
    }

    public Thread getLooper(String tag) {
        return this.getLooper(tag);
    }

    public void quitSafely() {
        return;
    }

}
