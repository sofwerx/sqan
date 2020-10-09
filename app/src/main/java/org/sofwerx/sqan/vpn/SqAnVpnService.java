package org.sofwerx.sqan.vpn;

import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.Pair;
import android.widget.Toast;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.R;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.VpnForwardValue;
import org.sofwerx.sqan.ui.SettingsActivity;
import org.sofwerx.sqan.util.AddressUtil;
import org.sofwerx.sqan.util.NetUtil;
import org.sofwerx.sqandr.util.StringUtils;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import androidx.annotation.NonNull;

public class SqAnVpnService extends VpnService implements Handler.Callback {
    private static final int SQAN_VPN_NOTIFICATION = 837;
    private static final String TAG = SqAnVpnService.class.getSimpleName();
    public static final String ACTION_CONNECT = "org.sofwerx.sqan.vpn.START";
    public static final String ACTION_DISCONNECT = "org.sofwerx.sqan.vpn.STOP";
    private FileOutputStream out;
    private Handler mHandler;
    private int lastMessage = R.string.disconnected;
    private static SqAnDevice thisDevice;
    private static int thisDeviceIp;

    public static void start(final Context context) {
        if (context != null) {
            Intent intent = new Intent(context, SqAnVpnService.class);
            intent.setAction(SqAnVpnService.ACTION_CONNECT);
            context.startService(intent);
            thisDevice = Config.getThisDevice();
            if (thisDevice != null)
                thisDeviceIp = AddressUtil.getSqAnVpnIpv4Address(thisDevice.getUUID());
        }
    }

    public static void stop(final Context context) {
        if (context != null) {
            Intent intent = new Intent(context, SqAnVpnService.class);
            intent.setAction(SqAnVpnService.ACTION_DISCONNECT);
            context.startService(intent);
            thisDevice = null;
        }
    }

    public void onReceived(final byte[] data) {
        if (data == null)
            return;
        if (out != null) {
            Log.d(TAG,data.length+"b VpnPacket data received from SqAN");
            try {
                if (Config.isVpnForwardIps() && (thisDevice != null)) {
                    int dest = NetUtil.getDestinationIpFromIpPacket(data);
                    byte[] destBytes = NetUtil.intToByteArray(dest);
                    int srcPort = NetUtil.getSourcePort(data);
                    int destPort = NetUtil.getDestinationPort(data);

                    if (dest != thisDeviceIp) {
                        VpnForwardValue forward = thisDevice.getIpForwardAddress(destBytes[1]);
                        if (forward != null) {
                            byte[] actualDest = NetUtil.intToByteArray(forward.getAddress());
                            Log.d(TAG,"VPN Packet received for "+AddressUtil.intToIpv4String(dest)+"(src port "+srcPort+", dest port "+destPort+") redirecting to "+AddressUtil.intToIpv4String(NetUtil.byteArrayToInt(actualDest)));
                            NetUtil.changeIpv4HeaderDst(data,actualDest);
                            SqAnVpnConnection.swapIpInPayload(data,destBytes,actualDest);
                        }
                    }
                } else {
                    //FIXME for testing
                    int dest = NetUtil.getDestinationIpFromIpPacket(data);
                    int destPort = NetUtil.getDestinationPort(data);
                    String ipAdd = AddressUtil.intToIpv4String(dest);
                    String port = Integer.toString(destPort);
                    Log.d(TAG,"VpnPkt in from SqAN ("+ipAdd+":"+port+"): "+new String(data,StandardCharsets.US_ASCII));
                    Log.d(TAG,"VpnPkt in from SqAN ("+ipAdd+":"+port+"): "+StringUtils.toHex(data));
                    //FIXME for testing
                }
                out.write(data);
            } catch (IOException e) {
                Log.e(TAG,"Unable to forward VpnPacket from SqAN to the VPN:"+e.getMessage());
            }
        } else
            Log.d(TAG,data.length+"b VpnPacket data received from SqAN, but VPN is not yet ready");
    }

