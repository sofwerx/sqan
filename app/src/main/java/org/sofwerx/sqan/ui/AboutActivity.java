package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;

import org.sofwerx.sqan.R;
import org.sofwerx.sqan.util.Admin;
import org.sofwerx.sqan.util.CommsLog;

public class AboutActivity extends Activity {
    private TextView ackTitle, ack;
    private TextView licTitle, lic;
    private TextView logTitle, log;
    private TextView version;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.about_activity);
        ackTitle = findViewById(R.id.legalAckTitle);
        ack = findViewById(R.id.legalAck);
        licTitle = findViewById(R.id.legalLicenseTitle);
        lic = findViewById(R.id.legalLicense);
        logTitle = findViewById(R.id.commsLogTitle);
        log = findViewById(R.id.commsLog);
        version = findViewById(R.id.aboutVersion);

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionValue = pInfo.versionName;
            version.setText("SqAN "+versionValue.toUpperCase());
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }

        logTitle.setOnClickListener(v -> {
            if (log.getVisibility() == View.VISIBLE) {
                log.setVisibility(View.GONE);
                logTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expanded_white, 0, 0, 0);
            } else {
                log.setText(CommsLog.getEntriesAsString());
                log.setVisibility(View.VISIBLE);
                logTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expandable_white, 0, 0, 0);
            }
        });
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