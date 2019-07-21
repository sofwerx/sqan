package org.sofwerx.sqantest;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.sofwerx.sqan.ipc.BftBroadcast;
import org.sofwerx.sqan.ipc.BftDevice;
import org.sofwerx.sqantest.tests.AbstractTest;
import org.sofwerx.sqantest.tests.SimpleTest;
import org.sofwerx.sqantest.tests.support.TestException;
import org.sofwerx.sqantest.tests.support.TestPacket;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqAnTestService extends Service implements IpcBroadcastTransceiver.IpcListener {
    private final static String TAG = "SqAnTestSvc";
    public final static int BROADCAST_ADDRESS = Integer.MIN_VALUE;
    private final static int TEST_NOTIFICATION_ID = 200;
    private final static int NOTIFICATION_ID = 1;
    private final static String NOTIFICATION_CHANNEL = "sqan_test";
    private final static String REPORT_NOTIFICATION_CHANNEL = "sqan_rpt";
    public final static String ACTION_STOP = "STOP";
    private AbstractTest test = new SimpleTest(this); //FIXME this is for testing the test mechanism only
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private IpcBroadcastTransceiver.IpcListener uiIpcListener = null;
    private static BftBroadcast bftBroadcast;
    private int thisDevice = BROADCAST_ADDRESS;
    private NotificationChannel channel = null;
    private NotificationChannel reportChannel = null;

    private final IBinder mBinder = new SqAnTestBinder();

    /**
     * Gets the test currently loaded in SqAN Test
     * @return
     */
    public AbstractTest getTest() { return test; }
    public void setTest(AbstractTest test) { this.test = test; }

    public int getDeviceId() { return thisDevice; }

    @Override
    public void onChatPacketReceived(int origin, byte[] data) {
        if (uiIpcListener != null)
            uiIpcListener.onChatPacketReceived(origin, data);
        if (test != null)
            test.onOtherDataReceived(origin,(data==null)?0:data.length);
    }

    @Override
    public void onSaBroadcastReceived(BftBroadcast broadcast) {
        this.bftBroadcast = broadcast;
        if ((broadcast != null) && (thisDevice == BROADCAST_ADDRESS)) {
            ArrayList<BftDevice> devices = broadcast.getDevices();
            if ((devices != null) && !devices.isEmpty())
                thisDevice = devices.get(0).getUUID();
        }
        if (uiIpcListener != null)
            uiIpcListener.onSaBroadcastReceived(broadcast);
    }

    @Override
    public void onTestPacketReceived(TestPacket packet) {
        if (packet != null) {
            packet.setRcvTimeReceived();
            if (test != null)
                test.onTestPacketReceived(packet);
        }
    }

    @Override
    public void onError(TestException error) {
        Log.e(TAG,"Testing error: "+error.getMessage());
        if (test != null)
            test.onException(error);
    }

    @Override
    public void onOtherDataReceived(int origin, int size) {
        if (test != null)
            test.onOtherDataReceived(origin,size);
    }

    @Override
    public void onTestCommand(byte command) {
        AbstractTest newTest = AbstractTest.newFromCommand(this,command);
        if (test != null) {
            if (newTest == null)
                test.stop();
            else {
                if (test.getCommandType() == newTest.getCommandType()) {
                    if (!test.isRunning())
                        test.start();
                    return; //ignore as this test is already running
                } else
                    test.stop();
            }
        }
        test = newTest;
        notifyOfTest(test);
        if (uiIpcListener != null)
            uiIpcListener.onTestCommand(command);
    }

    public void notifyOfTest(AbstractTest test) {
        if (test != null)
            setForeground(test.getName()+" is "+(test.isRunning()?"running...":"paused"));
        else
            setForeground("SqAN Test is ready");
    }

    /*public void notifyOfTest(AbstractTest test) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (test == null)
            notificationManager.cancel(TEST_NOTIFICATION_ID);
        else {
            PendingIntent pendingIntent = null;
            try {
                Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqantest.ui.MainActivity"));
                pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            } catch (ClassNotFoundException ignore) {
            }

            String message = "Test "+test.getName()+" in progress";

            Notification.Builder builder;
            builder = new Notification.Builder(this);
            builder.setContentIntent(pendingIntent);
            builder.setSmallIcon(R.drawable.ic_notify);
            builder.setContentTitle("Testing...");
            builder.setTicker(message);
            builder.setContentText(message);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                buildNotificationChannel();
                builder.setChannelId(NOTIFICATION_CHANNEL);
            }

            notificationManager.notify(TEST_NOTIFICATION_ID, builder.build());
        }
    }*/

    public void notifyOfReport(AbstractTest test, File file) {
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (test == null)
            notificationManager.cancel(TEST_NOTIFICATION_ID);
        else {
            PendingIntent pendingIntent = null;
            try {
                Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqantest.ui.ReportActivity"));
                pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
            } catch (ClassNotFoundException ignore) {
            }

            Notification.Builder builder;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                buildReportNotificationChannel();
                builder = new Notification.Builder(this,REPORT_NOTIFICATION_CHANNEL);
            } else
                builder = new Notification.Builder(this);

            String message = test.getName();
            if ((file != null) && file.exists()) {
                Intent intentShareFile = new Intent(Intent.ACTION_SEND);
                intentShareFile.setType("application/octet-stream");
                intentShareFile.putExtra(Intent.EXTRA_STREAM,
                        FileProvider.getUriForFile(this, this.getApplicationContext().getPackageName() + ".report.provider", file));
                intentShareFile.putExtra(Intent.EXTRA_SUBJECT, file.getName());
                intentShareFile.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
                message += " and saved in " + file.getPath();
                PendingIntent pIntentShare = PendingIntent.getService(this, 0, intentShareFile, PendingIntent.FLAG_UPDATE_CURRENT);
                builder.addAction(android.R.drawable.ic_menu_share, "Share", pIntentShare);
            }

            builder.setContentIntent(pendingIntent);
            builder.setSmallIcon(R.drawable.ic_report);
            builder.setContentTitle("Report ready");
            builder.setTicker(message);
            builder.setContentText(message);

            notificationManager.notify(TEST_NOTIFICATION_ID, builder.build());
        }
    }

    public BftBroadcast getLastBftBroadcast() { return bftBroadcast; }

    public static BftDevice getDevice(int uuid) {
        if ((bftBroadcast != null) && (uuid > 0l)) {
            ArrayList<BftDevice> devices = bftBroadcast.getDevices();
            if ((devices != null) && !devices.isEmpty()) {
                for (BftDevice device:devices) {
                    if ((device != null) && (device.getUUID() != BROADCAST_ADDRESS) && (device.getUUID() == uuid))
                        return device;
                }
            }
        }
        return null;
    }

    public class SqAnTestBinder extends Binder {
        public SqAnTestService getService() {
            return SqAnTestService.this;
        }
    }

    public void setUiIpcListener(IpcBroadcastTransceiver.IpcListener listener) { this.uiIpcListener = listener; }

    public void startTest() {
        if (test != null) {
            test.start();
            setForeground(test.getName()+" is running...");
        }
    }

    @Override
    public void onDestroy() {
        IpcBroadcastTransceiver.unregister(this);
        isRunning.set(false);
        super.onDestroy();
    }

    public void shutdown() {
        if (test != null)
            test.stop();
        stopSelf();
    }

    private void setForeground(String message) {
        PendingIntent pendingIntent = null;
        try {
            Intent notificationIntent = new Intent(this, Class.forName("org.sofwerx.sqantest.ui.MainActivity"));
            pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        } catch (ClassNotFoundException ignore) {
        }

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildNotificationChannel();
            builder = new Notification.Builder(this,NOTIFICATION_CHANNEL);
        } else
            builder = new Notification.Builder(this);
        builder.setContentIntent(pendingIntent);
        builder.setSmallIcon(R.drawable.ic_notify);
        builder.setContentTitle(getResources().getString(R.string.app_name));
        builder.setTicker(message);
        builder.setContentText(message);

        Intent intentStop = new Intent(this, SqAnTestService.class);
        intentStop.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentStop, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(android.R.drawable.ic_lock_power_off, "Stop Test", pIntentShutdown);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void buildNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (channel == null) {
                CharSequence name = "SqAN Test";
                String description = "Testing Utility for SqAN";
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                channel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
                channel.setDescription(description);
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private void buildReportNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (reportChannel == null) {
                CharSequence name = "Reports";
                String description = "Reports generated by tests";
                int importance = NotificationManager.IMPORTANCE_DEFAULT;
                reportChannel = new NotificationChannel(REPORT_NOTIFICATION_CHANNEL, name, importance);
                reportChannel.setDescription(description);
                NotificationManager notificationManager = getSystemService(NotificationManager.class);
                notificationManager.createNotificationChannel(reportChannel);
            }
        }
    }

    public boolean isRunning() { return isRunning.get(); }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        synchronized (this) {
            if (intent != null) {
                String action = intent.getAction();
                if (ACTION_STOP.equalsIgnoreCase(action)) {
                    Log.d(TAG, "Shutting down SqAN Test");
                    stopSelf();
                    return START_NOT_STICKY;
                }
            }
            if (!isRunning.get()) {
                isRunning.set(true);
                IpcBroadcastTransceiver.register(this);
            }
            startTest(); //FIXME this is for testing the test mechanism only
            return START_STICKY;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }
}
