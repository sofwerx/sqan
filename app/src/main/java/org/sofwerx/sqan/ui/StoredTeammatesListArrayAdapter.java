package org.sofwerx.sqan.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SavedTeammate;

import java.util.ArrayList;

public class StoredTeammatesListArrayAdapter extends ArrayAdapter<SavedTeammate> {
    private final Context context;
    private final ArrayList<SavedTeammate> teammates;

    public StoredTeammatesListArrayAdapter(Context context, ArrayList<SavedTeammate> teammates) {
        super(context, -1, teammates);
        this.context = context;
        this.teammates = teammates;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        LayoutInflater inflater = (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View rowView = inflater.inflate(R.layout.stored_teammate_list_item, parent, false);
        StoredTeammateSummary teammateSummary = rowView.findViewById(R.id.teammatesListItem);
        if (position < teammates.size())
            teammateSummary.update(teammates.get(position));
        else
            teammateSummary.update(null);

        return rowView;
    }
}
