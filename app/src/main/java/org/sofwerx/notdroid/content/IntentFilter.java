package org.sofwerx.notdroid.content;

public class IntentFilter {
    private Object androidFilter;

    public IntentFilter() {
        this.androidFilter = null;
    }

    public IntentFilter (android.content.IntentFilter intFilter) {
        this.androidFilter = intFilter;
    }

    public android.content.IntentFilter toAndroid() {
        return (android.content.IntentFilter) this.androidFilter;
    }
}
