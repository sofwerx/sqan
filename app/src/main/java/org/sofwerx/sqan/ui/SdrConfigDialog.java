package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.issues.AbstractManetIssue;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.SdrMode;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Shows the current hardware/system status and any issues impacting the MANET
 */
public class SdrConfigDialog extends Dialog {
    private Activity activity;
    private EditText tx,rx;
    private Spinner modeSpinner;
    private ArrayList<SdrMode> modes;
    private final static float MIN_FREQ = 325f; //MHz
    private final static float MAX_FREQ = 3800f; //MHz

    public SdrConfigDialog(Activity a) {
        super(a);
        this.activity = a;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_sdr_settings);
        Button bDone = findViewById(R.id.dSdrDone);
        bDone.setOnClickListener(v -> {
            if (savePrefs())
                dismiss();
        });
        tx = findViewById(R.id.dSdrEditTx);
        rx = findViewById(R.id.dSdrEditRx);
        modeSpinner = findViewById(R.id.dSdrModeSpinner);

        tx.setText(String.format("%.1f",SdrConfig.getTxFreq()));
        rx.setText(String.format("%.1f",SdrConfig.getRxFreq()));

        modes = new ArrayList<>();
        modes.add(SdrMode.P2P);

        List<String> list = new ArrayList<>();
        for (SdrMode mode:modes) {
            list.add(mode.name());
        }
        ArrayAdapter<String> dataAdapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item, list);
        dataAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        modeSpinner.setAdapter(dataAdapter);
        try {
            modeSpinner.setSelection(SdrConfig.getMode().ordinal());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * @return true == success
     */
    private boolean savePrefs() {
        float txF = SdrConfig.getTxFreq();
        float rxF = SdrConfig.getRxFreq();
        SdrMode mode = SdrConfig.getMode();

        String problem = null;

        try {
            txF = Float.valueOf(tx.getText().toString());
            if (txF < MIN_FREQ)
                problem = String.format("%.1f",txF)+" is below the min tunable freq of "+String.format("%.1f",MIN_FREQ)+" MHz";
            else if (txF > MAX_FREQ)
                problem = String.format("%.1f",txF)+" is below the max tunable freq of "+String.format("%.1f",MAX_FREQ)+" MHz";
        } catch (NumberFormatException ignore) {
            problem = "Invalid TX frequency";
        }
        try {
            rxF = Float.valueOf(rx.getText().toString());
            if (rxF < MIN_FREQ)
                problem = String.format("%.1f",rxF)+" is below the min tunable freq of "+String.format("%.1f",MIN_FREQ)+" MHz";
            else if (rxF > MAX_FREQ)
                problem = String.format("%.1f",rxF)+" is below the max tunable freq of "+String.format("%.1f",MAX_FREQ)+" MHz";
        } catch (NumberFormatException ignore) {
            problem = "Invalid RX frequency";
        }
        try {
            mode = modes.get(modeSpinner.getSelectedItemPosition());
        } catch (Exception ignore) {
            problem = "Invalid mode";
        }

        if (problem != null) {
            Toast.makeText(activity, "Unable to update SDR: " + problem, Toast.LENGTH_LONG).show();
            return false;
        } else {
            SdrConfig.setTxFreq(txF);
            SdrConfig.setRxFreq(rxF);
            SdrConfig.setMode(mode);
            SdrConfig.saveToPrefs(activity);
            return true;
        }
    }

    public static void show(Activity context) {
        SdrConfigDialog d = new SdrConfigDialog(context);
        d.setOnDismissListener(dialog -> d.savePrefs());
        d.show();
    }
}
