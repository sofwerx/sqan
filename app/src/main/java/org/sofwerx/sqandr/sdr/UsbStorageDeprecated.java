package org.sofwerx.sqandr.sdr;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.util.Log;

import org.sofwerx.sqan.Config;

@Deprecated
public class UsbStorageDeprecated {
    private final static String TAG = Config.TAG+".store";
    private UsbInterface usbInterface;

    public static boolean isStorageInterface(UsbInterface iface) {
        if (iface == null)
            return false;
        if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) {
            if (iface.getEndpointCount() == 2) {
                UsbEndpoint endpoint;
                for (int i=0;i<2;i++) {
                    endpoint = iface.getEndpoint(i);
                    if ((endpoint.getDirection() == UsbConstants.USB_DIR_OUT) && (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK))
                        return true;
                }
                Log.w(TAG,iface.getName()+" appears to be Mass Storage, but does not have an endpoint with direction USB_DIR_OUT and type USB_ENDPOINT_XFER_BULK");
            } else
                Log.w(TAG,iface.getName()+" appears to be Mass Storage, but does not have 2 endpoints");
        }
        return false;
    }

    public UsbStorageDeprecated(UsbInterface usbInterface) {
        this.usbInterface = usbInterface;
    }
}
