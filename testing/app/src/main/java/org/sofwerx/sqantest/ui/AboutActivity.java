package org.sofwerx.sqantest.ui;

import android.app.Activity;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import org.sofwerx.sqantest.R;
import org.sofwerx.sqantest.util.Admin;

public class AboutActivity extends Activity {
    private TextView ackTitle;
    private TextView ack;
    private TextView licTitle;
    private TextView lic;
    private TextView version;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about_activity);
        ackTitle = findViewById(R.id.legalAckTitle);
        ack = findViewById(R.id.legalAck);
        licTitle = findViewById(R.id.legalLicenseTitle);
        lic = findViewById(R.id.legalLicense);
        version = findViewById(R.id.aboutVersion);

        ackTitle.setOnClickListener(v -> {
            if (ack.getVisibility() == View.VISIBLE) {
                ack.setVisibility(View.GONE);
                ackTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expanded_white, 0, 0, 0);
            } else {
                ack.setVisibility(View.VISIBLE);
                ackTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expandable_white, 0, 0, 0);
            }
        });
        licTitle.setOnClickListener(v -> {
            if (lic.getVisibility() == View.VISIBLE) {
                lic.setVisibility(View.GONE);
                licTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expanded_white, 0, 0, 0);
            } else {
                lic.setVisibility(View.VISIBLE);
                licTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expandable_white, 0, 0, 0);
            }
        });
        ack.setText(Html.fromHtml(Admin.getCredits()));
        ack.setMovementMethod(LinkMovementMethod.getInstance());
        lic.setText(Html.fromHtml(Admin.getLicenses()));
        lic.setMovementMethod(LinkMovementMethod.getInstance());
    }
}