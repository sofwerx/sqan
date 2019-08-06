package org.sofwerx.sqan.manet.sdr.helper;

import org.sofwerx.sqan.manet.common.packet.AbstractPacket;

import java.io.IOException;

public interface ReadListener {
	void onSuccess(AbstractPacket packet);
	void onError(IOException e);

	/**
	 * Used primarily for keeping track of error rates
	 */
	void onPacketDropped();
}
