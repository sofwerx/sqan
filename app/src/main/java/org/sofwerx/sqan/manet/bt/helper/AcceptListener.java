package org.sofwerx.sqan.manet.bt.helper;

/**
 * Listens and handles new accept()ed BT devices 
 */
public interface AcceptListener {
	void onNewConnectionAccepted(BTSocket newConnection);
	void onError(Exception e, String where);
}
