package org.sofwerx.sqan.manet.sdr.helper;

/**
 * Listens and handles new accept()ed SDR devices
 */
public interface AcceptListener {
	void onNewConnectionAccepted();
	void onError(Exception e, String where);
}
