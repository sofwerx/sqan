package org.sofwerx.notdroid.app;

import android.app.ActivityManager;
import android.content.ComponentCallbacks2;
import org.sofwerx.notdroid.content.Context;
import android.content.res.Configuration;
import android.util.AttributeSet;
import android.view.*;
import android.view.accessibility.AccessibilityEvent;
import androidx.annotation.Nullable;
import org.sofwerx.notdroid.view.ContextThemeWrapper;

public abstract class Activity {
    private Object androidActivity;

    public Activity() {
        androidActivity = null;
    }

    public Activity (android.app.Activity act) {
        androidActivity = act;
    }


    public android.app.Activity toAndroid() {
        return (android.app.Activity)this.androidActivity;
    }

}
