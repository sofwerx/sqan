package org.sofwerx.sqan.ui;

import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.bt.Discovery;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.util.StringUtil;

import androidx.appcompat.app.AppCompatActivity;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

public class StoredTeammatesActivity extends AppCompatActivity implements StoredTeammateChangeListener {
    private StoredTeammatesList teammatesList;
    private Discovery btDiscovery;
    private FloatingActionButton fabFix;
    private CoordinatorLayout coordinatorLayout;
    private BluetoothAdapter bluetoothAdapter;
    private View viewSearching;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stored_teammates);
        coordinatorLayout = findViewById(R.id.storedTeammatesCoordinator);
        teammatesList = findViewById(R.id.storedTeammatesList);
        fabFix = findViewById(R.id.storedTeammatesRepair);
        viewSearching = findViewById(R.id.storedTeammatesFindOthers);
        fabFix.setOnClickListener(v -> repair());
        final BluetoothManager bluetoothManager = (BluetoothManager)getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        intentFilter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        registerReceiver(receiver,intentFilter);
    }

    @Override
    public void onResume() {
        super.onResume();
        Config.updateSavedTeammates();
        updateDisplay();
    }

    private void updateDisplay() {
        fabFix.setEnabled(!bluetoothAdapter.isDiscovering());
        viewSearching.setVisibility(bluetoothAdapter.isDiscovering()?View.VISIBLE:View.GONE);
        teammatesList.update();
        int num = Config.getNumberOfSavedTeammates();
        if (num < 1)
            setTitle("No Stored Teammates");
        else {
            if (num == 1)
                setTitle("Stored Teammate");
            else
                setTitle(num+" Stored Teammates");
        }
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(receiver);
        if (btDiscovery != null) {
            btDiscovery.stopDiscovery();
            btDiscovery.stopAdvertising();
            btDiscovery = null;
        }
        super.onDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
    }

    @Override
    public void onTeammateChanged(SavedTeammate teammate) {
        runOnUiThread(() -> updateDisplay());
    }

    private void repair() {
        fabFix.setEnabled(false);
        onDiscoveryNeeded();
    }

    @Override
    public void onDiscoveryNeeded() {
        if (btDiscovery == null)
            btDiscovery = new Discovery(this);
        btDiscovery.startAdvertising();
        btDiscovery.startDiscovery();
    }

    @Override
    public void onPairingNeeded(MacAddress bluetoothMac) {
        if ((bluetoothMac != null) && bluetoothMac.isValid()) {
            onDiscoveryNeeded();
            String macString = bluetoothMac.toString();
            btDiscovery.requestPairing(macString);
            runOnUiThread(() -> Toast.makeText(StoredTeammatesActivity.this,"Trying to pair with "+macString,Toast.LENGTH_LONG).show());
        } else
            Log.e(Config.TAG,"Cannot request a BT pairing with a null or invalid MAC");
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_STARTED)) {
                Snackbar.make(coordinatorLayout, R.string.bt_discovery_started, Snackbar.LENGTH_LONG).show();
            } else if (action.equals(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)) {
                Snackbar.make(coordinatorLayout, R.string.bt_discovery_ended, Snackbar.LENGTH_LONG).show();
            //} else if (action.equals(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED)) {
            //    String mode = intent.getStringExtra(BluetoothAdapter.EXTRA_SCAN_MODE);
            //    Toast.makeText(StoredTeammatesActivity.this,"Mode is "+mode,Toast.LENGTH_LONG).show();
            }
            runOnUiThread(() -> updateDisplay());
        }
    };

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(Config.TAG, "Result : " + requestCode + " " + resultCode);

        switch (requestCode) {
            case Discovery.REQUEST_DISCOVERY:
                if (resultCode == RESULT_OK) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(this);
                    builder.setTitle(R.string.bt_start_discovery_on_other_devices);
                    builder.setMessage(getResources().getString(R.string.bt_start_discovery_on_other_devices_narrative, StringUtil.toDuration(((long)Discovery.DISCOVERY_DURATION_SECONDS)*1000l)));
                    final AlertDialog dialog = builder.create();
                    dialog.setCanceledOnTouchOutside(true);
                    dialog.show();
                } else if (resultCode == RESULT_CANCELED)
                    Snackbar.make(coordinatorLayout, R.string.bt_discovery_canx, Snackbar.LENGTH_LONG).show();
                updateDisplay();
                break;

            case Discovery.REQUEST_ENABLE_BLUETOOTH:
                if (resultCode == RESULT_OK) {
                    //ignore for now
                } else if (resultCode == RESULT_CANCELED)
                    Snackbar.make(coordinatorLayout, R.string.bt_needed, Snackbar.LENGTH_LONG).show();
                updateDisplay();
                break;
        }
    }
}