    private final AtomicReference<Thread> mConnectingThread = new AtomicReference<>();
    private final AtomicReference<Connection> mConnection = new AtomicReference<>();
    private AtomicInteger mNextConnectionId = new AtomicInteger(1);
    private PendingIntent mConfigureIntent;
    private LiteWebServer webServer;

    private static class Connection extends Pair<Thread, ParcelFileDescriptor> {
        public Connection(Thread thread, ParcelFileDescriptor pfd) {
            super(thread, pfd);
        }
    }

    @Override
    public void onCreate() {
        if (mHandler == null)
            mHandler = new Handler(this);
        mConfigureIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, SettingsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_DISCONNECT.equals(intent.getAction())) {
            disconnect();
            return START_NOT_STICKY;
        } else {
            connect();
            return START_STICKY;
        }
    }

    @Override
    public void onDestroy() {
        disconnect();
    }

    @Override
    public boolean handleMessage(Message message) {
        Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show();
        lastMessage = message.what;
        if (message.what != R.string.disconnected)
            updateForegroundNotification(message.what);
        return true;
    }

    private void connect() {
        Config.setVpnEnabled(true);
        updateForegroundNotification(R.string.connecting);
        mHandler.sendEmptyMessage(R.string.connecting);
        startConnection(new SqAnVpnConnection(this, mNextConnectionId.getAndIncrement()));
        if (Config.isVpnHostLandingPage())
            webServer = new LiteWebServer();
        if (SqAnService.getInstance() != null)
            SqAnService.getInstance().setVpnService(this);
    }

    private void startConnection(final SqAnVpnConnection connection) {
        final Thread thread = new Thread(connection, "SqAnVpnThread");
        setConnectingThread(thread);
        connection.setConfigureIntent(mConfigureIntent);
        connection.setOnEstablishListener(tunInterface -> {
            mHandler.sendEmptyMessage(R.string.connected);
            mConnectingThread.compareAndSet(thread, null);
            setConnection(new Connection(thread, tunInterface));
        });
        thread.start();
    }

    private void setConnectingThread(final Thread thread) {
        final Thread oldThread = mConnectingThread.getAndSet(thread);
        if (oldThread != null)
            oldThread.interrupt();
    }
    private void setConnection(final Connection connection) {
        final Connection oldConnection = mConnection.getAndSet(connection);
        if ((connection != null) && (connection.second != null))
            out = new FileOutputStream(connection.second.getFileDescriptor());
        if (oldConnection != null) {
            try {
                oldConnection.first.interrupt();
                oldConnection.second.close();
            } catch (IOException e) {
                Log.e(TAG, "Closing VPN interface: "+e.getMessage());
            }
        }
    }

    private void disconnect() {
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignore) {
            }
            out = null;
        }
        try {
            SqAnService.getInstance().setVpnService(null);
        } catch (Exception ignore) {
        }
        if (webServer != null) {
            webServer.stop();
            webServer = null;
        }
        if (lastMessage != R.string.disconnected)
            mHandler.sendEmptyMessage(R.string.disconnected);
        setConnectingThread(null);
        setConnection(null);
        stopForeground(true);
    }

    private void updateForegroundNotification(final int message) {
        final String NOTIFICATION_CHANNEL_ID = "SqAnVpn";
        NotificationManager mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mNotificationManager.createNotificationChannel(new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, NOTIFICATION_CHANNEL_ID,
                    NotificationManager.IMPORTANCE_DEFAULT));
            builder = new Notification.Builder(this, NOTIFICATION_CHANNEL_ID);
        } else
            builder = new Notification.Builder(this);
        startForeground(SQAN_VPN_NOTIFICATION, builder
                .setSmallIcon(R.drawable.ic_vpn)
                .setContentText(getString(message))
                .setContentIntent(mConfigureIntent)
                .build());
    }
}