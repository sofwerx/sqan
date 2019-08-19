package org.sofwerx.sqan.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;

import java.util.ArrayList;

public class VpnForwardListArrayAdapter extends ArrayAdapter<VpnForwardValue> implements VpnForwardSummary.VpnSummaryChangeListener {
    private final Context context;
    private final ArrayList<VpnForwardValue> ips;

    public VpnForwardListArrayAdapter(Context context, ArrayList<VpnForwardValue> ips) {
        super(context, -1, ips);
        this.context = context;
        this.ips = ips;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View rowView = inflater.inflate(R.layout.vpn_fwd_list_item, parent, false);
        VpnForwardSummary vpnFwdSummary = rowView.findViewById(R.id.vpnFwdListItem);
        if (position < ips.size())
            vpnFwdSummary.update(ips.get(position),this);
        else
            vpnFwdSummary.update(null,this);

        return rowView;
    }

    @Override
    public void onVpnForwardValuesChanged() {
        if (ips != null) {
            int i=0;
            while (i<ips.size()) {
                if (ips.get(i).getAddress() == Integer.MAX_VALUE)
                    ips.remove(i);
                else
                    i++;
            }
        }
        notifyDataSetChanged();
    }
}
