package org.sofwerx.sqan.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ExceptionHelper;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.Status;
import org.sofwerx.sqan.util.PermissionsHelper;

public class MainActivity extends AppCompatActivity implements SqAnStatusListener {
    protected static final int REQUEST_DISABLE_BATTERY_OPTIMIZATION = 401;
    private boolean permissionsNagFired = false; //used to request permissions from user

    protected boolean serviceBound = false;
    protected SqAnService sqAnService = null;

    @Override
    protected void onStart() {
        super.onStart();
        connectToBackend();
    }

    protected boolean isOptimizingBattery() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            final PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            return pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
        } else
            return false;
    }

    protected boolean isAffectedByDataSaver() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            final ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            return cm != null && cm.isActiveNetworkMetered() && cm.getRestrictBackgroundStatus() == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED;
        } else
            return false;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (serviceBound && (sqAnService != null))
            registerListeners();
        else
            connectToBackend();
        if (!permissionsNagFired) {
            permissionsNagFired = true;
            openBatteryOptimizationDialogIfNeeded();
            PermissionsHelper.checkForPermissions(this);
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;

            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ExceptionHelper.set(getApplicationContext());
        startService(new Intent(this,SqAnService.class));//TODO temp
    }

    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
             .setTitle(R.string.quit)
             .setMessage(R.string.quit_narrative)
             .setNegativeButton(R.string.quit_yes, (dialog, which) -> {
                 if (serviceBound && (sqAnService != null))
                     sqAnService.requestShutdown();
                 MainActivity.this.finish();
             })
             .setPositiveButton(R.string.quit_run_in_background, (arg0, arg1) -> MainActivity.this.finish()).create().show();
    }

    protected ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(Config.TAG,"MdxService bound to this activity");
            SqAnService.SqAnServiceBinder binder = (SqAnService.SqAnServiceBinder) service;
            sqAnService = binder.getService();
            serviceBound = true;
            registerListeners();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            serviceBound = false;
        }
    };

    public void startMdxServiceIfNeeded() {
        startService(new Intent(this, SqAnService.class));
    }

    public void connectToBackend() {
        startMdxServiceIfNeeded();
        Intent intent = new Intent(this, SqAnService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterListeners();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (serviceBound) {
            unregisterListeners();
            unbindService(mConnection);
            serviceBound = false;
        }
    }

    private void unregisterListeners() {
        if (serviceBound && (sqAnService != null))
            sqAnService.setListener(null);
    }

    private void registerListeners() {
        if (serviceBound && (sqAnService != null))
            sqAnService.setListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    private void openBatteryOptimizationDialogIfNeeded() {
        if (isOptimizingBattery() && Config.isAllowAskAboutBattery(this)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.enable_battery_optimization);
            builder.setMessage(R.string.battery_optimizations_narrative);
            builder.setPositiveButton(R.string.battery_optimize_yes, (dialog, which) -> {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                Uri uri = Uri.parse("package:" + getPackageName());
                intent.setData(uri);
                try {
                    startActivityForResult(intent, REQUEST_DISABLE_BATTERY_OPTIMIZATION);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.does_not_support_battery_optimization, Toast.LENGTH_SHORT).show();
                }
            });
            builder.setOnDismissListener(dialog -> Config.setNeverAskBatteryOptimize(this));
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_DISABLE_BATTERY_OPTIMIZATION:
                Config.setNeverAskBatteryOptimize(this);
                break;
        }
    }
}