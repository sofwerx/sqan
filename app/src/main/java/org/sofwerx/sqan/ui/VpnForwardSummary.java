package org.sofwerx.sqan.ui;

import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;

public class VpnForwardSummary extends ConstraintLayout {
    private TextView resolvedIP;
    private EditText originIP;
    private ImageView iconDel, iconWarning;
    private VpnForwardValue fwdValue;
    private VpnSummaryChangeListener listener;

    public interface VpnSummaryChangeListener {
        void onVpnForwardValuesChanged();
    }

    public VpnForwardSummary(@NonNull Context context) {
        super(context);
        init(context);
    }

    public VpnForwardSummary(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VpnForwardSummary(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    public void update(VpnForwardValue forwardValue, VpnSummaryChangeListener listener) {
        this.fwdValue = forwardValue;
        this.listener = listener;
        if (forwardValue != null)
            originIP.setText(AddressUtil.intToIpv4String(forwardValue.getAddress()));
        resolvedIP.post(() -> updateView());
    }

    private void init(Context context) {
        View view = inflate(context,R.layout.vpn_forward_summary,this);
        resolvedIP = view.findViewById(R.id.vpnFwdResolved);
        originIP = view.findViewById(R.id.vpnFwdEditIp);
        originIP.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) { }

            @Override
            public void afterTextChanged(Editable s) {
                int address = AddressUtil.stringToIpv4Int(originIP.getText().toString());
                if (address == 0) {
                    if (fwdValue != null)
                        fwdValue.setAddress(0);
                } else {
                    if (fwdValue == null) {
                        fwdValue = new VpnForwardValue();
                        if (Config.getThisDevice() != null)
                            fwdValue.setIndex(Config.getThisDevice().getNextFowardingIndex());
                    }
                    fwdValue.setAddress(address);
                }
                updateView();
            }
        });
        iconDel = view.findViewById(R.id.vpnFwdDel);
        iconWarning = view.findViewById(R.id.vpnFwdWarning);
        iconDel.setOnClickListener(v -> {
            if (fwdValue != null)
                fwdValue.setAddress(Integer.MAX_VALUE);
            updateView();
            if (listener != null)
                listener.onVpnForwardValuesChanged();
        });
    }

    public VpnForwardValue getIp() {
        return fwdValue;
    }

    private void updateView() {
        if (fwdValue == null) {
            resolvedIP.setVisibility(View.INVISIBLE);
            iconDel.setVisibility(View.INVISIBLE);
            originIP.setVisibility(View.INVISIBLE);
            iconWarning.setVisibility(View.VISIBLE);
        } else {
            if (Config.getThisDevice() == null) {
                resolvedIP.setVisibility(View.INVISIBLE);
                iconDel.setVisibility(View.INVISIBLE);
                originIP.setVisibility(View.INVISIBLE);
                iconWarning.setVisibility(View.VISIBLE);
            } else {
                resolvedIP.setVisibility(View.VISIBLE);
                iconDel.setVisibility(View.VISIBLE);
                originIP.setVisibility(View.VISIBLE);
                if (fwdValue.getAddress() == 0)
                    iconWarning.setVisibility(View.VISIBLE);
                else
                    iconWarning.setVisibility(View.INVISIBLE);
                resolvedIP.setText(AddressUtil.intToIpv4String(AddressUtil.getSqAnVpnIpv4Address(Config.getThisDevice().getUUID(),fwdValue.getForwardIndex())));
            }
        }
    }
}
