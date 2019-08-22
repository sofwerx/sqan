package org.sofwerx.sqan.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.provider.Settings;
import androidx.appcompat.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.LocationService;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.bt.BtManetV2;
import org.sofwerx.sqan.manet.bt.helper.BTSocket;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.manet.common.pnt.SpaceTime;
import org.sofwerx.sqan.manet.sdr.SdrManet;
import org.sofwerx.sqan.util.PermissionsHelper;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqan.vpn.SqAnVpnService;

import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;

import static org.sofwerx.sqan.SqAnService.ACTION_STOP;

public class MainActivity extends AppCompatActivity implements SqAnStatusListener, PeripheralStatusListener {
    private final static String TAG = Config.TAG+".UI";
    protected static final int REQUEST_DISABLE_BATTERY_OPTIMIZATION = 401;
    private final static long REPORT_PROBLEMS_DURATION = 1000l * 60l;
    private final static long PERIODIC_REFRESH_INTERVAL = 1000l * 5l;
    private boolean permissionsNagFired = false; //used to request permissions from user

    protected boolean serviceBound = false;
    protected SqAnService sqAnService = null;
    //private Switch switchActive;
    private boolean isSystemChangingSwitchActive = false;
    private TextView textResults;
    private TextView textTxTally, textNetType;
    private TextView textSysStatus;
    private TextView statusMarquee, textOverall;
    private TextView roleWiFi, roleBT, roleSDR, roleSDRstale, roleBackhaul, textLocation;
    private TextView statusPerphieral;
    private View statusPeripheralView, statusPeriphProgress;
    private ImageView iconSysStatus, iconSysInfo, iconMainTx, iconPing, iconPeriphProblem;
    private View offlineStamp;
    private DevicesList devicesList;
    private long lastTxTotal = 0l;
    private Animation pingAnimation;
    private Timer autoUpdate;
    private boolean nagAboutPreferredManet = true;
    private boolean nagAboutIncompleteSetup = true;

