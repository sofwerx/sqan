package org.sofwerx.sqandr.serial;

import android.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.annotation.NonNull;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.util.StringUtil;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqandr.sdr.SdrException;
import org.sofwerx.sqandr.testing.PlutoStatus;
import org.sofwerx.sqandr.testing.SqandrStatus;
import org.sofwerx.sqandr.testing.TestListener;
import org.sofwerx.sqandr.util.SdrUtils;
import org.sofwerx.sqandr.util.StringUtils;

import java.nio.charset.StandardCharsets;

public class FakeSdr implements TestListener {
    private final static String TAG = Config.TAG+".FakeSDR";
    protected UsbDevice usbDevice;
    protected UsbInterface usbInterface;
    //protected UsbEndpoint usbEndpointFromSDR;
    //protected UsbEndpoint usbEndpointToSdr;
    protected UsbDeviceConnection usbConnection;
    protected UsbManager usbManager;
    protected AbstractDataConnection dataConnection;
    protected AbstractDataConnection commandConnection;
    private TestListener listener;
    private Context context;

    public void setUsbDevice(Context context, UsbManager usbManager, UsbDevice usbDevice) throws SdrException {
        Log.d(TAG,"setUsbDevice()");
        this.context = context;
        this.usbManager = usbManager;
        if (this.usbDevice != usbDevice) {
            if (usbDevice == null)
                shutdown();
            this.usbDevice = usbDevice;
            if (usbDevice == null)
                return;

            if (this.usbDevice != null) {
                int interfaceCount = usbDevice.getInterfaceCount();
                if (interfaceCount > 0) {
                    UsbInterface iface;
                    StringBuilder sb;
                    for (int i=0;i<interfaceCount;i++) {
                        sb = new StringBuilder();
                        iface = usbDevice.getInterface(i);
                        sb.append("Interface ("+i+") found: "+iface.getName()+"; class "+ SdrUtils.getUsbClass(iface.getInterfaceClass()));
                        if (iface.getEndpointCount() > 0) {
                            UsbEndpoint ep;
                            for (int a=0;a<iface.getEndpointCount();a++) {
                                ep = iface.getEndpoint(a);
                                sb.append("\n");
                                sb.append(" - Endpoint "+a+": "+ SdrUtils.getEndpointType(ep.getType())+", "+SdrUtils.getUsbDirection(ep.getDirection()));
                            }
                        }
                        Log.d(TAG,sb.toString());
                    }
                }

                Log.d(TAG,"Creating serial connection...");
                serialConnection = new SerialConnectionTest(null);
                serialConnection.open(context, usbDevice);
                serialConnection.setListener(this);
                dataConnection = serialConnection;
            }
        }
    }

    public String getInfo(@NonNull Context context) {
        if (usbDevice == null)
            return "no device connected";
        else {
            StringBuilder sb = new StringBuilder();
            sb.append(usbDevice.getProductName());
            sb.append(" (");
            sb.append(usbDevice.getDeviceName());
            sb.append(")\nv");
            sb.append(usbDevice.getVersion());
            sb.append(" serial ");
            sb.append(usbDevice.getSerialNumber());
            sb.append('\n');
            sb.append(usbDevice.getManufacturerName());
            if (serialConnection != null) {
                sb.append("\nSerial Connection: ");
                if (serialConnection.isActive())
                    sb.append("OPEN");
                else
                    sb.append("CLOSED");
            }
            sb.append('\n');
            return sb.toString();
        }
    }

    /**
     * Executes a request to the USB interface
     * @param direction direction (UsbConstants.USB_DIR_IN or UsbConstants.USB_DIR_OUT)
     * @param request request type
     * @param buffer  buffer
     * @return count of received bytes (negative == error)
     * @throws SdrException
     */
    protected int sendRequest(int direction, int request, /*int value, int index,*/ byte[] buffer) throws SdrException {
        if (buffer == null) {
            Log.w(TAG,"sendRequest ignored as buffer was empty");
            return 0;
        }
        if ((direction != UsbConstants.USB_DIR_IN) && (direction != UsbConstants.USB_DIR_OUT)) {
            Log.w(TAG, "sendRequest ignored as direction was "+direction+" and must UsbConstants.USB_DIR_IN or UsbConstants.USB_DIR_OUT");
            return 0;
        }

        int len = buffer.length;

        if( !usbConnection.claimInterface(usbInterface, true)) {
            Log.e(TAG, "Couldn't claim "+this.getClass().getSimpleName()+" USB Interface!");
            throw(new SdrException("Couldn't claim "+this.getClass().getSimpleName()+" USB Interface!"));
        }

        // Send Board ID Read request
        len = usbConnection.controlTransfer(
                direction | UsbConstants.USB_TYPE_VENDOR,	// Request Type
                request,	// Request
                0,
                0,
                //value,		// Value (unused)
                //index,		// Index (unused)
                buffer,		// Buffer
                len, 		// Length
                0			// Timeout
        );

        // Release usb interface
        this.usbConnection.releaseInterface(this.usbInterface);

        return len;
    }

    public void shutdown() {
        Log.d(TAG,"SDR shutting down...");
        if (commandConnection != null) {
            commandConnection.close();
            commandConnection = null;
        }
        if (dataConnection != null) {
            dataConnection.close();
            dataConnection = null;
        }
        if (serialConnection != null) {
            serialConnection.close();
            serialConnection = null;
        }
    }

    public void burst(byte[] data) {
        if (data == null)
            return;
        if ((dataConnection == null) || !dataConnection.isActive()) {
            Log.d(TAG,"Ignored burstPacket("+data.length+"b) as dataConnection not ready");
            return;
        }
        dataConnection.burstPacket(data);
    }

    /**
     * Send a command to the SDR
     * @param command
     * @return
     */
    public boolean sendCommand(String command) {
        if (command == null)
            return false;
        return sendCommand((command+"\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Send a command to the SDR
     * @param command
     * @return
     */
    public boolean sendCommand(final byte[] command) {
        boolean sent = false;

        if (command != null) {
            if ((commandConnection != null) && commandConnection.isActive()) {
                commandConnection.write(command);
                sent = true;
            }
        }

        return sent;
    }

    public void setAppRunning(boolean shouldRun) {
        if (serialConnection != null) {
            if (shouldRun) {
                serialConnection.launchSdrApp();
                serialConnection.startSdrApp();
            } else
                serialConnection.stopApp();
        }
    }

    public void setCommandFlags(String flags) {
        if (serialConnection != null)
            serialConnection.setCommandFlags(flags);
    }

    private SerialConnectionTest serialConnection;

    public final static int VENDOR_ID = 1110;

    @Override
    public void onDataReassembled(byte[] payloadData) {
        Log.d(TAG,"FakeSDR received: "+ StringUtils.toHex(payloadData));
        if (listener != null)
            listener.onDataReassembled(payloadData);
    }

    @Override
    public void onPacketDropped() {
        if (listener != null)
            listener.onPacketDropped();
    }

    @Override
    public void onReceivedSegment() {
        if (listener != null)
            listener.onReceivedSegment();
    }

    @Override
    public void onSqandrStatus(SqandrStatus status, String message) {
        if (listener != null)
            listener.onSqandrStatus(status, message);
    }

    @Override
    public void onPlutoStatus(PlutoStatus status, String message) {
        if (listener != null)
            listener.onPlutoStatus(status, message);
    }

    public void setListener(TestListener listener) {
        this.listener = listener;
    }

    public boolean isCongested() {
        if (dataConnection == null)
            return true;
        return dataConnection.isSdrConnectionCongested();
    }
}
