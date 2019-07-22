package org.sofwerx.sqandr.sdr;

import org.sofwerx.notdroid.content.Context;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.Log;

import androidx.annotation.NonNull;

//import com.github.mjdev.libaums.UsbMassStorageDevice;

import org.sofwerx.sqan.Config;
import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.sdr.hackrf.HackRfSDR;
import org.sofwerx.sqandr.sdr.lime.LimeSDR;
import org.sofwerx.sqandr.sdr.pluto.PlutoSDR;
import org.sofwerx.sqandr.serial.SerialConnection;
import org.sofwerx.sqandr.util.SdrUtils;

import java.nio.charset.StandardCharsets;

public abstract class AbstractSdr implements DataConnectionListener {
    private final static String TAG = Config.TAG+".SDR";
    protected UsbDevice usbDevice;
    protected UsbInterface usbInterface;
    protected UsbEndpoint usbEndpointFromSDR;
    protected UsbEndpoint usbEndpointToSdr;
    protected UsbDeviceConnection usbConnection;
    protected UsbManager usbManager;
    //protected UsbStorageDeprecated2 usbStorageDep2;
    protected UsbStorage usbStorage;
    protected SerialConnection serialConnection;
    //protected SerialListener serialListener;
    //protected WriteableInputStream inputStream;
    //protected OutputStream outputStream;
    //public enum CommandPath {SERIAL,USB_INTERFACE};
    protected AbstractDataConnection dataConnection;
    protected AbstractDataConnection commandConnection;
    protected DataConnectionListener dataConnectionListener;
    protected PeripheralStatusListener peripheralStatusListener;

    public static AbstractSdr newFromVendor(int vendorId) {
        switch (vendorId) {
            case HackRfSDR.VENDOR_ID:
                return new HackRfSDR();

            case PlutoSDR.VENDOR_ID:
                return new PlutoSDR();

            case LimeSDR.VENDOR_ID:
                return new LimeSDR();
        }
        return null;
    }

    public boolean isSdrConnectionRecentlyCongested() {
        if (dataConnection == null)
            return false;
        return dataConnection.isSdrConnectionRecentlyCongested();
    }

    public void setDataConnectionListener(DataConnectionListener listener) { this.dataConnectionListener = listener; }

    protected void setCommLinkUsbInterface(UsbInterface usbInterface) {
        if (usbInterface == null) {
            Log.w(TAG,"Unable to setCommLinkUsbInterface() as provided usbInterface was null");
            return;
        }
        Log.i(TAG,"setUsbDevice: [interface 0] interface protocol: " + usbInterface.getInterfaceProtocol()
                + " subclass: " + usbInterface.getInterfaceSubclass());
        Log.i(TAG,"setUsbDevice: [interface 0] interface class: " + usbInterface.getInterfaceClass());
        Log.i(TAG,"setUsbDevice: [interface 0] endpoint count: " + usbInterface.getEndpointCount());
        usbEndpointFromSDR = usbInterface.getEndpoint(0);
        if (usbInterface.getEndpointCount() > 1)
            usbEndpointToSdr = usbInterface.getEndpoint(1);
        else {
            Log.w(TAG,"usbEndpointToSdr set to same as usbEndpointFromSDR since only one endpoint exists");
            usbEndpointToSdr = usbEndpointFromSDR;
        }
    }

    //public void setSerialListener(SerialListener listener) { this.serialListener = listener; }

    public void setUsbDevice(android.content.Context context,UsbManager usbManager, UsbDevice usbDevice) throws SdrException {
        Log.d(TAG,"setUsbDevice()");
        this.usbManager = usbManager;
        if (this.usbDevice != usbDevice) {
            if (usbDevice == null)
                shutdown();
            this.usbDevice = usbDevice;
            if (usbDevice == null)
                return;

            if (this.usbDevice != null) {
                if (useMassStorage()) {
                    Log.d(TAG,"Connecting to mass storage...");
                    usbStorage = UsbStorage.getInstance();
                }
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
                        if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                            //TODO possible claim the RNDIS interface and try to FTP a file across or maybe try the mount command - see https://linuxhint.com/list-usb-devices-linux/
                        }
                        //TODO handle the other interfaces
                    }
                }

                if (useSerialConnection()) {
                    Log.d(TAG,"Creating serial connection...");
                    serialConnection = new SerialConnection(getTerminalUsername(),getTerminalPassword());
                    serialConnection.open(context, usbDevice);
                    serialConnection.setListener(this);
                    serialConnection.setPeripheralStatusListener(peripheralStatusListener);
                }
            }
        }
    }

    protected abstract String getTerminalUsername();
    protected abstract String getTerminalPassword();

    public String getInfo(@NonNull Context context) {
        return this.getInfo(context.toAndroid());
    }

        public String getInfo(@NonNull android.content.Context context) {
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
            if (usbStorage != null) {
                if (usbStorage.isMounted()) {
                    sb.append("\nFiles available:\n");
                    sb.append(usbStorage.getFileNames(context,true));
                } else
                    sb.append("\nUSB Storage not yet mounted");
            }
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

    public void setUsbStorage(UsbStorage usbStorage) {
        this.usbStorage = usbStorage;
    }

    public void shutdown() {
        Log.d(TAG,"SDR shutting down...");
        /*if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                Log.e(TAG,"Unable to close input stream: "+e.getMessage());
            }
            inputStream = null;
        }
        if (outputStream != null) {
            try {
                outputStream.close();
            } catch (IOException e) {
                Log.e(TAG,"Unable to close output stream: "+e.getMessage());
            }
        }*/
        if (commandConnection != null) {
            commandConnection.close();
            commandConnection = null;
        }
        if (dataConnection != null) {
            dataConnection.close();
            dataConnection = null;
        }
        UsbStorage.stop();
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

    protected abstract boolean useSerialConnection();
    public abstract boolean useMassStorage();

    //public boolean isStreamingReady() { return ((inputStream != null) && (outputStream != null)); }
    //public InputStream getInputStream() { return inputStream; }
    //public OutputStream getOutputStream() { return outputStream; }

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

    /*@Override
    public void onSerialConnect() {
        if (serialListener != null)
            serialListener.onSerialConnect();
    }

    @Override
    public void onSerialError(Exception e) {
        if (serialListener != null)
            serialListener.onSerialError(e);
    }

    @Override
    public void onSerialRead(byte[] data) {
        if ((commandConnection != serialConnection) && (dataConnection != serialConnection)) {
            if (serialListener != null)
                serialListener.onSerialRead(data);
        }
    }

    @Override
    public void onDataConnectionReceived(byte[] data) {
        if (dataConnectionListener != null)
            dataConnectionListener.onDataConnectionReceived(data);
    }

    @Override
    public void onDataConnectionError(String message) {
        if (dataConnectionListener != null)
            dataConnectionListener.onDataConnectionError(message);
    }*/

    /*public void onDataReceived(final byte[] data) {
        if (data == null)
            return;
        if (inputStream == null)
            Log.d(TAG,"onDataReceived("+data.length+"b) ignored as inputStream not ready");
        else
            inputStream.write(data);
    }*/

    public SerialConnection getSerialConnection() { return serialConnection; }


    @Override
    public void onConnect() {
        if (dataConnectionListener != null)
            dataConnectionListener.onConnect();
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

    public abstract void setPeripheralStatusListener(PeripheralStatusListener listener);
}
