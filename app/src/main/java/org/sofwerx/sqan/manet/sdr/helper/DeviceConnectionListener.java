package org.sofwerx.sqan.manet.sdr.helper;

/**
 * Listens and handles device connection events 
 */
public interface DeviceConnectionListener {
	void onConnectSuccess();
	void onConnectionError(Exception exception, String where);
}