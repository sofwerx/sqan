package org.sofwerx.sqandr.testing;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.SqANDRListener;
import org.sofwerx.sqandr.serial.TestService;

public class MainActivity extends AppCompatActivity implements SqANDRListener, PeripheralStatusListener {
    private TestService testService;
    private TextView textStatus;
    private EditText editCommands;
    private ImageView imagePlutoStatus,imageAppStatus;
    private PlutoStatus plutoStatus = PlutoStatus.OFF;
    private AppStatus appStatus = AppStatus.OFF;

    private enum PlutoStatus {OFF,ERROR,INSTALLING,UP}
    private enum AppStatus {OFF,HIDE,RUNNING}

    private final static String PREFS_COMMANDS = "cmds";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textStatus = findViewById(R.id.mainStatus);
        imagePlutoStatus = findViewById(R.id.mainStatusIcon);
        imageAppStatus = findViewById(R.id.mainStatusStartButton);
        imageAppStatus.setOnClickListener(v -> toggleAppStatus());
        editCommands = findViewById(R.id.mainCommands);
        testService = new TestService(this);
    }

    private void loadPrefs() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editCommands.setText(sharedPrefs.getString(PREFS_COMMANDS,null));
    }

    private void savePrefs() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor edit = sharedPrefs.edit();
        edit.putString(PREFS_COMMANDS,editCommands.getText().toString());
        edit.apply();
    }

    private void setPlutoStatus(String message) {
        runOnUiThread(() -> textStatus.setText(message));
    }

    private void toggleAppStatus() {
        //TODO
    }

    private void setStatus(AppStatus status) {
        if (this.appStatus != status) {
            this.appStatus = status;
            runOnUiThread(() -> {
                switch (appStatus) {
                    case OFF:
                        imageAppStatus.setImageResource(R.drawable.icon_up);
                        imageAppStatus.setVisibility(View.VISIBLE);
                        editCommands.setEnabled(true);
                        Toast.makeText(MainActivity.this,"SqANDR is off",Toast.LENGTH_SHORT).show();
                        break;

                    case RUNNING:
                        imageAppStatus.setImageResource(R.drawable.icon_busy);
                        imageAppStatus.setVisibility(View.VISIBLE);
                        editCommands.setEnabled(false);
                        Toast.makeText(MainActivity.this,"SqANDR is running",Toast.LENGTH_SHORT).show();
                        break;

                    default:
                        imageAppStatus.setVisibility(View.INVISIBLE);
                        editCommands.setEnabled(true);
                }
            });
        }
    }

    private void setStatus(PlutoStatus status) {
        if (this.plutoStatus != status) {
            this.plutoStatus = status;
            runOnUiThread(() -> {
                switch (status) {
                    case UP:
                        imagePlutoStatus.setImageResource(R.drawable.icon_up);
                        Toast.makeText(MainActivity.this,"Pluto is ready",Toast.LENGTH_SHORT).show();
                        break;

                    case INSTALLING:
                        imagePlutoStatus.setImageResource(R.drawable.icon_busy);
                        setStatus(AppStatus.HIDE);
                        Toast.makeText(MainActivity.this,"SqANDR is installing",Toast.LENGTH_LONG).show();
                        break;

                    case OFF:
                        imagePlutoStatus.setImageResource(R.drawable.icon_off);
                        Toast.makeText(MainActivity.this,"Pluto is not connected",Toast.LENGTH_LONG).show();
                        setStatus(AppStatus.HIDE);
                        break;

                    default:
                        imagePlutoStatus.setImageResource(R.drawable.icon_error);
                        setStatus(AppStatus.HIDE);
                }
            });
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPrefs();
        if (testService != null) {
            testService.setListener(this);
            testService.setPeripheralStatusListener(this);
        }
    }

    @Override
    public void onPause() {
        if (testService != null) {
            testService.setListener(null);
            testService.setPeripheralStatusListener(this);
        }
        savePrefs();
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (testService != null) {
            testService.shutdown();
            testService = null;
        }
        super.onDestroy();
    }

    @Override
    public void onSdrError(String message) {
        setPlutoStatus("ERROR: "+message);
        setStatus(PlutoStatus.ERROR);
        //TODO
    }

    @Override
    public void onSdrReady(boolean isReady) {
        setPlutoStatus("Pluto is ready");
        setStatus(AppStatus.RUNNING);
        //TODO
    }

    @Override
    public void onSdrMessage(String message) {
        setPlutoStatus(message);
        setStatus(PlutoStatus.INSTALLING);
        //TODO
    }

    @Override
    public void onPacketReceived(byte[] data) {
        //TODO
        setStatus(AppStatus.RUNNING);
    }

    @Override
    public void onPacketDropped() {
        //TODO
    }

    @Override
    public void onPeripheralMessage(String message) {
        setPlutoStatus(message);
    }

    @Override
    public void onPeripheralReady() {
        setPlutoStatus("Pluto is ready");
        setStatus(PlutoStatus.UP);
    }

    @Override
    public void onPeripheralError(String message) {
        setPlutoStatus("ERROR: "+message);
        setStatus(PlutoStatus.ERROR);
    }
}
