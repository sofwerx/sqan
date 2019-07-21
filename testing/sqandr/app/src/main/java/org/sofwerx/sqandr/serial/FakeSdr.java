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
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.sdr.AbstractDataConnection;
import org.sofwerx.sqandr.sdr.DataConnectionListener;
import org.sofwerx.sqandr.sdr.SdrException;
import org.sofwerx.sqandr.util.SdrUtils;

import java.nio.charset.StandardCharsets;

public class FakeSdr implements DataConnectionListener {
    private final static String TAG = Config.TAG+".FakeSDR";
    protected UsbDevice usbDevice;
    protected UsbInterface usbInterface;
    //protected UsbEndpoint usbEndpointFromSDR;
    //protected UsbEndpoint usbEndpointToSdr;
    protected UsbDeviceConnection usbConnection;
    protected UsbManager usbManager;
    protected AbstractDataConnection dataConnection;
    protected AbstractDataConnection commandConnection;
    protected DataConnectionListener dataConnectionListener;
    protected PeripheralStatusListener peripheralStatusListener;
    private Context context;

    public void setDataConnectionListener(DataConnectionListener listener) { this.dataConnectionListener = listener; }

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
                serialConnection = new SerialConnectionTest(commands);
                serialConnection.open(context, usbDevice);
                serialConnection.setListener(this);
                serialConnection.setPeripheralStatusListener(peripheralStatusListener);
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

    @Override
    public void onOperational() {
        //ignore
    }

    @Override
    public void onDisconnect() {
        if (dataConnectionListener != null)
            dataConnectionListener.onDisconnect();
    }

    @Override
    public void onConnectionError(String message) {
        if (dataConnectionListener != null)
            dataConnectionListener.onConnectionError(message);
    }

    @Override
    public void onReceiveDataLinkData(byte[] data) {
        Log.d(TAG,"AbstractSdr.onReceiveDataLinkData("+((data==null)?"no ":data.length)+"b)");
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveDataLinkData(data);
        else
            Log.d(TAG,"...but ignored as there is no AbstractSDR DataConnectionListener");
    }

    @Override
    public void onReceiveCommandData(byte[] data) {
        if (dataConnectionListener != null)
            dataConnectionListener.onReceiveCommandData(data);
    }

    @Override
    public void onPacketDropped() {
        if (dataConnectionListener != null)
            dataConnectionListener.onPacketDropped();
    }

    private SerialConnectionTest serialConnection;
    private String commands;

    public final static int VENDOR_ID = 1110;

    @Override
    public void onConnect() {
        if (dataConnectionListener != null)
            dataConnectionListener.onConnect();
        if ((serialConnection != null) && serialConnection.isActive()) {
            dataConnection = serialConnection;
            commandConnection = serialConnection;
        }
    }

    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        peripheralStatusListener = listener;
        if (dataConnection != null)
            dataConnection.setPeripheralStatusListener(listener);
        if (serialConnection != null)
            serialConnection.setPeripheralStatusListener(listener);
        else
            Log.d(TAG,"Unable to set Peripheral Listener as the Serial Connection has not been assigned yet");
    }
}
