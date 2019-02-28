package org.sofwerx.sqan.manet.bt.helper;

import java.io.IOException;

/**
 * Interface for BTSocket write listeners 
 */
public interface WriteListener {
	void onSuccess();
	void onError(IOException e);

}
