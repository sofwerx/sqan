package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

public class StoredTeammateSummary extends ConstraintLayout {
    private final static long RECENT_TIME = 1000l * 60l * 60l * 24l * 5l;
    private TextView callsign, uuid, bt, wifi, last, btName;
    private ImageView iconTrash;
    private ImageView iconFix;
    private ImageView iconBt, iconWiFi, iconLast;
    private CheckBox checkEnabled;
    private StoredTeammateChangeListener listener;
    private SavedTeammate teammate;
    private boolean systemChangingCheckEnabled = false;

    public StoredTeammateSummary(@NonNull Context context) {
        super(context);
        init(context);
    }

    public StoredTeammateSummary(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StoredTeammateSummary(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    private void init(final Context context) {
        View view = inflate(context,R.layout.stored_teammate_summary,this);
        callsign = view.findViewById(R.id.teammateCallsign);
        uuid = view.findViewById(R.id.teammateUUID);
        bt = view.findViewById(R.id.teammateBt);
        btName = view.findViewById(R.id.teammateBtName);
        wifi = view.findViewById(R.id.teammateWiFi);
        last = view.findViewById(R.id.teammateLast);
        iconTrash = view.findViewById(R.id.teammateForget);
        iconFix = view.findViewById(R.id.teammateFix);
        iconWiFi = view.findViewById(R.id.teammateIconWiFi);
        iconBt = view.findViewById(R.id.teammateIconBt);
        iconLast = view.findViewById(R.id.teammateIconLast);
        checkEnabled = view.findViewById(R.id.teammateEnabled);
        checkEnabled.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (!systemChangingCheckEnabled && (teammate != null)) {
                teammate.setEnabled(isChecked);
                Toast.makeText(context,isChecked?("SqAN will try to connect to "+teammate.getLabel()):("SqAN will ignore "+teammate.getLabel()),Toast.LENGTH_SHORT).show();
            }
        });
        if (context instanceof StoredTeammateChangeListener)
            listener = (StoredTeammateChangeListener)context;
        else
            listener = null;
        if (listener == null)
            iconTrash.setVisibility(View.INVISIBLE);
        else {
            iconTrash.setVisibility(View.VISIBLE);
            iconTrash.setOnClickListener(v -> new AlertDialog.Builder(context)
                    .setTitle(R.string.forget_teammate)
                    .setMessage(R.string.forget_teammate_description)
                    .setNegativeButton(R.string.forget, (dialog, which) -> {
                        Config.removeTeammate(teammate);
                        if (listener != null)
                            listener.onTeammateChanged(null);
                    })
                    .setNeutralButton(R.string.no_link_device, (dialog, which) -> {
                        teammate.setEnabled(false);
                        if (listener != null)
                            listener.onTeammateChanged(null);
                    })
                    .setPositiveButton(R.string.keep, (dialog, which) -> dialog.dismiss()).create().show());
        }
        iconFix.setOnClickListener(v -> {
            if ((listener != null) && (teammate != null))
                listener.onPairingNeeded(teammate.getBluetoothMac());
        });
    }

    public void update(SavedTeammate teammate) {
        this.teammate = teammate;
        if (teammate != null) {
            boolean needsRepair = false;
            if (teammate.getCallsign() == null)
                callsign.setText("[unknown callsign]");
            else
                callsign.setText(teammate.getCallsign());
            uuid.setText((teammate.getSqAnAddress()>0?Integer.toString(teammate.getSqAnAddress()):"Unknown"));
            systemChangingCheckEnabled = true;
            checkEnabled.setChecked(teammate.isEnabled());
            systemChangingCheckEnabled = false;
            if (teammate.getLastContact() > 0l) {
                if ((System.currentTimeMillis() - teammate.getLastContact()) > RECENT_TIME)
                    iconLast.setColorFilter(getResources().getColor(R.color.yellow));
                else
                    iconLast.setColorFilter(getResources().getColor(R.color.green));
                last.setText(StringUtil.getFormattedTime(teammate.getLastContact()));
            } else {
                iconLast.setColorFilter(getResources().getColor(R.color.light_grey));
                last.setText("Never");
            }
            btName.setText(teammate.getBtName());
            MacAddress btMac = teammate.getBluetoothMac();
            if ((btMac == null) || !btMac.isValid()) {
                needsRepair = true;
                bt.setText(null);
                iconBt.setColorFilter(getResources().getColor(R.color.bright_red));
            } else {
                bt.setText(btMac.toString());
                if (teammate.isBtPaired())
                    iconBt.setColorFilter(getResources().getColor(R.color.green));
                else {
                    iconBt.setColorFilter(getResources().getColor(R.color.yellow));
                    needsRepair = true;
                }
            }
            if ((teammate.getNetID() == null) || (teammate.getNetID().length() == 0)) {
                wifi.setText(null);
                iconWiFi.setColorFilter(getResources().getColor(R.color.bright_red));
            } else {
                wifi.setText(teammate.getNetID());
                iconWiFi.setColorFilter(getResources().getColor(R.color.green));
            }
            iconFix.setVisibility(needsRepair?View.VISIBLE:View.INVISIBLE);
        } else
            Log.e(Config.TAG,"StoredTeammateSummary has been assigned a null teammate - this should never happen");
    }
}
