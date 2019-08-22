package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.vpn.SqAnVpnService;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new CoreSettingsFragment())
                .commit();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PreferenceManager.getDefaultSharedPreferences(this)
                .registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPause() {
        PreferenceManager.getDefaultSharedPreferences(this)
                .unregisterOnSharedPreferenceChangeListener(listener);
        super.onPause();
    }

    @Override
    protected void onStop() {
        /*if (Config.isRunInForegroundEnabled(this)) {
            Intent intent = new Intent(this, SqAnService.class);
            intent.setAction(SqAnService.ACTION_FOREGROUND_SERVICE);
            startService(intent);
        }*/
        super.onStop();
    }

    private SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) -> {
        Log.d(Config.TAG,"Preference changed: "+key);
        Config.recheckPreferences(this);
        if (Config.PREFS_MANET_ENGINE.equalsIgnoreCase(key)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
            builder.setTitle(R.string.shutdown_required);
            builder.setMessage(R.string.prefs_manet_changed_description);
            final AlertDialog dialog = builder.create();
            /*dialog.setButton(DialogInterface.BUTTON_POSITIVE, "Shutdown", (dialog1, which) -> {
                finish();
                if (SqAnService.getInstance() != null)
                    SqAnService.getInstance().requestShutdown(false);
            });*/
            dialog.show();
        } else if (Config.PREFS_VPN_FORWARD.equalsIgnoreCase(key)) {
            Config.recheckPreferences(this);
        } else if (Config.PREFS_SDR_LISTEN_ONLY.equalsIgnoreCase(key)) {
            Config.recheckPreferences(this);
        } else if (Config.PREFS_VPN_AUTO_ADD.equalsIgnoreCase(key)) {
            Config.recheckPreferences(this);
        } else if (Config.PREFS_VPN_MTU.equalsIgnoreCase(key)) {
            Config.recheckPreferences(this);
            AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
            builder.setTitle(R.string.shutdown_required);
            builder.setMessage(R.string.prefs_vpn_changed_description);
            final AlertDialog dialog = builder.create();
            dialog.show();
        } else if (Config.PREFS_VPN_MODE.equalsIgnoreCase(key)) {
            if (Config.isVpnEnabled())
                SqAnVpnService.start(SqAnService.getInstance());
            else
                SqAnVpnService.stop(SqAnService.getInstance());
        } else if (Config.PREFS_WRITE_LOG.equalsIgnoreCase(key)
                || (Config.PREFS_IGNORE_0_0_0_0.equalsIgnoreCase(key))) {
            Config.recheckPreferences(this);
            CommsLog.init(this);
        }
    };
}
