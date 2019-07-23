package org.sofwerx.sqandr.testing;

import androidx.appcompat.app.AppCompatActivity;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.serial.TestService;
import org.sofwerx.sqandr.util.StringUtils;

import java.nio.ByteBuffer;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements TestListener {
    private final static String TAG = Config.TAG+".ui";
    private TestService testService;
    private TextView textStatus;
    private EditText editCommands, editPktSize, editPktDelay;
    private ImageView imagePlutoStatus,imageAppStatus;
    private PlutoStatus plutoStatus = PlutoStatus.OFF;
    private SqandrStatus appStatus = SqandrStatus.OFF;
    private TextView pktMeComplete,pktMeUnique,pktMeMissed,pktMeSuccess;
    private TextView pktOtherComplete,pktOtherUnique,pktOtherMissed,pktOtherSuccess;
    private TextView pktPartial,pktSegments, outPackets, outBytes;
    private View tableSpecs;
    private Timer autoUpdate;
    private ImageView buttonSend;

    private final static String PREFS_COMMANDS = "cmds";
    private final static String PREFS_PKT_SIZE = "pktss";
    private final static String PREFS_PKT_DELAY = "pktdly";

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
        outPackets = findViewById(R.id.meOutPackets);
        outBytes = findViewById(R.id.meOutBytes);
        editPktSize = findViewById(R.id.mainPktSize);
        editPktDelay = findViewById(R.id.mainPktDelay);
        tableSpecs = findViewById(R.id.mainTableSendSpecs);
        buttonSend = findViewById(R.id.mainToggleSend);
        imagePlutoStatus.setVisibility(View.INVISIBLE);
        buttonSend.setOnClickListener(v -> {
            if (testService != null) {
                if (testService.isSendData())
                    testService.setSendData(false);
                else {
                    try {
                        TestPacket.setPacketSize(Integer.parseInt(editPktSize.getText().toString()));
                        testService.setIntervalBetweenTx(Long.parseLong(editPktDelay.getText().toString()));
                    } catch (NumberFormatException ignore) {
                    }
                    testService.setSendData(true);
                }
            }
            updateSendButton();
        });

        /*TestPacket.setPacketSize(105);
        TestPacket pkt = new TestPacket(1234l,1);
        byte[] bytes = pkt.toBytes();
        Log.d(TAG,"pkt = "+ StringUtils.toHex(bytes));
        byte[] segBytes = Segmenter.wrap(bytes).toBytes();
        ByteBuffer in = ByteBuffer.wrap(segBytes);
        byte[] header = new byte[3];
        header[0] = in.get();
        header[1] = in.get();
        header[2] = in.get();
        int size = header[2] & 0xFF;
        byte[] rest = new byte[size+2]; //2 added to get the rest of the header
        in.get(rest);
        Segment segment = new Segment();
        segment.parseRemainder(rest);
        Log.d(TAG,"Seg = "+ StringUtils.toHex(segment.toBytes()));
        //TestPacket newPkt = new TestPacket(segment.getData());
        TestPacket newPkt = new TestPacket(StringUtils.toByteArray("33cc31d4f75555555555555187555555547e5457515d457515d5d4d7d1ddc5f595555457515d457515d5d4d7d1ddc5f595555457515d457515d5d4d7d1ddc5f595555457515d457515d5d4d7d1ddc5f595555457515d457515d5d4d7d1ddc5f595555457515d555555"));
        Log.d(TAG,"Packet "+newPkt.getIndex()+" from "+newPkt.getDevice());
        Log.d(TAG,newPkt.isValid()?"Valid":"Invalid");*/
    }

    private void loadPrefs() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        editCommands.setText(sharedPrefs.getString(PREFS_COMMANDS,null));
        editPktSize.setText(sharedPrefs.getString(PREFS_PKT_SIZE,"100"));
        editPktDelay.setText(sharedPrefs.getString(PREFS_PKT_DELAY,"10000"));
    }

    private void savePrefs() {
        SharedPreferences sharedPrefs = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        SharedPreferences.Editor edit = sharedPrefs.edit();
        edit.putString(PREFS_COMMANDS,editCommands.getText().toString());
        edit.putString(PREFS_PKT_SIZE,editPktSize.getText().toString());
        edit.putString(PREFS_PKT_DELAY,editPktDelay.getText().toString());
        edit.apply();
    }

    private void setPlutoStatus(String message) {
        runOnUiThread(() -> textStatus.setText(message));
    }

    private void toggleAppStatus() {
        if ((appStatus != SqandrStatus.PENDING) && (testService != null)) {
            testService.setCommandFlags(editCommands.getText().toString());
            testService.setAppRunning(appStatus != SqandrStatus.RUNNING);
            updateStats();
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
                        setStatus(PlutoStatus.UP);
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
            outPackets.setText(Integer.toString(stats.packetsSent));
            outBytes.setText(Long.toString(stats.bytesSent));
        });
    }

    private void updateSendButton() {
        runOnUiThread(() -> {
            if (testService == null)
                buttonSend.setVisibility(View.INVISIBLE);
            if (testService.getAppStatus() == SqandrStatus.RUNNING) {
                buttonSend.setVisibility(View.VISIBLE);
                if (testService.isSendData())
                    buttonSend.setImageResource(R.drawable.icon_sending);
                else
                    buttonSend.setImageResource(R.drawable.icon_not_sending);
            } else
                buttonSend.setVisibility(View.INVISIBLE);
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
