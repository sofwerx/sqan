package org.sofwerx.sqan.vpn;

import android.app.PendingIntent;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.SqAnService;
import org.sofwerx.sqan.manet.common.SqAnDevice;
import org.sofwerx.sqan.manet.common.packet.PacketHeader;
import org.sofwerx.sqan.manet.common.packet.VpnPacket;
import org.sofwerx.sqan.util.AddressUtil;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SqAnVpnConnection implements Runnable {
    private final static int MTU_SIZE = 1500;
    private static final int MAX_PACKET_SIZE = Short.MAX_VALUE; //Max packet size cannot exceed MTU constraint of Short
    private static final long IDLE_INTERVAL_MS = TimeUnit.MILLISECONDS.toMillis(100);
    private final VpnService vpnService;
    private SqAnService sqAnService;
    private SqAnDevice thisDevice;
    private final int id;
    private PendingIntent configureIntent;
    private OnEstablishListener listener;
    private AtomicBoolean keepGoing = new AtomicBoolean(true);

    public interface OnEstablishListener {
        void onEstablish(ParcelFileDescriptor tunInterface);
    }

    public SqAnVpnConnection(final VpnService service, final int connectionId) {
        vpnService = service;
        sqAnService = SqAnService.getInstance();
        thisDevice = Config.getThisDevice();
        id = connectionId;
    }

    public void setConfigureIntent(PendingIntent intent) {
        configureIntent = intent;
    }

    public void setOnEstablishListener(OnEstablishListener listener) {
        this.listener = listener;
    }

    @Override
    public void run() {
        ParcelFileDescriptor pfd = configure();
        if (pfd == null) {
            Log.d(getTag(),"run() exited as could not fet ParcelFileDescriptor");
            return;
        }

        FileInputStream in = new FileInputStream(pfd.getFileDescriptor());

        ByteBuffer packet = ByteBuffer.allocate(MAX_PACKET_SIZE);

        while (keepGoing.get()) {
            boolean idle = true;
            try {
                int length = in.read(packet.array());
                if (length > 0) {
                    packet.limit(length);
                    VpnPacket outgoing = new VpnPacket(new PacketHeader(thisDevice.getUUID()));
                    //FIXME add the destination address
                    packet.rewind();
                    byte[] rawBytes = new byte[length];
                    packet.get(rawBytes);
                    outgoing.setData(rawBytes);
                    sqAnService.burst(outgoing);

                    packet.clear();

                    idle = false;
                }
            } catch (IOException e) {
                Log.e(getTag(),"IOException: "+e.getMessage());
            } catch (BufferUnderflowException e) {
                Log.e(getTag(),"BufferUnderflowException: "+e.getMessage());
            }
            if (idle) {
                try {
                    Thread.sleep(IDLE_INTERVAL_MS);
                } catch (InterruptedException ignore) {
                }
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException e) {
                Log.e(getTag(), "Unable to close interface", e);
            }
        }
    }

    private ParcelFileDescriptor configure() {
        if (thisDevice == null) {
            Log.e(getTag(),"Cannot configure VPN as SqAnDevice is null");
            return null;
        }
        VpnService.Builder builder = vpnService.new Builder();
        builder.setMtu(MTU_SIZE);
        builder.addAddress(thisDevice.getVpnIpv4AddressString(),16);
        builder.addRoute(AddressUtil.VPN_NET_MASK,16);
        final ParcelFileDescriptor vpnInterface;
        builder.setSession("SqAnVpn").setConfigureIntent(configureIntent);
        synchronized (vpnService) {
            vpnInterface = builder.establish();
            if (listener != null)
                listener.onEstablish(vpnInterface);
        }
        Log.i(getTag(), "New interface: " + vpnInterface);
        return vpnInterface;
    }

    private final String getTag() {
        return Config.TAG + "[" + id + "]";
    }
}