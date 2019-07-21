package org.sofwerx.sqantest.tests;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import org.sofwerx.sqantest.IpcBroadcastTransceiver;
import org.sofwerx.sqantest.SqAnTestService;
import org.sofwerx.sqantest.tests.support.TestException;
import org.sofwerx.sqantest.tests.support.TestPacket;
import org.sofwerx.sqantest.tests.support.TestPacketListener;
import org.sofwerx.sqantest.tests.support.TestProgress;
import org.sofwerx.sqantest.util.FileUtil;
import org.sofwerx.sqantest.util.StringUtil;

import java.io.File;
import java.io.StringWriter;

public abstract class AbstractTest implements TestPacketListener {
    private final static String TAG = "Test";
    private TestProgress testProgress;
    private boolean isRunning = false;
    public boolean isRunning() { return isRunning; }
    protected HandlerThread thread;
    protected Handler handler;
    protected final SqAnTestService service;

    public final static byte COMMAND_STOP_TEST = 0b00000000;
    public final static byte COMMAND_SIMPLE_TEST = 0b00000001;

    public AbstractTest(SqAnTestService service) {
        this.service = service;
    }

    public static AbstractTest newFromCommand(SqAnTestService service, byte command) {
        switch (command) {
            case COMMAND_SIMPLE_TEST:
                return new SimpleTest(service);
            default:
                return null;
        }
    }

    public void burst(TestPacket packet) {
        if (packet == null)
            return;
        post(() -> {
            IpcBroadcastTransceiver.broadcastTest(service,packet);
            if ((testProgress != null) && (packet.getData() != null))
                testProgress.addTxBytes(packet.getData().length);
        });
    }

    public abstract byte getCommandType();

    private Runnable periodicHelper = new Runnable() {
        @Override
        public void run() {
            executePeriodicTasks();
            if (handler == null) {
                Log.e(TAG,"Unable to start "+getName()+" periodic helper as the handler is not yet assigned");
                return;
            }
            handler.postDelayed(periodicHelper,getPeriodicInterval());
        }
    };

    protected abstract void executePeriodicTasks();

    protected abstract long getPeriodicInterval();

    protected void post(Runnable runnable) {
        if (handler == null) {
            Log.w(TAG,"Handler not yet ready so executing runnable on calling thread");
            runnable.run();
        } else
            handler.post(runnable);
    }

    /**
     * Stops this test
     */
    public void stop() {
        isRunning = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        if (service != null) {
            String filename;
            if (testProgress == null)
                filename = "Report";
            else
                filename = "Report "+getName()+" "+StringUtil.getFilesafeTime(testProgress.getStartTime())+".txt";
            filename = filename.replace(' ','_');
            File file = FileUtil.save(service, filename, getFullReport());
            service.notifyOfReport(this, file);
        }
    }

    /**
     * Starts this test
     */
    public void start() {
        if (isRunning != true) {
            testProgress = new TestProgress();
            isRunning = true;
            thread = new HandlerThread("TestThread") {
                @Override
                protected void onLooperPrepared() {
                    handler = new Handler(thread.getLooper());
                    handler.postDelayed(periodicHelper,getPeriodicInterval());
                }
            };
            thread.start();
            if (service != null)
                service.notifyOfTest(this);
        }
    }

    /**
     * Gets the name of this test
     * @return
     */
    public abstract String getName();

    /**
     * Gets a description for this test
     * @return
     */
    public abstract String getDescription();

    public TestProgress getProgress() { return testProgress; }

    public void onTestPacketReceived(TestPacket packet) {
        synchronized (testProgress) {
            testProgress.add(packet);
        }
    }

    public void onException(TestException exception) {
        synchronized (testProgress) {
            testProgress.add(exception);
        }
    }

    public void onOtherDataReceived(int origin, int size) {
        synchronized (testProgress) {
            testProgress.add(origin,size);
        }
    }

    public String getFullReport() {
        StringWriter out = new StringWriter();
        out.append(getName());
        if (isRunning)
            out.append(" (in progress)");
        out.append("\r\n");
        out.append(getDescription());
        out.append("\r\n");

        if (testProgress == null)
            out.append("No data to report");
        else
            out.append(testProgress.getFullStatus());

        String result = out.toString();
        return result;
    }
}
