package org.sofwerx.sqan.ui;

import android.os.Bundle;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.manet.bt.Discovery;

import androidx.appcompat.app.AppCompatActivity;

public class StoredTeammatesActivity extends AppCompatActivity implements StoredTeammateChangeListener {
    private StoredTeammatesList teammatesList;
    private Discovery btDiscovery;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stored_teammates);
        teammatesList = findViewById(R.id.storedTeammatesList);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDisplay();
    }

    private void updateDisplay() {
        teammatesList.update(null);
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
    public void onTeammateChanged(Config.SavedTeammate teammate) {
        runOnUiThread(() -> updateDisplay());
    }

    @Override
    public void onDiscoveryNeeded() {
        if (btDiscovery == null)
            btDiscovery = new Discovery(this);
        btDiscovery.startAdvertising();
        btDiscovery.startDiscovery();
    }

    /*public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_about:
                startActivity(new Intent(this, AboutActivity.class));
                return true;

            case R.id.action_settings:
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
        }

        return super.onOptionsItemSelected(item);
    }*/

    /*@Override
    public void onActivityResult(int requestCode, int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        switch (requestCode) {
            case REQUEST_DISABLE_BATTERY_OPTIMIZATION:
                Config.setNeverAskBatteryOptimize(this);
                break;
        }
    }*/
}