package org.sofwerx.sqan.manet.bt;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.ui.StoredTeammateChangeListener;

import java.util.Set;

public class Discovery {
    public final static int REQUEST_DISCOVERY = 101;
    public final static int REQUEST_ENABLE_BLUETOOTH = 102;
    private final static String SQAN_PREFIX = "sqan";
    public final static int DISCOVERY_DURATION_SECONDS = 30;
    private Activity activity;
    private String originalBtName = null;
    private StoredTeammateChangeListener listener;
    private final BluetoothAdapter bluetoothAdapter;

    public Discovery(Activity activity) {
        this.activity = activity;
        if (activity instanceof StoredTeammateChangeListener)
            listener = (StoredTeammateChangeListener)activity;
        final BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager.getAdapter();
    }

    /**
     * Try to advertise
     * @return true = is trying; false = some other issue needed to be fixed
     */
    public boolean startAdvertising() {
        Log.d(Config.TAG,"starting Advertising");
        //try to build teammates based on already paired devices
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                handleFoundDevice(device);
            }
        }

        //try discovery to pick-up other devices
        if (bluetoothAdapter.isEnabled()) {
            String currentName = bluetoothAdapter.getName();
            String correctUuid = SQAN_PREFIX+Config.getThisDevice().getUUID();
            if ((currentName == null) || !currentName.equalsIgnoreCase(correctUuid)) {
                originalBtName = currentName;
                bluetoothAdapter.setName(correctUuid);
                Log.d(Config.TAG,"Changing device Bluetooth name from "+originalBtName+" to "+correctUuid);
            }
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERY_DURATION_SECONDS);
            activity.startActivityForResult(discoverableIntent,REQUEST_DISCOVERY);
            return true;
        } else {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent,REQUEST_ENABLE_BLUETOOTH);
        }
        return false;
    }

    public void stopAdvertising() {
        if (originalBtName != null) {
            Log.d(Config.TAG,"stopping Advertising");
            if (bluetoothAdapter.isEnabled()) {
                Log.d(Config.TAG,"Restoring device bluetooth name to "+originalBtName);
                bluetoothAdapter.setName(originalBtName);
            }
        }
    }

    public void startDiscovery() {
        if (activity != null) {
            IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
            activity.registerReceiver(receiver, filter);
            if (bluetoothAdapter.startDiscovery())
                Log.d(Config.TAG,"starting Discovery");
            else
                Log.d(Config.TAG,"starting Discovery failed");
        }
    }

    public void stopDiscovery() {
        Log.d(Config.TAG,"stopping Discovery");
        if (activity != null)
            activity.unregisterReceiver(receiver);
    }

    private void handleFoundDevice(BluetoothDevice device) {
        if (device == null)
            return;
        String deviceName = device.getName();
        if ((deviceName != null) && deviceName.startsWith(SQAN_PREFIX)) {
            Log.d(Config.TAG,"SqAN Bluetooth device found");
            try {
                int uuid = Integer.parseInt(deviceName.substring(SQAN_PREFIX.length()));
                Config.SavedTeammate teammate = Config.getTeammate(uuid);
                String deviceHardwareAddress = device.getAddress(); // MAC address
                boolean changed = false;
                if (teammate == null) {
                    Log.d(Config.TAG,"New SqAN teammate found");
                    teammate = Config.saveTeammate(uuid,null,null);
                    teammate.setBluetoothMac(MacAddress.build(deviceHardwareAddress));
                    changed = true;
                } else {
                    if (teammate.getBluetoothMac() == null) {
                        teammate.setBluetoothMac(MacAddress.build(deviceHardwareAddress));
                        changed = true;
                    }
                }
                teammate.update();
                if (changed && (listener != null))
                    listener.onTeammateChanged(teammate);
            } catch (NumberFormatException e) {
                Log.e(Config.TAG,deviceName+" is an invalid SqAN device label");
            }
        }
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                // Discovery has found a device
                handleFoundDevice(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
            }
        }
    };
}
