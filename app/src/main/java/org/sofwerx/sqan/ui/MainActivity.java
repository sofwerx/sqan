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
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ExceptionHelper;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.util.PermissionsHelper;
import org.sofwerx.sqan.util.StringUtil;

import static org.sofwerx.sqan.SqAnService.ACTION_STOP;

public class MainActivity extends AppCompatActivity implements SqAnStatusListener {
    protected static final int REQUEST_DISABLE_BATTERY_OPTIMIZATION = 401;
    private boolean permissionsNagFired = false; //used to request permissions from user

    protected boolean serviceBound = false;
    protected SqAnService sqAnService = null;
    private Switch switchActive;
    private boolean isSystemChangingSwitchActive = false;
    private TextView textResults;
    private TextView textTxTally, textNetType;
    private TextView textSysStatus;
    private ImageView iconSysStatus, iconSysInfo, iconMainTx;
    private View offlineStamp;
    private DevicesList devicesList;

    private boolean shownFirstSysWarning = false;

    @Override
    protected void onStart() {
        super.onStart();
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
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ExceptionHelper.set(getApplicationContext());
        switchActive = findViewById(R.id.mainSwitchActive);
        connectToBackend();
        if (Config.isAutoStart(this))
            switchActive.setChecked(true);
        else
            switchActive.setChecked(false);
        textResults = findViewById(R.id.mainTextTemp); //TODO temp
        textTxTally = findViewById(R.id.mainTxBytes);
        textSysStatus = findViewById(R.id.mainTextSysStatus);
        offlineStamp = findViewById(R.id.mainOfflineLabel);
        textNetType = findViewById(R.id.mainNetType);
        iconSysInfo = findViewById(R.id.mainSysStatusInfo);
        iconSysStatus = findViewById(R.id.mainSysStatusIcon);
        iconMainTx = findViewById(R.id.mainIconTxStatus);
        if (iconSysInfo != null)
            iconSysInfo.setOnClickListener(view -> SystemStatusDialog.show(MainActivity.this));
        textNetType.setText(null);
        switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isSystemChangingSwitchActive) {
                Config.setAutoStart(MainActivity.this,isChecked);
                if (serviceBound && (sqAnService != null) && (sqAnService.getManetOps() != null))
                    sqAnService.getManetOps().setActive(isChecked);
            }
            updateManetTypeDisplay();
            updateActiveIndicator();
        });
        devicesList = findViewById(R.id.mainDevicesList);
    }

    private void updateTransmitText() {
        textTxTally.setText("Tx: "+StringUtil.toDataSize(ManetOps.getTransmittedByteTally()));
    }

    @Override
    public void onResume() {
        super.onResume();
        registerListeners();
        updateManetTypeDisplay();
        if (!permissionsNagFired) {
            permissionsNagFired = true;
            openBatteryOptimizationDialogIfNeeded();
            PermissionsHelper.checkForPermissions(this);
        }
        updateTransmitText();
        updateSysStatusText();
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterListeners();
    }

    @Override
    protected void onStop() {
        super.onStop();
    }

    @Override
    public void onDestroy() {
        if (serviceBound) {
            unregisterListeners();
            unbindService(mConnection);
            serviceBound = false;
        }
        super.onDestroy();
    }

    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    private void updateActiveIndicator() {
        boolean active = false;
        if (serviceBound && (sqAnService != null) && (sqAnService.getManetOps() != null) && (sqAnService.getManetOps().getManet() != null)) {
            active = sqAnService.getManetOps().getManet().isRunning();
        }
        isSystemChangingSwitchActive = true;
        switchActive.setChecked(active);
        isSystemChangingSwitchActive = false;
        if (offlineStamp != null)
            offlineStamp.setVisibility(active?View.INVISIBLE:View.VISIBLE);
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

    private void updateManetTypeDisplay() {
        if ((textNetType.getText().toString() == null) || (textNetType.getText().toString().length() < 3)) {
            if (serviceBound && (sqAnService != null)) {
                AbstractManet manet = sqAnService.getManetOps().getManet();
                if (manet != null)
                     textNetType.setText(" Core: "+manet.getName());
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (serviceBound) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.quit)
                    .setMessage(R.string.quit_narrative)
                    .setNegativeButton(R.string.quit_yes, (dialog, which) -> {
                        if (serviceBound && (sqAnService != null))
                            sqAnService.requestShutdown(false);
                        else {
                            disconnectBackend();
                            MainActivity.this.finish();
                        }
                    })
                    .setPositiveButton(R.string.quit_run_in_background, (arg0, arg1) -> MainActivity.this.finish()).create().show();
        } else
            finish();
    }

    protected ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(Config.TAG,"SqAnService bound to this activity");
            SqAnService.SqAnServiceBinder binder = (SqAnService.SqAnServiceBinder) service;
            sqAnService = binder.getService();
            serviceBound = true;
            registerListeners();
            updateManetTypeDisplay();
            updateSysStatusText();
            updateActiveIndicator();
            if (sqAnService.getManetOps() != null)
                updateMainStatus(sqAnService.getManetOps().getStatus());
            else
                updateMainStatus(Status.ERROR);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            serviceBound = false;
        }
    };

    public void startSqAnServiceIfNeeded() {
        startService(new Intent(this, SqAnService.class));
    }

    public void connectToBackend() {
        startSqAnServiceIfNeeded();
        Intent intent = new Intent(this, SqAnService.class);
        bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void disconnectBackend() {
        if (serviceBound) {
            if (mConnection != null)
                unbindService(mConnection);
            Intent intentAction = new Intent(this, SqAnService.class);
            intentAction.setAction(ACTION_STOP);
            intentAction.putExtra(SqAnService.EXTRA_KEEP_ACTIVITY, true);
            startService(intentAction);
            serviceBound = false;
            sqAnService = null;
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

    private void updateSysStatusText() {
        if (textSysStatus != null) {
            if (SqAnService.hasSystemIssues()) {
                if (SqAnService.hasBlockerSystemIssues()) {
                    if (iconSysStatus != null) {
                        iconSysStatus.setImageResource(R.drawable.ic_arrow_down);
                        iconSysStatus.setColorFilter(getColor(R.color.bright_red));
                    }
                    textSysStatus.setText("Down");
                    textSysStatus.setTextColor(getColor(R.color.bright_red));
                } else {
                    if (iconSysStatus != null) {
                        iconSysStatus.setImageResource(R.drawable.ic_arrow_right);
                        iconSysStatus.setColorFilter(getColor(R.color.yellow));
                    }
                    textSysStatus.setText("Degraded");
                    textSysStatus.setTextColor(getColor(R.color.yellow));
                }
                if (!shownFirstSysWarning) {
                    shownFirstSysWarning = true;
                    SystemStatusDialog.show(MainActivity.this);
                }
            } else {
                if (iconSysStatus != null) {
                    iconSysStatus.setImageResource(R.drawable.ic_arrow_up);
                    iconSysStatus.setColorFilter(getColor(R.color.white_hint_green));
                }
                textSysStatus.setText("Up");
                textSysStatus.setTextColor(getColor(R.color.white_hint_green));
            }
        }
    }

    private void updateMainStatus(Status status) {
        if (iconMainTx != null) {
            switch (status) {
                case OFF:
                    iconMainTx.setImageResource(R.drawable.icon_off);
                    break;

                case CONNECTED:
                    iconMainTx.setImageResource(R.drawable.icon_link);
                    break;

                case ADVERTISING:
                case DISCOVERING:
                case CHANGING_MEMBERSHIP:
                case ADVERTISING_AND_DISCOVERING:
                    iconMainTx.setImageResource(R.drawable.icon_link_white);
                    break;

                default:
                    iconMainTx.setImageResource(R.drawable.icon_link_broken);
            }
        }
    }

    @Override
    public void onStatus(final Status status) {
        runOnUiThread(() -> {
            textResults.setText("Status is: "+StatusHelper.getName(status));
            updateManetTypeDisplay();
            updateActiveIndicator();
            updateMainStatus(status);
        });
    }

    @Override
    public void onNodesChanged(SqAnDevice device) {
        devicesList.update(device);
    }

    @Override
    public void onDataTransmitted() {
        runOnUiThread(() -> updateTransmitText());
    }

    @Override
    public void onSystemReady(boolean isReady) {
        runOnUiThread(() -> updateSysStatusText());
    }
}