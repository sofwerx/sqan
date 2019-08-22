package org.sofwerx.sqandr.ui;

import android.app.Activity;
import android.content.Context;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.R;
import org.sofwerx.sqandr.sdr.DataConnectionListener;
import org.sofwerx.sqandr.serial.SerialConnection;

import java.nio.charset.StandardCharsets;

public class Terminal extends LinearLayout implements DataConnectionListener {
    private Context context;
    private SerialConnection serialConnection;
    private TextView receiveText;
    private View loggedInMarker;
    private View imageDisconnected;
    private ImageButton sendButton;
    private Activity activity;

    public Terminal(Context context) { this(context,null); }

    public Terminal(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        this.context = context;
        if (context instanceof Activity)
            this.activity = (Activity)context;
        View view = inflate(context, R.layout.terminal, this);
        receiveText = view.findViewById(R.id.terminalReceive);
        receiveText.setTextColor(getResources().getColor(R.color.colorReceiveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());
        imageDisconnected = view.findViewById(R.id.terminalDisconnected);
        TextView sendText = view.findViewById(R.id.terminalSendText);
        sendButton = view.findViewById(R.id.terminalSendButton);
        loggedInMarker = view.findViewById(R.id.terminalLoggedIn);
        sendButton.setOnClickListener(v -> {
            send(sendText.getText().toString());
            sendText.setText(null);
        });
        updateTerminalDisplay();
    }

    public void setSerialConnection(SerialConnection serialConnection) {
        this.serialConnection = serialConnection;
        updateTerminalDisplay();
    }

    public void updateTerminalDisplay() {
        receiveText.post(() -> {
            if ((serialConnection != null) && serialConnection.isActive()) {
                imageDisconnected.setVisibility(View.INVISIBLE);
                sendButton.setVisibility(View.VISIBLE);
                loggedInMarker.setVisibility(serialConnection.isTerminalLoggedIn()?View.VISIBLE:View.INVISIBLE);
            } else {
                imageDisconnected.setVisibility(View.VISIBLE);
                sendButton.setVisibility(View.INVISIBLE);
                loggedInMarker.setVisibility(View.INVISIBLE);
            }
        });
    }

    private void send(String text) {
        if ((text == null) || (serialConnection == null) || !serialConnection.isActive())
            return;
        try {
            SpannableStringBuilder spn = new SpannableStringBuilder(text+'\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            byte[] data = (text + "\r\n").getBytes(StandardCharsets.UTF_8);
            serialConnection.write(data);
        } catch (Exception e) {
            if (activity != null)
                Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void receive(final byte[] data) {
        if (data != null)
            receiveText.post(() -> receiveText.append(new String(data, StandardCharsets.UTF_8)));
    }

    private void status(String str) {
        if (str == null)
            return;
        final SpannableStringBuilder spn = new SpannableStringBuilder(str+'\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.post(() -> receiveText.append(spn));
    }

    /*@Override
    public void onSerialConnect() {
        status("Connected");
        updateTerminalDisplay();
    }

    @Override
    public void onSerialError(Exception e) {
        status("ERROR: "+e.getMessage());
        updateTerminalDisplay();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
        updateTerminalDisplay();
    }*/

    @Override
    public void onConnect() {
        status("Connected");
        updateTerminalDisplay();
    }

    @Override
    public void onDisconnect() {
        //TODO
    }

    @Override
    public void onReceiveDataLinkData(byte[] data) {
        //TODO
    }

    @Override
    public void onReceiveCommandData(byte[] data) {
        receive(data);
        updateTerminalDisplay();
    }

    @Override
    public void onConnectionError(String message) {
        status("ERROR: "+message);
        updateTerminalDisplay();
    }

    @Override
    public void onPacketDropped() {
        status("Packet dropped");
    }

    @Override
    public void onOperational() {
        status("SDR app is running");
    }

    @Override
    public void onHighNoise(float snr) {
        //ignore
    }
}
