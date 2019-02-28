package org.sofwerx.sqan.manet.bt.helper;

import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

import java.io.IOException;

/**
 * Interface for BTSocket read listeners 
 */
public interface ReadListener {
	void onSuccess(AbstractPacket packet);
	void onError(int totalNumBytes, IOException e);

}
