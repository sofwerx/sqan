package org.sofwerx.sqan.ui;

import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.bt.Discovery;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.sdr.SdrManet;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqandr.SqANDRService;
import org.sofwerx.sqandr.ui.Terminal;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;

public class ConnectionDetailsActivity extends AppCompatActivity {
    private TextView textTeammates, textActive, textSysConnect;
    private View sendDiagnostics, openTerminal;
    private Terminal terminal;
    private SdrManet sdrManet;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection_details);
        textTeammates = findViewById(R.id.connDetailsStoredTeammates);
        textActive = findViewById(R.id.connDetailsActiveTeammates);
        textSysConnect = findViewById(R.id.connDetailsStoredSys);
        sendDiagnostics = findViewById(R.id.connDetailsSend);
        openTerminal = findViewById(R.id.connDetailsTerminalButton);
        terminal = findViewById(R.id.connDetailsTerminal);
        terminal.setVisibility(View.GONE);
        sendDiagnostics.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();

            sb.append("Teammate and Connection status overview:\r\n");
            sb.append(" * Saved teammates:\r\n");
            sb.append(textTeammates.getText().toString());
            sb.append("\r\n\r\n");
            sb.append(" * Active teammates:\r\n");
            sb.append(textActive.getText().toString());
            sb.append("\r\n\r\n");
            sb.append(" * System Saved Pairings:\r\n");
            sb.append(textSysConnect.getText().toString());
            CommsLog.log(CommsLog.Entry.Category.STATUS,sb.toString());
            File file = CommsLog.getFileAndStartNew(ConnectionDetailsActivity.this);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("plain/text");
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"support@aug.email"});
            intent.putExtra(Intent.EXTRA_SUBJECT, "SqAN log file");
            if ((file == null) || !file.exists())
                intent.putExtra(Intent.EXTRA_TEXT, "Unable to generate a log file");
            else {
                intent.putExtra(Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(ConnectionDetailsActivity.this, ConnectionDetailsActivity.this.getApplicationContext().getPackageName() + ".mission.provider", file));
                intent.putExtra(Intent.EXTRA_TEXT, "Log generated at " + StringUtil.getFilesafeTime(System.currentTimeMillis()));
            }
            ConnectionDetailsActivity.this.startActivity(intent);
        });
        if ((SqAnService.getInstance() != null) && (SqAnService.getInstance().getManetOps() != null))
            sdrManet = SqAnService.getInstance().getManetOps().getSdrManet();
        if (sdrManet != null) {
            openTerminal.setVisibility(View.VISIBLE);
            openTerminal.setOnClickListener(v -> {
                if (terminal.getVisibility() == View.VISIBLE)
                    terminal.setVisibility(View.GONE);
                else
                    terminal.setVisibility(View.VISIBLE);
            });
            terminal.setSerialConnection(sdrManet.getSerialConnection());
        } else
            openTerminal.setVisibility(View.GONE);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDisplay();
        if (sdrManet != null)
            sdrManet.setTerminal(terminal);
    }

    private SpannableString getText(String title, String description) {
        if ((title == null) && (description == null))
            return null;
        SpannableString spannable;
        if (title == null) {
            spannable = new SpannableString(description);
        } else if (description == null) {
            spannable = new SpannableString(title);
        } else
            spannable = new SpannableString(title+": "+description);
        if (title != null) {
            spannable.setSpan(new ForegroundColorSpan(Color.GREEN),0,title.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return spannable;
    }

    private void updateDisplay() {
        ArrayList<SavedTeammate> teammates = Config.getSavedTeammates();
        if ((teammates == null) || teammates.isEmpty())
            textTeammates.setText(getText("No teammates",null));
        else {
            textTeammates.setText(null);
            boolean first = false;
            for (SavedTeammate teammate:teammates) {
                if (first)
                    first = false;
                else
                    textTeammates.append("\r\n");
                textTeammates.append(getText(teammate.getLabel(),teammate.toString()));
            }
        }

        ArrayList<SqAnDevice> devices = SqAnDevice.getDevices();
        if ((devices == null) || devices.isEmpty())
            textActive.setText(getText("No active devices",null));
        else {
            textActive.setText(null);
            boolean first = false;
            for (SqAnDevice device:devices) {
                if (first)
                    first = false;
                else
                    textActive.append("\r\n");
                textActive.append(getText(device.getLabel(),device.toString()));
            }
        }

        Set<BluetoothDevice> btDevices = Discovery.getPairedDevices(this);
        if ((btDevices == null) || btDevices.isEmpty())
            textSysConnect.setText(getText("No bluetooth devices",null));
        else {
            textSysConnect.setText(null);
            boolean first = false;
            for (BluetoothDevice device:btDevices) {
                if (first)
                    first = false;
                else
                    textSysConnect.append("\r\n");
                textSysConnect.append(getText(device.getName(),device.getAddress()));
            }
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onPause() {
        if (sdrManet != null)
            sdrManet.setTerminal(null);
        super.onPause();
    }
}