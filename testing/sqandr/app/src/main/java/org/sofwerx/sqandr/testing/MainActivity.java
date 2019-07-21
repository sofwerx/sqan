package org.sofwerx.sqandr.testing;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;

import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.SqANDRListener;
import org.sofwerx.sqandr.serial.TestService;

public class MainActivity extends AppCompatActivity implements SqANDRListener, PeripheralStatusListener {
    private TestService testService;
    private TextView textStatus;
    private ImageView imageStatus;
    private PlutoStatus status = PlutoStatus.INSTALLING;

    private enum PlutoStatus {ERROR,INSTALLING,UP}

    private void setStatus(String message) {
        runOnUiThread(() -> textStatus.setText(message));
    }

    private void setStatus(PlutoStatus status) {
        if (this.status != status) {
            this.status = status;
            runOnUiThread(() -> {
                switch (status) {
                    case UP:
                        imageStatus.setImageResource(R.drawable.icon_up);
                        break;

                    case INSTALLING:
                        imageStatus.setImageResource(R.drawable.icon_busy);
                        break;

                    default:
                        imageStatus.setImageResource(R.drawable.icon_error);
                }
            });
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textStatus = findViewById(R.id.mainStatus);
        imageStatus = findViewById(R.id.mainStatusIcon);
        testService = new TestService(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (testService != null) {
            testService.setListener(this);
            testService.setPeripheralStatusListener(this);
        }
    }

    @Override
    public void onPause() {
        if (testService != null) {
            testService.setListener(null);
            testService.setPeripheralStatusListener(this);
        }
        super.onPause();
    }

    @Override
    public void onDestroy() {
        if (testService != null) {
            testService.shutdown();
            testService = null;
        }
        super.onDestroy();
    }

    @Override
    public void onSdrError(String message) {
        setStatus("ERROR: "+message);
        setStatus(PlutoStatus.ERROR);
        //TODO
    }

    @Override
    public void onSdrReady(boolean isReady) {
        setStatus("SDR is ready");
        //TODO
    }

    @Override
    public void onSdrMessage(String message) {
        setStatus(message);
        //TODO
    }

    @Override
    public void onPacketReceived(byte[] data) {
        //TODO
        setStatus(PlutoStatus.UP);
    }

    @Override
    public void onPacketDropped() {
        //TODO
    }

    @Override
    public void onPeripheralMessage(String message) {
        setStatus(message);
    }

    @Override
    public void onPeripheralReady() {
        setStatus("Pluto is ready");
        setStatus(PlutoStatus.UP);
    }

    @Override
    public void onPeripheralError(String message) {
        setStatus("ERROR: "+message);
        setStatus(PlutoStatus.ERROR);
    }
}
