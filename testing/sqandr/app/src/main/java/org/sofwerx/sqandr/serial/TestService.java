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
import org.sofwerx.sqan.rf.SignalConverter;
import org.sofwerx.sqandr.sdr.SdrException;
import org.sofwerx.sqandr.sdr.sar.Segment;
import org.sofwerx.sqandr.sdr.sar.Segmenter;
import org.sofwerx.sqandr.testing.OneStats;
import org.sofwerx.sqandr.testing.PlutoStatus;
import org.sofwerx.sqandr.testing.SqandrStatus;
import org.sofwerx.sqandr.testing.Stats;
import org.sofwerx.sqandr.testing.TestListener;
import org.sofwerx.sqandr.testing.TestPacket;
import org.sofwerx.sqandr.util.PermissionsHelper;
import org.sofwerx.sqandr.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class TestService implements TestListener {
    private final static String TAG = Config.TAG+".sqandr";
    private final static String SDR_USB_PERMISSION = "org.sofwerx.sqandr.SDR_USB_PERMISSION";
    private final static long PERIODIC_TASK_INTERVAL = 1000l*10l;
    private Context context;
    private HandlerThread sdrThread;
    private Handler handler;
    private BroadcastReceiver usbBroadcastReceiver;
    private BroadcastReceiver permissionBroadcastReceiver;
    private FakeSdr fakeSdr;
    private TestListener listener;
    private boolean isSqandrRunning = false;
    private boolean sendData = false;
    private Thread txThread;
    //private long thisDevice = 1234l;
    private long thisDevice = System.currentTimeMillis();
    private Stats stats = new Stats();
    private SqandrStatus appStatus = SqandrStatus.OFF;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);

    public TestService(@NonNull Context context) {
        this.context = context;
        if (context instanceof TestListener)
            listener = (TestListener) context;
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
        txThread = new TxThread();
        txThread.start();
    }

    private final static void testIqParsing() {
        SignalConverter conv = new SignalConverter(false);
        class PairIQ {
            int i,q;
            PairIQ(int i, int q) {
                this.i = i;
                this.q = q;
            }
        }

        //101101010011 01010101
        PairIQ[] pairs = {
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(30000,0),        //1

                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
                new PairIQ(-30000,0),        //0
                new PairIQ(30000,0),        //1
        };

        for (int i=0;i<pairs.length;i++) {
            conv.onNewIQ(pairs[i].i,pairs[i].q);
            if (conv.hasByte()) {
                byte popped = conv.popByte();
                StringBuilder sbO = new StringBuilder();
                sbO.append("Popped byte: ");
                sbO.append(StringUtils.toHex(popped));
                Log.d(TAG,sbO.toString());
            }
        }
        Log.d(TAG,"test done");
    }

    public boolean isCongested() {
        if (fakeSdr == null)
            return true;
        return fakeSdr.isCongested();
    }

    private long nextTxTime = Long.MIN_VALUE;
    private long intervalBetweenTx = 1000l;
    private int myPacketIndex = 0;

    public void setIntervalBetweenTx(long intervalInMs) { intervalBetweenTx = intervalInMs; }

    public Stats getStats() { return stats; }

    public SqandrStatus getAppStatus() { return appStatus; }

    public boolean isAppRunning() {
        return appStatus == SqandrStatus.RUNNING;
    }

    public void setSendData(boolean send) {
        this.sendData = send;
        if (send)
            stats.clear();
    }

    public boolean isSendData() {
        return sendData;
    }

    private class TxThread extends Thread {
        private ArrayList<Segment> segs;
        private byte[] data;
        private long lag;

        @Override
        public void run() {
            Log.d(TAG,"TxThread starting...");
            while(keepGoing.get()) {
                isSqandrRunning = isAppRunning();
                while (isSqandrRunning && sendData) {
                    if (nextTxTime > 0l)
                        lag = nextTxTime - System.currentTimeMillis();
                    else
                        lag = 0l;
                    if (lag > 0l) {
                        if (lag > 2l) {
                            try {
                                sleep(lag - 2);
                            } catch (InterruptedException ignore) {
                            }
                        }
                    } else {
                        nextTxTime = System.currentTimeMillis() + intervalBetweenTx;
                        data = getNextTestData();
                        Log.d(TAG,"Next test packet: "+StringUtils.toHex(data));
                        stats.statsMe.incrementTotalSent();
                        stats.incrementPacketsSent();
                        burst(data);
                    }
                }
                try {
                    sleep(1000);
                } catch (InterruptedException ignore) {
                }
            }
        }
    }

    private byte[] getNextTestData() {
        myPacketIndex++;
        TestPacket pkt = new TestPacket(thisDevice,myPacketIndex);
        return pkt.toBytes();
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
                        fakeSdr.setListener(this);
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
                listener.onPlutoStatus(PlutoStatus.OFF,result.toString());
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
        keepGoing.set(false);
        isSqandrRunning = false;
        listener = null;
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

    public void setListener(TestListener listener) {
        this.listener = listener;
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
                                    listener.onPlutoStatus(PlutoStatus.UP,fakeSdr.getInfo(context));
                                }
                            } catch (SdrException e) {
                                String errorMessage = "Unable to set the SDR connection: " + e.getMessage();
                                Log.e(TAG, errorMessage);
                                if (listener != null)
                                    listener.onPlutoStatus(PlutoStatus.ERROR,errorMessage);
                            }
                        } else {
                            Log.e(TAG, "Permission denied for device " + device.getDeviceName());
                            Toast.makeText(context, "Permission denied to open SDR at " + device.getDeviceName(), Toast.LENGTH_LONG).show();
                            if (listener != null)
                                listener.onPlutoStatus(PlutoStatus.ERROR,"Permission denied for device " + device.getDeviceName());
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
                listener.onPlutoStatus(PlutoStatus.ERROR,"No SDR device attached");
            else
                listener.onPlutoStatus(PlutoStatus.OFF,fakeSdr.getInfo(context));
        }
    }

    private void burst(final byte[] data) {
        if (data == null)
            return;
        if (handler != null) {
            handler.post(() -> {
                if (fakeSdr == null) {
                    Log.d(TAG,"Ignoring burstPacket, SDR not ready");
                    return;
                }
                fakeSdr.burst(data);
                stats.incrementBytesSent(data.length);
                Log.d(TAG, "burstPacket(" + data.length + "b)");
            });
        }
    }

    private ArrayList<Integer> myStored = new ArrayList<>();
    private ArrayList<Integer> otherStored = new ArrayList<>();

    @Override
    public void onDataReassembled(byte[] data) {
        Log.d(TAG,"TestService.onReceiveDataLinkData("+((data==null)?"no ":data.length)+"b): "+ StringUtils.toHex(data));
        TestPacket pkt = new TestPacket(data);
        if (pkt.isValid()) {
            OneStats statsToUse;
            ArrayList<Integer> current;
            Log.d(TAG,"Valid packet received from "+pkt.getDevice());
            if (pkt.getDevice() == thisDevice) {
                statsToUse = stats.statsMe;
                current = myStored;
            } else {
                statsToUse = stats.statsOther;
                current = otherStored;
            }

            int index = pkt.getIndex();
            if (index > 0) {
                boolean unique = true;
                for (Integer one : current) {
                    if (one.intValue() == index)
                        unique = false;
                }
                if (unique) {
                    statsToUse.incrementUnique();
                    statsToUse.addBytes(data.length);
                    current.add(new Integer(index));
                    if (current.size() > 40)
                        current.remove(0);
                }

                if (index < statsToUse.getTotal() + 100)
                    statsToUse.setTotal(index);
                else
                    statsToUse.incrementTotalSent();
                statsToUse.incrementComplete();
            } else
                statsToUse.incrementTotalSent();

            Log.d(TAG,"TestService received: "+StringUtils.toHex(data));
            if (listener != null)
                listener.onDataReassembled(data);
        } else {
            stats.partialPackets++;
            if (listener != null)
                listener.onPacketDropped();
        }
    }

    @Override
    public void onPacketDropped() {
        stats.partialPackets++;
    }

    @Override
    public void onReceivedSegment() {
        stats.segments++;
    }

    @Override
    public void onSqandrStatus(SqandrStatus status, String message) {
        appStatus = status;
        isSqandrRunning = isAppRunning();
        if (listener != null)
            listener.onSqandrStatus(status, message);
    }

    @Override
    public void onPlutoStatus(PlutoStatus status, String message) {
        if (listener != null)
            listener.onPlutoStatus(status, message);
    }

    public void setAppRunning(boolean shouldRun) {
        if (shouldRun)
            stats.clear();
        if (fakeSdr != null)
            fakeSdr.setAppRunning(shouldRun);
    }

    public void setCommandFlags(String flags) {
        if (fakeSdr != null)
            fakeSdr.setCommandFlags(flags);
    }
}
