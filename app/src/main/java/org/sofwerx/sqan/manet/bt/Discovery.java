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
import org.sofwerx.sqan.SavedTeammate;
import org.sofwerx.sqan.manet.bt.helper.Core;
import org.sofwerx.sqan.manet.common.MacAddress;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.ui.StoredTeammateChangeListener;
import org.sofwerx.sqan.util.CommsLog;

import java.util.ArrayList;
import java.util.Set;

public class Discovery {
    private final static boolean RENAME_THIS_DEVICE = false; //should this device be renamed to broadcast that it is a SqAN device
    private final static int PAIRING_VARIANT_PIN = 1234;
    public final static int REQUEST_DISCOVERY = 101;
    public final static int REQUEST_ENABLE_BLUETOOTH = 102;
    private final static String SQAN_PREFIX = "sqan";
    public final static int DISCOVERY_DURATION_SECONDS = 60;
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

    public static Set<BluetoothDevice> getPairedDevices(Activity activity) {
        final BluetoothManager bluetoothManager = (BluetoothManager) activity.getSystemService(Context.BLUETOOTH_SERVICE);
        final BluetoothAdapter btAdapter = bluetoothManager.getAdapter();
        return btAdapter.getBondedDevices();
    }

    /**
     * Try to advertise
     */
    public void startAdvertising() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth Advertising started");
        //try to build teammates based on already paired devices
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                handleFoundDevice(device);
            }
        }

        //try discovery to pick-up other devices
        if (bluetoothAdapter.isEnabled()) {
            if (RENAME_THIS_DEVICE) {
                String currentName = bluetoothAdapter.getName();
                String correctUuid = SQAN_PREFIX + Config.getThisDevice().getUUID();
                if ((currentName == null) || !currentName.equalsIgnoreCase(correctUuid)) {
                    originalBtName = currentName;
                    bluetoothAdapter.setName(correctUuid);
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"Changing device Bluetooth name from " + originalBtName + " to " + correctUuid);
                }
            }
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, DISCOVERY_DURATION_SECONDS);
            activity.startActivityForResult(discoverableIntent,REQUEST_DISCOVERY);
        } else {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            activity.startActivityForResult(enableBtIntent,REQUEST_ENABLE_BLUETOOTH);
        }
    }

    public void stopAdvertising() {
        if (originalBtName != null) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth Advertising stopped");
            Log.d(Config.TAG,"stopping Advertising");
            if (bluetoothAdapter.isEnabled()) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"Restoring device bluetooth name to "+originalBtName);
                bluetoothAdapter.setName(originalBtName);
            }
        }
    }

    public void startDiscovery() {
        //try discovery to pick-up other devices
        if (bluetoothAdapter.isEnabled()) {
            if (activity != null) {
                IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
                filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
                filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
                filter.addAction(BluetoothDevice.ACTION_UUID);
                activity.registerReceiver(receiver, filter);
                if (bluetoothAdapter.startDiscovery())
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth discovery started");
                else
                    CommsLog.log(CommsLog.Entry.Category.PROBLEM,"Bluetooth discovery failed");
            }
        }

        //try to build teammates based on already paired devices
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        if (pairedDevices.size() > 0) {
            for (BluetoothDevice device : pairedDevices) {
                handleFoundDevice(device);
            }
        } else {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth discovery did not find any previously paired devices");
        }
    }

    private static boolean hasPairedMac(MacAddress mac, Set<BluetoothDevice> pairedDevices) {
        if ((pairedDevices != null) && !pairedDevices.isEmpty() && (mac != null) && mac.isValid()) {
            final String macString = mac.toString();
            for (BluetoothDevice device:pairedDevices) {
                if ((device != null) && macString.equalsIgnoreCase(device.getAddress()))
                    return true;
            }
        }
        return false;
    }

    public static void checkPairedDeviceStatus(BluetoothAdapter adapter) {
        if (adapter != null) {
            ArrayList<SavedTeammate> savedTeammates = Config.getSavedTeammates();
            if ((savedTeammates != null) && !savedTeammates.isEmpty()) {
                synchronized (savedTeammates) {
                    Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
                    for (SavedTeammate teammate : savedTeammates) {
                        if (teammate != null) {
                            teammate.setBtPaired(hasPairedMac(teammate.getBluetoothMac(), pairedDevices));
                        }
                    }
                }
            }
        }
    }

    public void stopDiscovery() {
        CommsLog.log(CommsLog.Entry.Category.STATUS,"Bluetooth discovery stopped");
        if (activity != null)
            activity.unregisterReceiver(receiver);
    }

    private void handleFoundDevice(BluetoothDevice device) {
        if (device == null)
            return;
        SavedTeammate teammate = Config.getTeammateByBtMac(new MacAddress(device.getAddress()));
        if (teammate != null) {
            if (teammate.isBtPaired()) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,teammate.getLabel()+" found but is already paired");
                return; //we already know about this device and are paired with it, ignore it
            }
            if (!teammate.isEnabled()) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,teammate.getLabel()+" found but is not set to be included in your team");
                return; //we already know about this device and are paired with it, ignore it
            }
        }
        String deviceName = device.getName();
        boolean isSqAn = Core.isSqAnSupported(device);
        //if (!isSqAn)
        //    isSqAn = (deviceName != null) && deviceName.startsWith(SQAN_PREFIX);
        if (isSqAn) {
            CommsLog.log(CommsLog.Entry.Category.STATUS,"SqAN Bluetooth device found: "+((deviceName==null)?"":deviceName)+" ("+device.getAddress()+")");
            int uuid = SqAnDevice.UNASSIGNED_UUID;
            if ((teammate == null) && (deviceName != null)) {
                try {
                    uuid = Integer.parseInt(deviceName.substring(SQAN_PREFIX.length()));
                    teammate = Config.getTeammate(uuid);
                } catch (NumberFormatException ignore) {
                }
            }

            String deviceHardwareAddress = device.getAddress(); // MAC address
            boolean changed = false;
            if (teammate == null) {
                CommsLog.log(CommsLog.Entry.Category.STATUS,"New SqAN teammate found");
                teammate = Config.saveTeammate(uuid,null,null,MacAddress.build(deviceHardwareAddress));
                if (teammate != null)
                    teammate.setBtName(device.getName());
                pairDevice(device);
                changed = true;
            } else {
                if (teammate.getBluetoothMac() == null) {
                    teammate.setBluetoothMac(MacAddress.build(deviceHardwareAddress));
                    teammate.setBtName(device.getName());
                    changed = true;
                }
                if (teammate.isEnabled() && !teammate.isBtPaired()) {
                    CommsLog.log(CommsLog.Entry.Category.STATUS,"Teammate "+teammate.getLabel()+" is not yet paired - attempting to correct that.");
                    pairDevice(device);
                }
            }
            if (changed) {
                teammate.update();
                if (listener != null)
                    listener.onTeammateChanged(teammate);
            }
        }
    }

    private void pairDevice(BluetoothDevice device) {
        if (device == null)
            return;
        /*try {
            String ACTION_PAIRING_REQUEST = "android.bluetooth.device.action.PAIRING_REQUEST";
            Intent intent = new Intent(ACTION_PAIRING_REQUEST);
            String EXTRA_DEVICE = "android.bluetooth.device.extra.DEVICE";
            intent.putExtra(EXTRA_DEVICE, device);
            String EXTRA_PAIRING_VARIANT = "android.bluetooth.device.extra.PAIRING_VARIANT";
            intent.putExtra(EXTRA_PAIRING_VARIANT, PAIRING_VARIANT_PIN);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(Config.TAG,"Unable to start intent to pairDevice: "+e.getMessage());*/
            device.createBond();
            Core.connectAsClientAsync(activity.getApplicationContext(),device,BtManetV2.getInstance());
        //}
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                handleFoundDevice(intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
                if (listener != null)
                    listener.onTeammateChanged(null);
            } else if (BluetoothDevice.ACTION_UUID.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (Core.isSqAnSupported(device)) {
                    handleFoundDevice(device);
                    if (listener != null)
                        listener.onTeammateChanged(null);
                }
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device != null) {
                    SavedTeammate teammate = Config.getTeammateByBtMac(new MacAddress(device.getAddress()));
                    if (teammate != null)
                        teammate.setBtPaired(true);
                    handleFoundDevice(device);
                    if (listener != null)
                        listener.onTeammateChanged(teammate);
                }
            //} else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(action)) {
            //    BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            //    if (device != null)
            //        device.setPin(Integer.toString(PAIRING_VARIANT_PIN).getBytes());
            }
        }
    };

    public void requestPairing(String mac) {
        if (mac == null) {
            Log.e(Config.TAG,"Discovery cannot request pairing with a null MAC");
            return;
        }
        CommsLog.log(CommsLog.Entry.Category.CONNECTION,"Requesting a pairing with "+mac);
        pairDevice(bluetoothAdapter.getRemoteDevice(mac));
    }
}
