package org.sofwerx.sqandr.serial;

import jssc.SerialPort;

import org.sofwerx.pisqan.Config;
import org.sofwerx.pisqan.Log;
import org.sofwerx.sqandr.serial.SerialConnection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;

// Based on:

        /* Copyright 2011-2013 Google Inc.
         * Copyright 2013 mike wakerly <opensource@hoho.com>
         *
         * This library is free software; you can redistribute it and/or
         * modify it under the terms of the GNU Lesser General Public
         * License as published by the Free Software Foundation; either
         * version 2.1 of the License, or (at your option) any later version.
         *
         * This library is distributed in the hope that it will be useful,
         * but WITHOUT ANY WARRANTY; without even the implied warranty of
         * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
         * Lesser General Public License for more details.
         *
         * You should have received a copy of the GNU Lesser General Public
         * License along with this library; if not, write to the Free Software
         * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301,
         * USA.
         *
         * Project home page: https://github.com/mik3y/usb-serial-for-android
         */

/**
 * Utility class which services a {@link SerialPort} in its {@link #run()}
 * method.
 *
 * @author mike wakerly (opensource@hoho.com)
 */
public class SerialInputOutputManager implements Runnable {

    private static final String TAG = Config.TAG + ".serialIOmgr";

    private static final boolean DEBUG = true;

    private static final int READ_WAIT_MILLIS = 200;
    private static final int BUFSIZ = 4096;

    private final SerialPort mDriver;

    private final ByteBuffer mReadBuffer = ByteBuffer.allocate(BUFSIZ);

    // Synchronized by 'mWriteBuffer'
    private final ByteBuffer mWriteBuffer = ByteBuffer.allocate(BUFSIZ);

    private enum State {
        STOPPED,
        RUNNING,
        STOPPING
    }

    // Synchronized by 'this'
    private State mState = State.STOPPED;

    // Synchronized by 'this'
    private Listener mListener;

    public interface Listener {
        /**
         * Called when new incoming data is available.
         */
        public void onNewData(byte[] data);

        /**
         * Called when {@link SerialInputOutputManager#run()} aborts due to an
         * error.
         */
        public void onRunError(Exception e);
    }

    /**
     * Creates a new instance with no listener.
     */
    public SerialInputOutputManager(SerialPort driver) {
        this(driver, null);
    }

    /**
     * Creates a new instance with the provided listener.
     */
    public SerialInputOutputManager(SerialPort driver, Listener listener) {
        mDriver = driver;
        mListener = listener;
    }

    public synchronized void setListener(Listener listener) {
        mListener = listener;
    }

    public synchronized Listener getListener() {
        return mListener;
    }

    public void writeAsync(byte[] data) {
        synchronized (mWriteBuffer) {
            mWriteBuffer.put(data);
        }
    }

    public synchronized void stop() {
        if (getState() == State.RUNNING) {
            Log.i(TAG, "Stop requested");
            mState = State.STOPPING;
        }
    }

    private synchronized State getState() {
        return mState;
    }

    /**
     * Continuously services the read and write buffers until {@link #stop()} is
     * called, or until a driver exception is raised.
     *
     * NOTE(mikey): Uses inefficient read/write-with-timeout.
     * TODO(mikey): Read asynchronously with UsbRequest#queue(ByteBuffer, int)
     */
    @Override
    public void run() {
        synchronized (this) {
            if (getState() != State.STOPPED) {
                throw new IllegalStateException("Already running.");
            }
            mState = State.RUNNING;
        }

        Log.i(TAG, SerialInputOutputManager.class.getSimpleName() + " running ..");
        try {
            while (true) {
                if (getState() != State.RUNNING) {
                    Log.i(TAG, "Stopping mState=" + getState());
                    break;
                }
                step();
            }
        } catch (Exception e) {
            Log.w(TAG, "Run ending due to exception: " + e.getMessage());
            final Listener listener = getListener();
            if (listener != null) {
                listener.onRunError(e);
            }
        } finally {
            synchronized (this) {
                mState = State.STOPPED;
                Log.i(TAG, "Stopped.");
            }
        }
    }

    private void step() throws IOException {
        // Handle incoming data.
//        int len = mDriver.read(mReadBuffer.array(), READ_WAIT_MILLIS);
        int len = 0;
        try {
            len = mDriver.getInputBufferBytesCount();
            if (len > 0) {
                mReadBuffer.put(mDriver.readBytes(len, READ_WAIT_MILLIS), mReadBuffer.position(), len);
                Log.v(TAG, "Read data len=" + len);
            }
        }
        catch (Exception e) {
            len = -1;
        }

        if (len > 0) {
            final Listener listener = getListener();
            if (listener != null) {
                byte[] data = new byte[len];
                mReadBuffer.rewind();   // REQUIRED to read the data we just got
                mReadBuffer.get(data);
                Log.v(TAG, "Calling onNewData with " + len + "b");
                listener.onNewData(data);
                mReadBuffer.clear();
            }
        }

        // Handle outgoing data.
        byte[] outBuff = null;
        synchronized (mWriteBuffer) {
            len = mWriteBuffer.position();
            if (len > 0) {
                outBuff = new byte[len];
                mWriteBuffer.rewind();
                mWriteBuffer.get(outBuff, 0, len);
                mWriteBuffer.clear();
            }
        }
        if (outBuff != null) {
                Log.d(TAG, "Writing data len=" + len);
//            mDriver.write(outBuff, READ_WAIT_MILLIS);
            try {
                mDriver.writeBytes(outBuff);
            }
            catch (Exception e) {
                Log.w(TAG, e.getMessage());
            }
        }
    }

}
