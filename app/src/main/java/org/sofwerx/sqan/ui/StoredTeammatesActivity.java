package org.sofwerx.sqan.ui;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.ExceptionHelper;
import org.sofwerx.sqan.LocationService;
import org.sofwerx.sqan.ManetOps;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.listeners.SqAnStatusListener;
import org.sofwerx.sqan.manet.common.AbstractManet;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.Status;
import org.sofwerx.sqan.manet.common.StatusHelper;
import org.sofwerx.sqan.util.CommsLog;
import org.sofwerx.sqan.util.PermissionsHelper;
import org.sofwerx.sqan.util.StringUtil;

import java.io.StringWriter;
import java.util.ArrayList;

import androidx.appcompat.app.AppCompatActivity;

import static org.sofwerx.sqan.SqAnService.ACTION_STOP;

public class StoredTeammatesActivity extends AppCompatActivity {
    private boolean permissionsNagFired = false; //used to request permissions from user
    private StoredTeammatesList teammatesList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stored_teammates);
        teammatesList = findViewById(R.id.storedTeammatesList);
    }

    @Override
    public void onResume() {
        super.onResume();
        teammatesList.update(null);
    }

    @Override
    public void onPause() {
        super.onPause();
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