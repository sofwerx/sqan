package org.sofwerx.sqan.ui;

import android.os.Build;
import android.os.Bundle;
import android.preference.CheckBoxPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;

public class CoreSettingsFragment extends PreferenceFragment {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.prefs_app);
        Preference clearTeammates = findPreference(Config.PREF_CLEAR_TEAM);
        if (clearTeammates != null) {
            int numberOfSavedTeammates = Config.getNumberOfSavedTeammates();
            if (numberOfSavedTeammates > 0) {
                clearTeammates.setEnabled(true);
                clearTeammates.setSummary("Clear list of "+numberOfSavedTeammates+" saved teammate"+((numberOfSavedTeammates>1)?"s":""));
                clearTeammates.setOnPreferenceClickListener(preference -> {
                    Config.clearTeammates();
                    Toast.makeText(getContext(), "List of saved teammates cleared.", Toast.LENGTH_LONG).show();
                    clearTeammates.setEnabled(false);
                    clearTeammates.setSummary("Clear list of teammates");
                    return true;
                });
            } else
                clearTeammates.setEnabled(false);
        }
        Preference sdrSettings = findPreference(Config.PREFS_SDR_SETTINGS);
        if (sdrSettings != null)
            sdrSettings.setOnPreferenceClickListener(preference -> {
                SdrConfigDialog.show(getActivity());
                return true;
            });

        Preference vpnForwardIps = findPreference(Config.PREFS_VPN_EDIT_FORWARDS);
        if (vpnForwardIps != null)
            vpnForwardIps.setOnPreferenceClickListener(preference -> {
                VpnFwdDialog.show(getActivity());
                return true;
            });
    }
}
