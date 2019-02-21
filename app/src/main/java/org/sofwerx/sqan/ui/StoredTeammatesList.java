package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.common.SqAnDevice;

import java.util.ArrayList;

import androidx.annotation.NonNull;
import androidx.constraintlayout.widget.ConstraintLayout;

public class StoredTeammatesList extends ConstraintLayout {
    private ListView list;
    private View waitingView;
    private StoredTeammatesListArrayAdapter adapter;
    private Activity activity;

    public StoredTeammatesList(@NonNull Context context) {
        super(context);
        init(context);
    }

    public StoredTeammatesList(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public StoredTeammatesList(Context context, AttributeSet attrs, int defStyle) {
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
        View view = inflate(context,R.layout.stored_teammates_list,this);
        list = view.findViewById(R.id.teammatesList);
        list.setVisibility(View.INVISIBLE);
        activity = getActivity();
        list.setOnItemClickListener((parent, view1, position, id) -> {
            if (adapter != null) {
                Config.SavedTeammate device = adapter.getItem(position);
                if (device != null) {
                    //TODO
                }
            }
        });
        waitingView = view.findViewById(R.id.teammatesNoDevices);
        //TextView waitingViewText = view.findViewById(R.id.teammatesNoDevicesText);
        //waitingViewText.setText("Looking for other SqAN nodes...");
    }

    public void update(Config.SavedTeammate device) {
        list.post(() -> {
            //if (device == null) {
                ArrayList<Config.SavedTeammate> devices = Config.getSavedTeammates();
                if ((devices == null) || devices.isEmpty()) {
                    adapter = null;
                    list.setAdapter(null);
                } else {
                    if (adapter == null) {
                        adapter = new StoredTeammatesListArrayAdapter(activity, devices);
                        list.setAdapter(adapter);
                    } else {
                        if (device == null)
                            adapter.notifyDataSetChanged();
                        else {
                            adapter.notifyDataSetChanged();
                        }
                    }
                }
            //}
            updateListVisibility();
        });
    }
    private void updateListVisibility() {
        ArrayList<Config.SavedTeammate> devices = Config.getSavedTeammates();
        if ((devices != null) && !devices.isEmpty()) {
            list.setVisibility(View.VISIBLE);
            waitingView.setVisibility(View.INVISIBLE);
        } else {
            list.setVisibility(View.INVISIBLE);
            waitingView.setVisibility(View.VISIBLE);
        }
    }
}
