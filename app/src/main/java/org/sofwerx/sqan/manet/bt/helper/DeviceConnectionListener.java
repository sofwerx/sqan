package org.sofwerx.sqan.manet.bt.helper;

/**
 * Listens and handles device connection events 
 */
public interface DeviceConnectionListener {
	void onConnectSuccess(BTSocket clientSocket);
	void onConnectionError(Exception exception, String where);
}