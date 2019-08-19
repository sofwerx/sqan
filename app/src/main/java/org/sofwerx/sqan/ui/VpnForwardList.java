package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;

import java.util.ArrayList;

public class VpnForwardList extends ConstraintLayout {
    private ListView list;
    private VpnForwardListArrayAdapter adapter;
    private Activity activity;

    public VpnForwardList(@NonNull Context context) {
        super(context);
        init(context);
    }

    public VpnForwardList(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public VpnForwardList(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init(context);
    }

    public void onPause() {
        //if (devices != null) {
            //for (SqAnDevice device:devices) {
            //    device.setDisplayInterface(null);
            //}
        //}
    }

    public void onResume() {
        if (adapter != null)
            adapter.notifyDataSetChanged();
    }

    private Activity getActivity() {
        Context context = getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity)context;
            }
            context = ((ContextWrapper)context).getBaseContext();
        }
        return null;
    }

    private void init(final Context context) {
        View view = inflate(context,R.layout.vpn_fwd_list,this);
        list = view.findViewById(R.id.vpnFwdList);
        activity = getActivity();
    }

    public void update(final ArrayList<VpnForwardValue> values) {
        list.post(() -> {
            if ((values == null) || values.isEmpty()) {
                adapter = null;
                list.setAdapter(null);
            } else {
                if (adapter == null) {
                    adapter = new VpnForwardListArrayAdapter(activity, values);
                    list.setAdapter(adapter);
                } else
                        adapter.notifyDataSetChanged();
            }
        });
    }
}
