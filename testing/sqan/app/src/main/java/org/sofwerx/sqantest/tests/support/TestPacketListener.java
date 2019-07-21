package org.sofwerx.sqantest.tests.support;

public interface TestPacketListener {
    void onTestPacketReceived(TestPacket packet);
    void onException(TestException exception);
    void onOtherDataReceived(int origin, int size);
}
