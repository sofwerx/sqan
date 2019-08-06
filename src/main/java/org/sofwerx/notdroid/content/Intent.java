package org.sofwerx.notdroid.content;

public class Intent {
    private Object androidIntent;

    public Intent() {
        this.androidIntent = null;
    }

//    public Intent(android.content.Intent intent) {
//        this.androidIntent = intent;
//    }

    public Intent(android.content.Intent intentIn) {
        this.androidIntent = new android.content.Intent(intentIn);
    }

    public Intent(String permName) {
        this.androidIntent = new android.content.Intent(permName);
    }

    public android.content.Intent toAndroid() {
        return (android.content.Intent) this.androidIntent;
    }

}
