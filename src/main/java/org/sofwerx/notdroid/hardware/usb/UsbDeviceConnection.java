package org.sofwerx.notdroid.hardware.usb;

import org.sofwerx.notdroid.app.PendingIntent;

import static org.sofwerx.sqandr.Config.isAndroid;


public class UsbDeviceConnection {
    private Object androidUsbDeviceConnection;

    private String serialNum = null;
    private Object devConn = null;

    public UsbDeviceConnection() {
        this.androidUsbDeviceConnection = null;
    }

    public UsbDeviceConnection (android.hardware.usb.UsbDeviceConnection devconn) {
        this.androidUsbDeviceConnection = devconn;
    }

    public android.hardware.usb.UsbDeviceConnection toAndroid() {
        return (android.hardware.usb.UsbDeviceConnection) this.androidUsbDeviceConnection;
    }


    public String getSerial () {
        if (isAndroid()) {
            return this.toAndroid().getSerial();
        } else {
            return this.serialNum;
        }
    }

    public void close() {
        // TODO: actually close the connection

        this.devConn = null;

        return;
    }

    public boolean claimInterface(UsbInterface mControlInterface, boolean b) {
        // TODO
        return true;
    }

    public int controlTransfer(int usbRtAcm, int request, int value, int mControlIndex, byte[] buf, int i, int i1) {
        // TODO
        return 0;
    }

    public int bulkTransfer(UsbEndpoint mReadEndpoint, byte[] dest, int length, int timeoutMillis) {
        // TODO
        return 0;
    }
}

