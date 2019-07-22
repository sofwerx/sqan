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

import org.sofwerx.sqandr.serial.TestService;

import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements TestListener {
    private TestService testService;
    private TextView textStatus;
    private EditText editCommands, editPktSize, editPktDelay;
    private ImageView imagePlutoStatus,imageAppStatus;
    private PlutoStatus plutoStatus = PlutoStatus.OFF;
    private SqandrStatus appStatus = SqandrStatus.OFF;
    private TextView pktMeComplete,pktMeUnique,pktMeMissed,pktMeSuccess;
    private TextView pktOtherComplete,pktOtherUnique,pktOtherMissed,pktOtherSuccess;
    private TextView pktPartial,pktSegments;
    private View tableSpecs;
    private Timer autoUpdate;
    private ImageView buttonSend;

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
        pktMeComplete = findViewById(R.id.meComplete);
        pktMeUnique = findViewById(R.id.meUnique);
        pktMeMissed = findViewById(R.id.meMissed);
        pktMeSuccess = findViewById(R.id.meSuccessRate);
        pktOtherComplete = findViewById(R.id.otherComplete);
        pktOtherUnique = findViewById(R.id.otherUnique);
        pktOtherMissed = findViewById(R.id.otherMissed);
        pktOtherSuccess = findViewById(R.id.otherSuccessRate);
        pktPartial = findViewById(R.id.unkPartial);
        pktSegments = findViewById(R.id.unkSegments);
        editPktSize = findViewById(R.id.mainPktSize);
        editPktDelay = findViewById(R.id.mainPktDelay);
        tableSpecs = findViewById(R.id.mainTableSendSpecs);
        buttonSend = findViewById(R.id.mainToggleSend);
        buttonSend.setOnClickListener(v -> {
            if (testService != null) {
                if (testService.getAppRunning())
                    testService.setAppRunning(false);
                else {
                    try {
                        TestPacket.setPacketSize(Integer.parseInt(editPktSize.getText().toString()));
                        testService.setIntervalBetweenTx(Long.parseLong(editPktDelay.getText().toString()));
                    } catch (NumberFormatException ignore) {
                    }
                    testService.setAppRunning(true);
                }
            }
            updateSendButton();
        });
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
        if ((appStatus != SqandrStatus.PENDING) && (testService != null)) {
            testService.setCommandFlags(editCommands.getText().toString());
            testService.setAppRunning(appStatus != SqandrStatus.RUNNING);
            setStatus(SqandrStatus.PENDING);
        }
    }

    private void setStatus(SqandrStatus status) {
        if (this.appStatus != status) {
            this.appStatus = status;
            runOnUiThread(() -> {
                switch (appStatus) {
                    case OFF:
                        imageAppStatus.setImageResource(android.R.drawable.ic_media_play);
                        imageAppStatus.setVisibility(View.VISIBLE);
                        editCommands.setEnabled(true);
                        Toast.makeText(MainActivity.this,"SqANDR is off",Toast.LENGTH_SHORT).show();
                        break;

                    case RUNNING:
                        imageAppStatus.setImageResource(android.R.drawable.ic_media_pause);
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
                        setStatus(SqandrStatus.PENDING);
                        Toast.makeText(MainActivity.this,"SqANDR is installing",Toast.LENGTH_LONG).show();
                        break;

                    case OFF:
                        imagePlutoStatus.setImageResource(R.drawable.icon_off);
                        Toast.makeText(MainActivity.this,"Pluto is not connected",Toast.LENGTH_LONG).show();
                        setStatus(SqandrStatus.PENDING);
                        break;

                    default:
                        imagePlutoStatus.setImageResource(R.drawable.icon_error);
                        setStatus(SqandrStatus.PENDING);
                }
            });
        }
    }

    private void updateStats() {
        if (testService == null)
            return;
        runOnUiThread(() -> {
            Stats stats = testService.getStats();
            if ((stats == null) || (stats.statsMe == null) || (stats.statsOther == null)) {
                tableSpecs.setVisibility(View.INVISIBLE);
                return;
            }
            tableSpecs.setVisibility(View.VISIBLE);
            pktMeComplete.setText(Integer.toString(stats.statsMe.getComplete()));
            pktMeUnique.setText(Integer.toString(stats.statsMe.getUnique()));
            pktMeMissed.setText(Integer.toString(stats.statsMe.getTotal()-stats.statsMe.getUnique()));
            pktMeSuccess.setText(Integer.toString(stats.statsMe.getSuccessRate()));
            pktOtherComplete.setText(Integer.toString(stats.statsOther.getComplete()));
            pktOtherUnique.setText(Integer.toString(stats.statsOther.getUnique()));
            pktOtherMissed.setText(Integer.toString(stats.statsOther.getTotal()-stats.statsOther.getUnique()));
            pktOtherSuccess.setText(Integer.toString(stats.statsOther.getSuccessRate()));
            pktSegments.setText(Integer.toString(stats.segments));
            pktPartial.setText(Integer.toString(stats.partialPackets));
        });
    }

    private void updateSendButton() {
        runOnUiThread(() -> {
            if (testService == null)
                buttonSend.setVisibility(View.INVISIBLE);
            switch (testService.getAppStatus()) {
                case RUNNING:
                    buttonSend.setImageResource(R.drawable.icon_sending);
                    buttonSend.setVisibility(View.VISIBLE);
                    break;

                case OFF:
                    buttonSend.setImageResource(R.drawable.icon_not_sending);
                    buttonSend.setVisibility(View.VISIBLE);

                default:
                    buttonSend.setVisibility(View.INVISIBLE);
                    break;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        loadPrefs();
        updateSendButton();
        if (testService != null)
            testService.setListener(this);
        autoUpdate = new Timer();
        autoUpdate.schedule(new TimerTask() {
            @Override
            public void run() {
                runOnUiThread(() -> updateStats());
            }
        }, 0, 1000);
    }

    @Override
    public void onPause() {
        if (autoUpdate != null)
            autoUpdate.cancel();
        if (testService != null)
            testService.setListener(null);
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
    public void onDataReassembled(byte[] payloadData) {
        //ignore
    }

    @Override
    public void onPacketDropped() {
        //ignore
    }

    @Override
    public void onReceivedSegment() {
        //ignore
    }

    @Override
    public void onSqandrStatus(SqandrStatus status, String message) {
        setStatus(status);
        if (message != null)
            setPlutoStatus(message);
        updateSendButton();
    }

    @Override
    public void onPlutoStatus(PlutoStatus status, String message) {
        setStatus(status);
        if (message != null)
            setPlutoStatus(message);
    }
}
