package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.ui.CoreSettingsFragment;

public class SettingsActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getFragmentManager().beginTransaction()
                .replace(android.R.id.content, new CoreSettingsFragment())
                .commit();
        PreferenceManager.getDefaultSharedPreferences(this).registerOnSharedPreferenceChangeListener((sharedPreferences, key) -> {
                if (Config.PREFS_MANET_ENGINE.equalsIgnoreCase(key)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(SettingsActivity.this);
                    builder.setTitle(R.string.shutdown_required);
                    builder.setMessage(R.string.prefs_manet_changed_description);
                    builder.setPositiveButton(R.string.shutdown, (dialog, which) -> SqAnService.shutdown(false));
                    final AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.show();
                }
            });
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
}
