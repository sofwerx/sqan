package org.sofwerx.notdroid.hardware.usb;

import org.sofwerx.notdroid.app.PendingIntent;

import java.util.ArrayList;

import static org.sofwerx.sqandr.Config.isAndroid;

public class UsbDevice {
    private Object androidUsbDevice;

    private int deviceId = -1;
    private String deviceName = null;
    private int ifaceCount = 2;
    private ArrayList<UsbInterface> ifaces = new ArrayList<>();

    public UsbDevice() {
        this.androidUsbDevice = null;
    }

    public UsbDevice (android.hardware.usb.UsbDeviceConnection dev) {
        this.androidUsbDevice = dev;
    }

    public android.hardware.usb.UsbDevice toAndroid() {
        return (android.hardware.usb.UsbDevice) this.androidUsbDevice;
    }



    public String getDeviceName() {
        if (isAndroid()) {
            return this.toAndroid().getDeviceName();
        } else {
            return this.deviceName;
        }
    }

    public int getDeviceId() {
        if (isAndroid()) {
            return this.toAndroid().getDeviceId();
        } else {
            return this.deviceId;
        }
    }

    public int getInterfaceCount() {
        // TODO
        return this.ifaceCount;
    }


    public UsbInterface getInterface(int i) {
        if (i < this.getInterfaceCount()) {
            return ifaces.get(i);
        } else {
            return null;
        }
    }
}
