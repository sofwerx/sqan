package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.util.ArrayList;

public class DevicesList extends ConstraintLayout {
    private ListView list;
    private View waitingView;
    private DevicesListArrayAdapter adapter;
    //private ArrayList<SqAnDevice> devices = null;
    private Activity activity;

    public DevicesList(@NonNull Context context) {
        super(context);
        init(context);
    }

    public DevicesList(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public DevicesList(Context context, AttributeSet attrs, int defStyle) {
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
        View view = inflate(context,R.layout.devices_list,this);
        list = view.findViewById(R.id.devicesList);
        list.setVisibility(View.INVISIBLE);
        activity = getActivity();
        list.setOnItemClickListener((parent, view1, position, id) -> {
            if (adapter != null) {
                SqAnDevice device = adapter.getItem(position);
                if (device != null) {
                    //TODO
                }
            }
        });
        waitingView = view.findViewById(R.id.devicesNoDevices);
        TextView waitingViewText = view.findViewById(R.id.devicesNoDevicesText);
        waitingViewText.setText("Looking for other SqAN nodes...");
    }

    public void update(final SqAnDevice device) {
        list.post(() -> {
            if ((device == null) || (device.getUiSummary() == null)) {
                ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
                if ((devices == null) || devices.isEmpty()) {
                    adapter = null;
                    list.setAdapter(null);
                } else {
                    if (adapter == null) {
                        adapter = new DevicesListArrayAdapter(activity, devices);
                        list.setAdapter(adapter);
                    } else {
                        if (device == null)
                            adapter.notifyDataSetChanged();
                        else {
                            adapter.notifyDataSetChanged();
                        }
                    }
                }
            } else
                device.getUiSummary().update(device);
            updateListVisibility();
        });
    }

    private void updateListVisibility() {
        ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
        if ((devices != null) && !devices.isEmpty()) {
            list.setVisibility(View.VISIBLE);
            boolean active = SqAnDevice.hasAtLeastOneActiveConnection();
            waitingView.setVisibility(active?View.INVISIBLE:View.VISIBLE);
        } else {
            list.setVisibility(View.INVISIBLE);
            waitingView.setVisibility(View.VISIBLE);
        }
    }
}
