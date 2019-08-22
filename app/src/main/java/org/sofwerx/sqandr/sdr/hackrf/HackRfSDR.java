package org.sofwerx.sqandr.sdr.hackrf;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;

import org.sofwerx.sqan.listeners.PeripheralStatusListener;
import org.sofwerx.sqandr.sdr.AbstractSdr;
import org.sofwerx.sqandr.sdr.SdrException;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Adapted from:
 *
 * <h1>HackRF USB Library for Android</h1>
 *
 * Module:      Hackrf.java
 * Description: The Hackrf class represents the HackRF device and
 *              acts as abstraction layer that manages the USB
 *              communication between the device and the application.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * based on code of libhackrf [https://github.com/mossmann/hackrf/tree/master/host/libhackrf]:
 *     Copyright (c) 2012, Jared Boone <jared@sharebrained.com>
 *     Copyright (c) 2013, Benjamin Vernoux <titanmkd@gmail.com>
 *     Copyright (c) 2013, Michael Ossmann <mike@ossmann.com>
 *     All rights reserved.
 *     Redistribution and use in source and binary forms, with or without modification,
 *     are permitted provided that the following conditions are met:
 *     - Redistributions of source code must retain the above copyright notice, this list
 *       of conditions and the following disclaimer.
 *     - Redistributions in binary form must reproduce the above copyright notice, this
 *       list of conditions and the following disclaimer in the documentation and/or other
 *       materials provided with the distribution.
 *     - Neither the name of Great Scott Gadgets nor the names of its contributors may be
 *       used to endorse or promote products derived from this software without specific
 *       prior written permission.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

public class HackRfSDR extends AbstractSdr {
    public final static int VENDOR_ID = 7504;

    private int transceiverMode = HACKRF_TRANSCEIVER_MODE_OFF;	// current mode of the HackRF

    public static final int HACKRF_TRANSCEIVER_MODE_OFF 		= 0;
    public static final int HACKRF_TRANSCEIVER_MODE_RECEIVE 	= 1;
    public static final int HACKRF_TRANSCEIVER_MODE_TRANSMIT 	= 2;

    // USB Vendor Requests (from hackrf.c)
    private static final int HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE = 1;
    private static final int HACKRF_VENDOR_REQUEST_MAX2837_WRITE = 2;
    private static final int HACKRF_VENDOR_REQUEST_MAX2837_READ = 3;
    private static final int HACKRF_VENDOR_REQUEST_SI5351C_WRITE = 4;
    private static final int HACKRF_VENDOR_REQUEST_SI5351C_READ = 5;
    private static final int HACKRF_VENDOR_REQUEST_SAMPLE_RATE_SET = 6;
    private static final int HACKRF_VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET = 7;
    private static final int HACKRF_VENDOR_REQUEST_RFFC5071_WRITE = 8;
    private static final int HACKRF_VENDOR_REQUEST_RFFC5071_READ = 9;
    private static final int HACKRF_VENDOR_REQUEST_SPIFLASH_ERASE = 10;
    private static final int HACKRF_VENDOR_REQUEST_SPIFLASH_WRITE = 11;
    private static final int HACKRF_VENDOR_REQUEST_SPIFLASH_READ = 12;
    private static final int HACKRF_VENDOR_REQUEST_BOARD_ID_READ = 14;
    private static final int HACKRF_VENDOR_REQUEST_VERSION_STRING_READ = 15;
    private static final int HACKRF_VENDOR_REQUEST_SET_FREQ = 16;
    private static final int HACKRF_VENDOR_REQUEST_AMP_ENABLE = 17;
    private static final int HACKRF_VENDOR_REQUEST_BOARD_PARTID_SERIALNO_READ = 18;
    private static final int HACKRF_VENDOR_REQUEST_SET_LNA_GAIN = 19;
    private static final int HACKRF_VENDOR_REQUEST_SET_VGA_GAIN = 20;
    private static final int HACKRF_VENDOR_REQUEST_SET_TXVGA_GAIN = 21;
    private static final int HACKRF_VENDOR_REQUEST_ANTENNA_ENABLE = 23;
    private static final int HACKRF_VENDOR_REQUEST_SET_FREQ_EXPLICIT = 24;

    // RF Filter Paths (from hackrf.c)
    public static final int RF_PATH_FILTER_BYPASS 		= 0;
    public static final int RF_PATH_FILTER_LOW_PASS 	= 1;
    public static final int RF_PATH_FILTER_HIGH_PASS 	= 2;

    @Override
    public void setUsbDevice(Context context, UsbManager usbManager, UsbDevice usbDevice) throws SdrException {
        super.setUsbDevice(context,usbManager,usbDevice);
        if (usbDevice != null)
            setCommLinkUsbInterface(usbDevice.getInterface(0));
    }

    @Override
    protected String getTerminalUsername() { return null; }

    @Override
    protected String getTerminalPassword() { return null; }

    @Override
    protected boolean useSerialConnection() { return false; }

    @Override
    public boolean useMassStorage() { return false; }

    @Override
    public void setPeripheralStatusListener(PeripheralStatusListener listener) {
        //TODO
    }

    @Override
    public void onHighNoise(float snr) {
        //ignore
    }
}
