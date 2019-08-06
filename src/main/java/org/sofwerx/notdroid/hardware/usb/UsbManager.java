package org.sofwerx.notdroid.hardware.usb;

public class UsbManager {
    private Object androidUsbManager;

    private UsbDeviceConnection usbDevConn = null;

    public UsbManager() {
        this.androidUsbManager = null;
    }

    public UsbManager(android.hardware.usb.UsbManager usbmgr) {
        this.androidUsbManager = usbmgr;
    }

    public android.hardware.usb.UsbManager toAndroid() {
        return (android.hardware.usb.UsbManager) this.androidUsbManager;
    }

    public UsbDeviceConnection openDevice(UsbDevice device) {

        // TODO: actually open device connection via jssc
        return this.usbDevConn;
    }
}
