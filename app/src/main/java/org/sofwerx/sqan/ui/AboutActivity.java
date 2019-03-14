package org.sofwerx.sqan.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.util.Admin;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqan.vpn.LiteWebServer;

import java.io.StringWriter;

public class AboutActivity extends Activity {
    private TextView ackTitle, ack;
    private TextView licTitle, lic;
    private TextView logTitle, log;
    private TextView version, uuid;
    private TextView timeUp, timeDegraded, timeDown;

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
        uuid = findViewById(R.id.aboutUUID);
        timeUp = findViewById(R.id.about_time_up);
        timeDegraded = findViewById(R.id.about_time_degraded);
        timeDown = findViewById(R.id.about_time_down);

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
                showLog();
            }
        });
        log.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("SqAN log", CommsLog.getEntriesAsString()));
            Toast.makeText(this,"CommsLog copied",Toast.LENGTH_SHORT).show();
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

        StringWriter out = new StringWriter();
        out.append("SqAN UUID ");
        out.append(Integer.toString(Config.getThisDevice().getUUID()));
        out.append(" (");
        out.append(Config.getThisDevice().getUuidExtended());
        out.append(')');
        if (Config.isVpnEnabled()) {
            String address = Config.getThisDevice().getVpnIpv4AddressString();
            out.append("\r\nVPN IPV4 address ");
            out.append(address);
            if (Config.isVpnHostLandingPage())
                out.append("\r\nActing as a web server at " + address + ":" + LiteWebServer.PORT);
        }
        uuid.setText(out.toString());
        Bundle extras = getIntent().getExtras();
        if ((extras != null) && extras.getBoolean("logs"))
            showLog();

        long time;
        if (timeUp != null) {
            time = ManetOps.getTotalUpTime();
            timeUp.setText((time < 1000l) ? "none" : StringUtil.toDuration(time));
        }
        if (timeDegraded != null) {
            time = ManetOps.getTotalDegradedTime();
            timeDegraded.setText((time < 1000l) ? "none" : StringUtil.toDuration(time));
        }
        if (timeDown != null) {
            time = ManetOps.getTotalDownTime();
            timeDown.setText((time < 1000l) ? "none" : StringUtil.toDuration(time));
        }
    }

    private void showLog() {
        log.setText(CommsLog.getEntriesAsString());
        log.setVisibility(View.VISIBLE);
        logTitle.setCompoundDrawablesWithIntrinsicBounds(R.drawable.icon_expandable_white, 0, 0, 0);
    }
}