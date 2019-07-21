package org.sofwerx.sqantest.tests;

import android.util.Log;

import org.sofwerx.sqantest.SqAnTestService;
import org.sofwerx.sqantest.tests.support.TestException;
import org.sofwerx.sqantest.tests.support.TestPacket;
import org.sofwerx.sqantest.tests.support.TestProgress;

/**
 * A simple test to test the testing mechanism
 */
public class SimpleTest extends AbstractTest {
    private final static String TAG = "SimpleTest";
    private final static long PERIODIC_INTERVAL = 1000l * 5l;

    public SimpleTest(SqAnTestService service) {
        super(service);
    }

    @Override
    public byte getCommandType() {
        return AbstractTest.COMMAND_SIMPLE_TEST;
    }

    @Override
    protected void executePeriodicTasks() {
        //temp
        if (service != null) {
            burst(new TestPacket(service.getDeviceId(),new byte[16]));
        } else
            Log.e(TAG,"Unable to send a test packet as the service is null");
        //TODO
    }

    @Override
    protected long getPeriodicInterval() {
        return PERIODIC_INTERVAL;
    }

    @Override
    public void stop() {
        //ignore
        super.stop();
    }

    @Override
    public void start() {
        super.start();
        //ignore
    }

    @Override
    public String getName() {
        return "Simple Test";
    }

    @Override
    public String getDescription() {
        return "A simple test that provides a low rate connectivity. Mostly used to trouble shoot the actual testing process.";
    }

    @Override
    public void onTestPacketReceived(TestPacket packet) {
        super.onTestPacketReceived(packet);
        //TODO any this-test specific stuff
    }
}
