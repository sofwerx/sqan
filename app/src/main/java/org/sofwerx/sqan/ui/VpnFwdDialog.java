package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.Dialog;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.VpnForwardValue;
import org.sofwerx.sqandr.sdr.SdrConfig;
import org.sofwerx.sqandr.sdr.SdrMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows the current hardware/system status and any issues impacting the MANET
 */
public class VpnFwdDialog extends Dialog {
    private Activity activity;
    private VpnForwardList list;
    private ArrayList<VpnForwardValue> values;

    public VpnFwdDialog(Activity a) {
        super(a);
        activity = a;
        if (Config.getThisDevice() != null)
            values = Config.getThisDevice().getIpForwardAddresses();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.dialog_vpn_fwd);
        Button bDone = findViewById(R.id.dVpnFwdDone);
        Button bAdd = findViewById(R.id.dVpnFwdAdd);
        list = findViewById(R.id.dVpnFwdList);
        bDone.setOnClickListener(v -> {
           save();
           dismiss();
        });
        bAdd.setOnClickListener(v -> {
            if (values == null)
                values = new ArrayList<>();
            VpnForwardValue value = new VpnForwardValue();
            if (Config.getThisDevice() != null)
                value.setIndex(Config.getThisDevice().getNextFowardingIndex());
            values.add(value);
            list.update(values);
        });
        list.update(values);
    }

    private void save() {
        if (values == null)
            return;
        Config.saveVpnForwardingIps(activity,values);
        Config.loadVpnForwardingIps(activity);
    }

    public static void show(Activity context) {
        VpnFwdDialog d = new VpnFwdDialog(context);
        d.setOnDismissListener(dialog -> d.save());
        d.show();
    }
}
