package org.sofwerx.notdroid.hardware.usb;

import android.hardware.usb.UsbConstants;

import java.util.ArrayList;

public class UsbInterface {

    private Object androidUsbInterface;

    // TODO: set these for real
    private int endptCount = 3;
    private ArrayList<UsbEndpoint> endpts = new ArrayList<>();
    private int ifaceClass = UsbConstants.USB_CLASS_COMM;


    public UsbInterface() {
        this.androidUsbInterface = null;
    }

    public UsbInterface(android.hardware.usb.UsbInterface iface) {
        this.androidUsbInterface = iface;
    }

    public android.hardware.usb.UsbInterface toAndroid() {
        return (android.hardware.usb.UsbInterface) this.androidUsbInterface;
    }

    public int getEndpointCount() {
        // TODO
        return this.endptCount;
    }

    public UsbEndpoint getEndpoint(int i) {
        if (i < this.endptCount) {
            return this.endpts.get(i);
        } else {
            return null;
        }
    }

    public int getInterfaceClass() {
        return this.ifaceClass;
    }
}
