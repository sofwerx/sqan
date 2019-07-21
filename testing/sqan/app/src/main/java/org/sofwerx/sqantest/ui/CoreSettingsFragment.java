package org.sofwerx.sqantest.ui;

import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;

import org.sofwerx.sqantest.R;

public class CoreSettingsFragment extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.prefs_app);
        /*CheckBoxPreference runBackground = (CheckBoxPreference)findPreference(Config.PREFS_RUN_IN_BACKGROUND);
        CheckBoxPreference runForeground = (CheckBoxPreference)findPreference(Config.PREFS_RUN_IN_FOREGROUND);
        EditTextPreference callsign = (EditTextPreference)findPreference(Config.PREFS_CALLSIGN);
        callsign.setSummary(Config.getCallsign());
        Preference uuid = findPreference(Config.PREFS_UUID);
        uuid.setSummary(Config.getUUID());
        if (SpecificConfig.isSensor())
            runBackground.setEnabled(false);
        if (Build.VERSION.SDK_INT >= 28) //TODO change to Build.VERSION_CODES.P once the SDK is updated
            runForeground.setEnabled(false);*/
    }
}
