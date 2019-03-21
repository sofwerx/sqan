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

import org.sofwerx.sqan.ipc.BftBroadcast;
import org.sofwerx.sqantest.tests.AbstractTest;
import org.sofwerx.sqantest.tests.TestTest;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqAnTestService extends Service implements IpcBroadcastTransceiver.IpcListener {
    private final static String TAG = "SqAnTestSvc";
    private final static int NOTIFICATION_ID = 1;
    private final static String NOTIFICATION_CHANNEL = "sqan_test";
    public final static String ACTION_STOP = "STOP";
    private AbstractTest test = new TestTest(); //FIXME this is for testing the test mechanism only
    private ArrayList<org.sofwerx.sqan.ipc.BftDevice> devices;
    private AtomicBoolean isRunning = new AtomicBoolean(false);
    private IpcBroadcastTransceiver.IpcListener uiIpcListener = null;
    private BftBroadcast bftBroadcast;

    private final IBinder mBinder = new SqAnTestBinder();

    /**
     * Gets the test currently loaded in SqAN Test
     * @return
     */
    public AbstractTest getTest() { return test; }
    public void setTest(AbstractTest test) { this.test = test; }

    @Override
    public void onChatPacketReceived(int origin, byte[] data) {
        if (uiIpcListener != null)
            uiIpcListener.onChatPacketReceived(origin, data);
    }

    @Override
    public void onSaBroadcastReceived(BftBroadcast broadcast) {
        this.bftBroadcast = broadcast;
        if (uiIpcListener != null)
            uiIpcListener.onSaBroadcastReceived(broadcast);
    }

    public BftBroadcast getLastBftBroadcast() { return bftBroadcast; }

    public class SqAnTestBinder extends Binder {
        public SqAnTestService getService() {
            return SqAnTestService.this;
        }
    }

    public void setUiIpcListener(IpcBroadcastTransceiver.IpcListener listener) { this.uiIpcListener = listener; }

    public void startTest() {
        if (test != null) {
            test.start();
            setForeground("SqAN Test "+test.getName()+" is running...");
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
        builder = new Notification.Builder(this);
        builder.setContentIntent(pendingIntent);
        builder.setSmallIcon(R.drawable.ic_notify);
        builder.setContentTitle(getResources().getString(R.string.app_name));
        builder.setTicker(message);
        builder.setContentText(message);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            buildNotificationChannel();
            builder.setChannelId(NOTIFICATION_CHANNEL);
        }

        Intent intentStop = new Intent(this, SqAnTestService.class);
        intentStop.setAction(ACTION_STOP);
        PendingIntent pIntentShutdown = PendingIntent.getService(this, 0, intentStop, PendingIntent.FLAG_UPDATE_CURRENT);
        builder.addAction(android.R.drawable.ic_lock_power_off, "Stop Test", pIntentShutdown);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void buildNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "SqAN Test";
            String description = "Testing Utility for SqAN";
            int importance = NotificationManager.IMPORTANCE_MIN;
            NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANNEL, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
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
