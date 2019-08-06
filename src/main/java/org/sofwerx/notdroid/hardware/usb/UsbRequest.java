package org.sofwerx.notdroid.hardware.usb;

public class UsbRequest {

    private Object androidUsbRequest;

    public UsbRequest() {
        this.androidUsbRequest = null;
    }

    public UsbRequest(android.hardware.usb.UsbInterface iface) {
        this.androidUsbRequest = iface;
    }

    public android.hardware.usb.UsbRequest toAndroid() {
        return (android.hardware.usb.UsbRequest) this.androidUsbRequest;
    }
}
