package org.sofwerx.sqan.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.util.ArrayList;

public class DevicesListArrayAdapter extends ArrayAdapter<SqAnDevice> {
    private final Context context;
    private final ArrayList<SqAnDevice> devices;

    public DevicesListArrayAdapter(Context context, ArrayList<SqAnDevice> devices) {
        super(context, -1, devices);
        this.context = context;
        this.devices = devices;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View rowView = inflater.inflate(R.layout.device_list_item, parent, false);
        DeviceSummary deviceSummary = rowView.findViewById(R.id.deviceListItem);
        if (position < devices.size())
            deviceSummary.update(devices.get(position));
        else
            deviceSummary.update(null);

        return rowView;
    }
}
