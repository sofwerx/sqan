package org.sofwerx.notdroid.hardware.usb;

import org.sofwerx.notdroid.hardware.usb.UsbConstants;
import android.hardware.usb.UsbConstants;

public class UsbEndpoint {

    private Object androidUsbEndpoint;

    // TODO: set these for real
    private int dir = UsbConstants.USB_DIR_IN;
    private int type = UsbConstants.USB_ENDPOINT_XFER_CONTROL;

    public UsbEndpoint() {
        this.androidUsbEndpoint = null;
    }

    public UsbEndpoint(android.hardware.usb.UsbInterface iface) {
        this.androidUsbEndpoint = iface;
    }

    public android.hardware.usb.UsbEndpoint toAndroid() {
        return (android.hardware.usb.UsbEndpoint) this.androidUsbEndpoint;
    }

    public int getDirection() {
        return this.dir;
    }

    public int getType() {
        return this.type;
    }
}