    private ArrayList<String> marqueeMessages = new ArrayList<>();

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
        //switchActive = findViewById(R.id.mainSwitchActive);
        connectToBackend();
        /*if (Config.isAutoStart(this))
            switchActive.setChecked(true);
        else
            switchActive.setChecked(false);*/
        textResults = findViewById(R.id.mainTextTemp); //TODO temp
        textTxTally = findViewById(R.id.mainTxBytes);
        textSysStatus = findViewById(R.id.mainTextSysStatus);
        offlineStamp = findViewById(R.id.mainOfflineLabel);
        textNetType = findViewById(R.id.mainNetType);
        iconSysInfo = findViewById(R.id.mainSysStatusInfo);
        iconSysStatus = findViewById(R.id.mainSysStatusIcon);
        iconPing = findViewById(R.id.mainPing);
        textLocation = findViewById(R.id.mainLocation);
        iconMainTx = findViewById(R.id.mainIconTxStatus);
        statusMarquee = findViewById(R.id.mainStatusMarquee);
        textOverall = findViewById(R.id.mainDescribeOverall);
        roleWiFi = findViewById(R.id.mainRoleWiFi);
        roleBT = findViewById(R.id.mainRoleBT);
        roleSDR = findViewById(R.id.mainRoleSDR);
        roleSDRstale = findViewById(R.id.mainRoleSDRstale);
        roleBackhaul = findViewById(R.id.mainBackhaul);
        statusPeripheralView = findViewById(R.id.mainStatusPeripheral);
        statusPerphieral = findViewById(R.id.mainStatusPeripheralStatus);
        statusPeriphProgress = findViewById(R.id.mainStatusPeripheralProgress);
        iconPeriphProblem = findViewById(R.id.mainStatusPeripheralWarning);
        pingAnimation = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.ping);
        statusPeripheralView.setOnClickListener(v -> statusPeripheralView.setVisibility(View.GONE));

        if (statusMarquee != null) {
            statusMarquee.setOnClickListener(view -> {
                Intent intent = new Intent(MainActivity.this,AboutActivity.class);
                intent.putExtra("logs",true);
                startActivity(intent);
            });
        }
        if (iconSysInfo != null)
            iconSysInfo.setOnClickListener(view -> SystemStatusDialog.show(MainActivity.this));
        if (textNetType != null) {
            textNetType.setText(null);
            textNetType.setOnClickListener(v ->
                    startActivity(new Intent(MainActivity.this,SettingsActivity.class)));
        }
        /*switchActive.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!isSystemChangingSwitchActive) {
                Config.setAutoStart(MainActivity.this,isChecked);
                if (serviceBound && (sqAnService != null) && (sqAnService.getManetOps() != null))
                    sqAnService.getManetOps().setActive(isChecked);
            }
            updateManetTypeDisplay();
            updateActiveIndicator();
        });*/
        devicesList = findViewById(R.id.mainDevicesList);
    }

    private void updateLocation() {
        SqAnDevice device = Config.getThisDevice();
        if (device == null)
            textLocation.setVisibility(View.INVISIBLE);
        else {
            SpaceTime space = device.getLastLocation();
            if (space == null)
                textLocation.setVisibility(View.INVISIBLE);
            else {
                if (space.hasAccuracy())
                    textLocation.setText("±"+Math.round(space.getAccuracy())+"m");
                else
                    textLocation.setText(null);
                textLocation.setVisibility(View.VISIBLE);
            }
        }
    }

    //private long marqueUpdate = Long.MIN_VALUE;
    private String[] marqueStatuses = new String[10];
    private void addMarqueStatus(String status) {
        //marqueUpdate = System.currentTimeMillis();
        for (int i = 1; i< marqueStatuses.length; i++) {
            marqueStatuses[i-1]= marqueStatuses[i];
        }
        marqueStatuses[marqueStatuses.length-1] = StringUtil.getFormattedJustTime(System.currentTimeMillis())+": "+status;
        runOnUiThread(() -> updateStatusMarquee());
    }

    private void updateStatusMarquee() {
        if (marqueStatuses.length < 1) {
            statusMarquee.setText(null);
            return;
        }
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (int i=marqueStatuses.length-1;i>=0;i--) {
            if (marqueStatuses[i] != null) {
                if (first)
                    first = false;
                else
                    out.append("\n");
                out.append(marqueStatuses[i]);
            }
        }

        statusMarquee.setText(out.toString());
        statusMarquee.setSelected(true);
        SqAnDevice device = Config.getThisDevice();
        if (device != null) {
            switch (device.getRoleWiFi()) {
                case HUB:
                    roleWiFi.setText("Hub");
                    roleWiFi.setVisibility(View.VISIBLE);
                    break;

                case SPOKE:
                    roleWiFi.setText("Spoke");
                    roleWiFi.setVisibility(View.VISIBLE);
                    break;

                default:
                    roleWiFi.setVisibility(View.INVISIBLE);
            }
            switch (device.getRoleBT()) {
                case HUB:
                    roleBT.setText("Hub");
                    roleBT.setVisibility(View.VISIBLE);
                    break;

                case SPOKE:
                    roleBT.setText("Spoke");
                    roleBT.setVisibility(View.VISIBLE);
                    break;

                case BOTH:
                    roleBT.setText("Hub & Spoke");
                    roleBT.setVisibility(View.VISIBLE);
                    break;

                default:
                    roleBT.setVisibility(View.INVISIBLE);

            }
            if (BTSocket.isCongested())
                roleBT.setTextColor(getColor(R.color.yellow));
            else
                roleBT.setTextColor(getColor(R.color.green));
            roleBackhaul.setVisibility(device.isBackhaulConnection()?View.VISIBLE:View.INVISIBLE);
        } else {
            roleWiFi.setVisibility(View.INVISIBLE);
            roleBT.setVisibility(View.INVISIBLE);
            roleBackhaul.setVisibility(View.INVISIBLE);
        }
        if ((sqAnService != null) && sqAnService.isSdrManetAvailable()) {
            if (sqAnService.isSdrManetActive()) {
                roleSDRstale.setVisibility(View.INVISIBLE);
                roleSDR.setVisibility(View.VISIBLE);
            } else
                roleSDRstale.setVisibility(View.VISIBLE);
        } else {
            roleSDR.setVisibility(View.INVISIBLE);
            roleSDRstale.setVisibility(View.INVISIBLE);
        }
    }

    private void updateTransmitText() {
        long currentTotal = ManetOps.getTransmittedByteTally();
        if (currentTotal != lastTxTotal) {
            lastTxTotal = currentTotal;
            iconPing.setAlpha(1f);
            iconPing.startAnimation(pingAnimation);
        }
        textTxTally.setText("Tx: "+StringUtil.toDataSize(currentTotal));
    }

    private void updateCallsignText() {
        if ((Config.getThisDevice() == null) || (Config.getThisDevice().getCallsign() == null))
            setTitle(R.string.app_name);
        else
            setTitle(Config.getThisDevice().getCallsign());
    }

    @Override
    public void onResume() {
        super.onResume();
        updateCallsignText();
        updatePeripheralStatus(null,true,false);
        registerListeners();
        updateManetTypeDisplay();
        if (!permissionsNagFired) {
            permissionsNagFired = true;
            openBatteryOptimizationDialogIfNeeded();
            PermissionsHelper.checkForPermissions(this);
        }
        updateTransmitText();
        updateSysStatusText();
        updateStatusMarquee();
        devicesList.update(null);
        checkForLocationServices();
        updateLocation();
        autoUpdate = new Timer();
        autoUpdate.schedule(new TimerTask() {
            @Override
            public void run() {
                periodicHelper();
            }
        }, PERIODIC_REFRESH_INTERVAL, PERIODIC_REFRESH_INTERVAL);
    }

    private void checkForTeammateIssues() {
        if (nagAboutIncompleteSetup && Config.isWarnIncompleteEnabled()) {
            nagAboutIncompleteSetup = false;
            if ((sqAnService != null) && (sqAnService.isOnlySdr()))
                return;

            //check for missing teammate info
            ArrayList<SavedTeammate> teammates = Config.getSavedTeammates();
            if ((teammates == null) || teammates.isEmpty()) {
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.no_teammates_alert);
                builder.setMessage(R.string.no_teammates_alert_narrative);
                builder.setPositiveButton(R.string.set_teammates, (dialog, which) -> startActivity(new Intent(MainActivity.this,StoredTeammatesActivity.class)));
                builder.setNeutralButton(R.string.ignore, (dialog, which) -> dialog.cancel());
                builder.setNegativeButton(R.string.never_ask, (dialog, which) -> {
                    Config.setWarnIncompleteEnabled(false);
                    dialog.cancel();
                });
                final AlertDialog dialog = builder.create();
                dialog.setCanceledOnTouchOutside(true);
                dialog.show();
            } else {
                boolean checkBt = true;
                boolean checkWiFi = true;
                if ((sqAnService != null) && (sqAnService.getManetOps() != null)) {
                    checkBt = sqAnService.getManetOps().isBtManetSelected();
                    checkWiFi = sqAnService.getManetOps().isWiFiDirectManetSelected();
                }
                int teammatesMissing = 0;
                int totalTeammates = 0;
                synchronized (teammates) {
                    for (SavedTeammate teammate:teammates) {
                        if (teammate.isEnabled()) {
                            totalTeammates++;
                            if (teammate.isIncomplete(checkBt,checkWiFi))
                                teammatesMissing++;
                        }
                    }
                }

                if (teammatesMissing > 0) {
                    final String missing;
                    if (teammatesMissing==1)
                        missing = "one teammate (out of "+totalTeammates+")";
                    else
                        missing = teammatesMissing+" teammates (out of "+totalTeammates+")";
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.teammates_fix_alert);
                    builder.setMessage("SqAN has incomplete info for "+missing+". Without this info, SqAN could use complete info to make a stronger mesh.");
                    builder.setPositiveButton(R.string.fix_teammates, (dialog, which) -> startActivity(new Intent(MainActivity.this,StoredTeammatesActivity.class)));
                    builder.setNeutralButton(R.string.ignore, (dialog, which) -> dialog.cancel());
                    builder.setNegativeButton(R.string.never_ask, (dialog, which) -> {
                        Config.setWarnIncompleteEnabled(false);
                        dialog.cancel();
                    });
                    final AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(true);
                    dialog.show();
                }
            }
        }
    }

    private void periodicHelper() {
        runOnUiThread(() -> {
            updateLocation();
            onNodesChanged(null);
            //updateTransmitText();
            //updateSysStatusText();
            updateStatusMarquee();
        });
    }

    @Override
    public void onPause() {
        super.onPause();
        autoUpdate.cancel();
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

    private void updateOverallMeshHealth() {
        if (textOverall != null) {
            switch (ManetOps.getOverallMeshStatus()) {
                case UP:
                    textOverall.setText("Mesh\nUP");
                    textOverall.setTextColor(getColor(R.color.white_hint_green));
                    break;
                case DEGRADED:
                    textOverall.setText("Mesh\nDEGRADED");
                    textOverall.setTextColor(getColor(R.color.yellow));
                    break;
                default:
                    textOverall.setText("Mesh\nDOWN");
                    textOverall.setTextColor(getColor(R.color.light_red));
                    break;
            }
        }
    }

    private void updateActiveIndicator() {
        boolean active = false;
        if (serviceBound && (sqAnService != null) && (sqAnService.getManetOps() != null)) {
            AbstractManet btManet = sqAnService.getManetOps().getBtManet();
            AbstractManet wifiManet = sqAnService.getManetOps().getWifiManet();
            AbstractManet sdrManet = sqAnService.getManetOps().getSdrManet();
            if (btManet != null)
                active = active || btManet.isRunning();
            if (wifiManet != null)
                active = active || wifiManet.isRunning();
            if (sdrManet != null)
                active = active || sdrManet.isRunning();
        }
        isSystemChangingSwitchActive = true;
        //switchActive.setChecked(active);
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

            case R.id.action_saved_teammates:
                startActivity(new Intent(this, StoredTeammatesActivity.class));
                return true;

            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void updateManetTypeDisplay() {
        if ((textNetType.getText().toString() == null) || (textNetType.getText().toString().length() < 3)) {
            int manetType = 0;
            try {
                manetType = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(this).getString(Config.PREFS_MANET_ENGINE,"4"));
            } catch (NumberFormatException e) {
            }
            textNetType.setText(" Core: "+getResources().getStringArray(R.array.listManetTypes)[manetType-1]);
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

    private void checkForSettingsIssues() {
        //see if we're using the preferred MANET (currently "Bluetooth Only")
        if (nagAboutPreferredManet) {
            if ((sqAnService != null) && (sqAnService.getManetOps() != null)) {
                AbstractManet manet = sqAnService.getManetOps().getBtManet();
                SdrManet sdrManet = sqAnService.getManetOps().getSdrManet();
                if (sdrManet != null) {
                    final String issues = sdrManet.getSystemIssues();
                    if (issues != null) {
                        new AlertDialog.Builder(this)
                                .setTitle("SDR Configuration")
                                .setMessage("Your SDR Configuration is not recommended. "+issues)
                                .setPositiveButton("Change", (dialog, which) -> {
                                    startActivity(new Intent(this, SettingsActivity.class));
                                })
                                .setNegativeButton("Ignore", (dialog, which) -> dialog.cancel()).create().show();
                    }
                }
                if ((manet == null) || (manet instanceof BtManetV2)) {
                    //ignore as this is the recommended option
                } else {
                    nagAboutPreferredManet = false;
                    new AlertDialog.Builder(this)
                            .setTitle("Not the recommended core approach")
                            .setMessage("You are not currently using a recommended core MANET approach. Do you want to switch approaches (you will need to start the app again)?")
                            .setPositiveButton("Switch to Bluetooth Only", (dialog, which) -> {
                                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(MainActivity.this);
                                SharedPreferences.Editor edit = prefs.edit();
                                edit.putString(Config.PREFS_MANET_ENGINE,"4");
                                edit.commit();
                                if (serviceBound && (sqAnService != null))
                                    sqAnService.requestShutdown(false);
                                else {
                                    disconnectBackend();
                                    MainActivity.this.finish();
                                }
                            })
                            .setNegativeButton("Ignore", (dialog, which) -> dialog.cancel()).create().show();
                }
            }
        }
    }

    protected ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.d(Config.TAG,"SqAnService bound to this activity");
            SqAnService.SqAnServiceBinder binder = (SqAnService.SqAnServiceBinder) service;
            sqAnService = binder.getService();
            serviceBound = true;
            registerListeners();
            addMarqueStatus("Status is: "+StatusHelper.getName(sqAnService.getStatus()));
            updateManetTypeDisplay();
            updateSysStatusText();
            updateActiveIndicator();
            updateOverallMeshHealth();
            updateCallsignText();
            checkForTeammateIssues();
            if (sqAnService.getManetOps() != null)
                updateMainStatus(sqAnService.getManetOps().getStatus());
            else
                updateMainStatus(Status.ERROR);
            checkForSettingsIssues();
            if (Config.isVpnEnabled()) {
                Intent intent = VpnService.prepare(MainActivity.this);
                if (intent != null)
                    startActivityForResult(intent, SqAnService.REQUEST_ENABLE_VPN);
            }

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
        Log.d(Config.TAG,"MainActivity.unregisterListeners()");
        if (serviceBound && (sqAnService != null)) {
            sqAnService.setListener(null);
            sqAnService.setPeripheralStatusListener(null);
        }
    }

    private void registerListeners() {
        if (serviceBound && (sqAnService != null)) {
            Log.d(Config.TAG,"MainActivity.registerListeners()");
            sqAnService.setListener(this);
            sqAnService.setPeripheralStatusListener(this);
        } else
            Log.d(Config.TAG,"MainActivity.registerListeners() ignored (not bound)");
    }

    private void checkForLocationServices() {
        if (!LocationService.isLocationEnabled(this)) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle(R.string.loc_services_needed_title);
            builder.setMessage(R.string.loc_services_needed_description);
            builder.setPositiveButton(R.string.loc_services_enable, (dialog, which) -> {
                try {
                    startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, "Sorry, I could not find the location settings", Toast.LENGTH_LONG).show();
                }
            });
            final AlertDialog dialog = builder.create();
            dialog.setCanceledOnTouchOutside(false);
            dialog.show();
        }
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

            case SqAnService.REQUEST_ENABLE_VPN:
                if (serviceBound && (sqAnService != null))
                    SqAnVpnService.start(sqAnService);
                else
                    SqAnVpnService.start(MainActivity.this);
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
            String statusString = "Status is: "+StatusHelper.getName(status);
            textResults.setText(statusString);
            addMarqueStatus(statusString);
            updateManetTypeDisplay();
            updateActiveIndicator();
            updateMainStatus(status);
            updateStatusMarquee();
            onNodesChanged(null);
        });
    }

    @Override
    public void onNodesChanged(SqAnDevice device) {
        runOnUiThread(() -> {
            devicesList.update(device);
            //if (device != null) {
            //    addMarqueStatus(device.getLabel()+" status "+device.getStatus().name());
            //    updateStatusMarquee();
            //}
            updateOverallMeshHealth();
        });
    }

    @Override
    public void onDataTransmitted() {
        runOnUiThread(() -> updateTransmitText());
    }

    @Override
    public void onSystemReady(boolean isReady) {
        runOnUiThread(() -> {
            addMarqueStatus("System is ready");
            updateSysStatusText();
            updateStatusMarquee();
        });
    }

    @Override
    public void onConflict(SqAnDevice conflictingDevice) {
        //FIXME do something other than toast this
        Toast.makeText(this,"Error: this device has the same identifier as "+conflictingDevice.getCallsign(),Toast.LENGTH_LONG).show();
    }

    @Override
    public void onHighNoise(final float snr) {
        Log.d(TAG,"onHighNoise()");
        runOnUiThread(() -> updatePeripheralStatus("SqAN is receiving large amounts of corrupted data (SNR = "+String.format("%.02f", snr)+"). Check connections and RF environment.",false,true));
    }

    @Override
    public void onPeripheralMessage(String message) {
        updatePeripheralStatus(message,false,false);
    }

    @Override
    public void onPeripheralReady() {
        Log.d(Config.TAG,"onPeripheralReady()");
        updatePeripheralStatus(null,true, false);
    }

    @Override
    public void onPeripheralError(String message) {
        updatePeripheralStatus(message,false, true);
    }

    private void updatePeripheralStatus(final String message, final boolean ready, final boolean isError) {
        this.runOnUiThread(() -> {
            if (ready) {
                statusPeripheralView.setVisibility(View.GONE);
                addMarqueStatus("Connected device is operational");
            } else {
                if (message != null) {
                    statusPerphieral.setText(message);
                    addMarqueStatus(message);
                    if (isError) {
                        iconPeriphProblem.setVisibility(View.VISIBLE);
                        statusPeriphProgress.setVisibility(View.GONE);
                    } else {
                        iconPeriphProblem.setVisibility(View.GONE);
                        statusPeriphProgress.setVisibility(View.VISIBLE);
                    }
                }
                statusPeripheralView.setVisibility(View.VISIBLE);
            }
        });
    }
}