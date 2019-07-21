package org.sofwerx.sqandr.serial;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.SqANDRListener;
import org.sofwerx.sqandr.sdr.DataConnectionListener;
import org.sofwerx.sqandr.sdr.SdrException;
import org.sofwerx.sqandr.util.PermissionsHelper;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class TestService implements DataConnectionListener {
    private final static String TAG = Config.TAG+".sqandr";
    private final static String SDR_USB_PERMISSION = "org.sofwerx.sqandr.SDR_USB_PERMISSION";
    private final static long PERIODIC_TASK_INTERVAL = 1000l * 10l;
    private Context context;
    private HandlerThread sdrThread;
    private Handler handler;
    private SqANDRListener listener;
    private BroadcastReceiver usbBroadcastReceiver;
    private BroadcastReceiver permissionBroadcastReceiver;
    private DataConnectionListener dataConnectionListener;
    private FakeSdr fakeSdr;
    private PeripheralStatusListener peripheralStatusListener;

    public TestService(@NonNull Context context) {
        this.context = context;
        if (context instanceof SqANDRListener)
            listener = (SqANDRListener) context;
        if (context instanceof Activity)
            PermissionsHelper.checkForPermissions((Activity)context);
        Log.d(TAG,"Starting TestService...");
        sdrThread = new HandlerThread("TestSrvc") {
            @Override
            protected void onLooperPrepared() {
                Log.d(TAG,"TestService started");
                handler = new Handler(sdrThread.getLooper());
                handler.postDelayed(periodicTask,PERIODIC_TASK_INTERVAL);
                init();
            }
        };
        sdrThread.start();
    }

    private Runnable periodicTask = new Runnable() {
        @Override
        public void run() {
            //TODO handle any periodic tasks here
            if (handler != null)
                handler.postDelayed(periodicTask, PERIODIC_TASK_INTERVAL);
        }
    };

    private void post(Runnable runnable) {
        if ((runnable == null) || (handler == null))
            return;
        handler.post(runnable);
    }

    private void updateUsbDevices(final Context context) {
        post(() -> {
            Log.d(TAG,"Updating USB devices...");
            StringBuilder result = new StringBuilder();
            final UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
            HashMap<String, UsbDevice> deviceList = manager.getDeviceList();
            int count = 0;
            if ((deviceList != null) && !deviceList.isEmpty()) {
                Iterator it = deviceList.entrySet().iterator();
                while (it.hasNext()) {
                    count++;
                    Map.Entry<String, UsbDevice> pair = (Map.Entry)it.next();
                    if (pair.getValue() == null)
                        continue;
                    String message = "USB Device available: "+pair.getKey()+", "+pair.getValue().getProductName()+" Manufacturer "+pair.getValue().getVendorId()+" product "+pair.getValue().getProductId()+'\n';
                    result.append(message);
                    result.append('\n');
                    Log.d(TAG,message);
                    if (fakeSdr == null) {
                        fakeSdr = new FakeSdr();
                        fakeSdr.setDataConnectionListener(this);
                        fakeSdr.setPeripheralStatusListener(peripheralStatusListener);
                        if (peripheralStatusListener != null)
                            peripheralStatusListener.onPeripheralMessage("SDR found");
                        else
                            Log.d("SqAN.oPM","TestService.updateUsbDevices psl is NULL");
                        final UsbDevice device = pair.getValue();
                        post(() -> setUsbDevice(context,manager,device) );
                    }
                }
            }

            if (count == 0) {
                fakeSdr = null;
                result.append("No USB devices detected");
            } else {
                result.append(count+" USB device"+((count==1)?"":"s")+" detected\r\n");
                if (fakeSdr == null)
                    result.append("but no SDR was found");
                else {
                    result.append(fakeSdr.getClass().getSimpleName() + " was found");
                    getInfo();
                }
            }
            if (listener != null)
                listener.onSdrMessage(result.toString());
        });
    }

    private void init() {
        if (usbBroadcastReceiver == null) {
            usbBroadcastReceiver = new org.sofwerx.sqandr.serial.TestService.UsbBroadcastReceiver();
            IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(usbBroadcastReceiver, filter);
        }
        updateUsbDevices(context);
    }

    public void shutdown() {
        peripheralStatusListener = null;
        Log.d(TAG,"Shutting down TestService...");
        Log.d(TAG,"Shutting down TestService...");
        if (usbBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(usbBroadcastReceiver);
                Log.d(TAG,"Unregistered USB Broadcast Receiver");
            } catch (Exception e) {
                Log.e(TAG,"Unable to unregister USB Broadcast Receiver: "+e.getMessage());
            }
        }
        if (permissionBroadcastReceiver != null) {
            try {
                context.unregisterReceiver(permissionBroadcastReceiver);
                Log.d(TAG,"Unregistered Permission Broadcast Receiver");
            } catch (Exception e) {
                Log.e(TAG,"Unable to unregister Permission Broadcast Receiver: "+e.getMessage());
            }
        }
        if (fakeSdr != null) {
            fakeSdr.shutdown();
            fakeSdr = null;
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
            sdrThread.quitSafely();
            handler = null;
            sdrThread = null;
        }
    }

    public void setListener(SqANDRListener listener) {
        this.listener = listener;
        if (listener != null) {
            /*if (manet != null) {
                switch (manet.getStatus()) {
                    case CONNECTED:
                        listener.onSdrReady(true);
                        break;
                    case ERROR:
                        listener.onSdrError(null);
                    case OFF:
                    case ADVERTISING:
                    case DISCOVERING:
                    case CHANGING_MEMBERSHIP:
                    case ADVERTISING_AND_DISCOVERING:
                        listener.onSdrReady(false);
                        break;
                }
            }*/
        }
    }

    @Override
    public void onConnect() {
        if (dataConnectionListener != null)
            dataConnectionListener.onConnect();
    }

    @Override
    public void onDisconnect() {
        //TODO
        if (dataConnectionListener != null)
            dataConnectionListener.onDisconnect();
    }

    @Override
    public void onReceiveDataLinkData(byte[] data) {
        Log.d(TAG,"TestService.onReceiveDataLinkData("+((data==null)?"no ":data.length)+"b)");
        if (listener != null) {
            listener.onPacketReceived(data);
        } else
            Log.d(TAG,"...but ignored as there is no TestService Listener");
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveDataLinkData(data);
    }

    @Override
    public void onReceiveCommandData(byte[] data) {
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveCommandData(data);
        //TODO on command received
    }

    @Override
    public void onConnectionError(String message) {
        if (listener != null)
            listener.onSdrError(message);
        if (dataConnectionListener != null)
            dataConnectionListener.onConnectionError(message);
    }

    public void setDataConnectionListener(DataConnectionListener dataConnectionListener) { this.dataConnectionListener = dataConnectionListener; }

    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        peripheralStatusListener = listener;
        if (fakeSdr == null) {
            if (listener != null)
                listener.onPeripheralError("SDR not connected");
        } else
            fakeSdr.setPeripheralStatusListener(listener);
    }

    private class UsbBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            StringBuilder sb = new StringBuilder();
            sb.append("Action: " + intent.getAction() + "\n");
            sb.append("URI: " + intent.toUri(Intent.URI_INTENT_SCHEME) + "\n");
            String log = sb.toString();
            Log.d(TAG, log);
            updateUsbDevices(context);
        }
    }

    private void setUsbDevice(final Context context, final UsbManager manager, UsbDevice device) {
        if (fakeSdr == null) {
            Log.e(TAG,"Cannot set USB Device as sdrDevice has not yet been assigned.");
            return;
        }
        if (permissionBroadcastReceiver == null) {
            permissionBroadcastReceiver = new BroadcastReceiver() {
                public void onReceive(Context context, Intent intent) {
                    if (SDR_USB_PERMISSION.equals(intent.getAction())) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                            Log.d(TAG, "Permission granted for device " + device.getDeviceName());
                            try {
                                fakeSdr.setUsbDevice(context, manager, device);
                                if (listener != null) {
                                    Log.d(TAG,"SDR is ready");
                                    listener.onSdrMessage(fakeSdr.getInfo(context));
                                }
                            } catch (SdrException e) {
                                String errorMessage = "Unable to set the SDR connection: " + e.getMessage();
                                Log.e(TAG, errorMessage);
                                if (listener != null) {
                                    listener.onSdrError(errorMessage);
                                    listener.onSdrReady(false);
                                }
                            }
                        } else {
                            Log.e(TAG, "Permission denied for device " + device.getDeviceName());
                            Toast.makeText(context, "Permission denied to open SDR at " + device.getDeviceName(), Toast.LENGTH_LONG).show();
                            if (listener != null) {
                                listener.onSdrError("Permission denied for device " + device.getDeviceName());
                                listener.onSdrReady(false);
                            }
                        }
                    }
                    try {
                        context.unregisterReceiver(permissionBroadcastReceiver);
                        Log.d(TAG,"Permission Broadcast Receiver unregistered");
                    } catch (Exception e) {
                        Log.e(TAG,"Unable to unregister the Permission Broadcast Receiver");
                    }
                    permissionBroadcastReceiver = null;
                }
            };

            PendingIntent mPermissionIntent = PendingIntent.getBroadcast(context, 0, new Intent(SDR_USB_PERMISSION), 0);
            IntentFilter filter = new IntentFilter(SDR_USB_PERMISSION);
            context.registerReceiver(permissionBroadcastReceiver, filter);
            Log.d(TAG,"Permission Broadcast Receiver registered");

            // Fire the request:
            manager.requestPermission(device, mPermissionIntent);
            Log.d(TAG, "Permission request for device " + device.getDeviceName() + " was sent. waiting...");
        }
    }

    public void getInfo() {
        if (listener != null) {
            if (fakeSdr == null)
                listener.onSdrMessage("No SDR device attached");
            else
                listener.onSdrMessage(fakeSdr.getInfo(context));
        }
    }

    public void burst(final byte[] data) {
        if (data == null)
            return;
        /*if (data.length > SdrSocket.MAX_PACKET_SIZE) {
            Log.e(TAG,"Error, cannot burstPacket a packet bigger than "+SdrSocket.MAX_PACKET_SIZE+"b over SqANDR - this "+data.length+"b packet is being dropped. Consider segmenting or restructuring.");
            return;
        }*/
        if (handler != null) {
            handler.post(() -> {
                /*if ((sdrSocket == null) || !sdrSocket.isActive()) {
                    Log.d(TAG,"Ignoring burstPacket, SdrSocket not ready");
                    return;
                }
                sdrSocket.write(data);*/
                if (fakeSdr == null) {
                    Log.d(TAG,"Ignoring burstPacket, SDR not ready");
                    return;
                }
                fakeSdr.burst(data);
                Log.d(TAG, "burstPacket(" + data.length + "b)");
            });
        }
    }

    public void onPacketReceived(final byte[] data) {
        if (handler != null) {
            handler.post(() -> {
                if (listener != null)
                    listener.onPacketReceived(data);
            });
        }
    }

    @Override
    public void onPacketDropped() {
        if (handler != null) {
            handler.post(() -> {
                if (listener != null)
                    listener.onPacketDropped();
            });
        }
    }

    @Override
    public void onOperational() {
        if (listener != null)
            listener.onSdrReady(true);
    }
}
