package org.sofwerx.sqandr.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * A stream that serves as a buffer to connect data being written into an
 * InputStream being consumed by something dealing with that data
 */
public class WriteableInputStream extends InputStream {
    private final static int BUFFER_SIZE = 1024*1024*10; //this is an arbitrary picked size

    private byte[] buffer = new byte[BUFFER_SIZE];
    private int readIndex = 0;
    private int writeIndex = 0;
    private Object blockingSync = new Object();
    private int backlogSize = 0;

    @Override
    public int read() throws IOException {
        blockTillData();
        synchronized (buffer) {
            return readNext();
        }
    }

    public int getReadPosition() { return readIndex; }
    public void rewindReadPosition(int position) { readIndex = position; }

    public void blockTillData() {
        while (!hasData()) {
            synchronized (blockingSync) {
                try {
                    blockingSync.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private byte readNext() {
        if (readIndex >= BUFFER_SIZE)
            readIndex = 0;
        byte out = buffer[readIndex];
        readIndex++;
        backlogSize--;
        return out;
    }

    @Override
    public int read(byte[] out) throws IOException {
        if ((out == null) || (out.length == 0))
            return 0;
        return read(out,0,out.length);
    }

    /**
     * reads from the writeable input stream with the option to block until the full data is read
     * @param out
     * @param blockTillFull true == will block until all of the data is read
     * @return
     * @throws IOException
     */
    public int read(byte[] out, boolean blockTillFull) throws IOException {
        if ((out == null) || (out.length == 0))
            return 0;
        if (blockTillFull) {
            int bytesRead = 0;
            final int maxRead = out.length;
            while (bytesRead < maxRead) {
                blockTillData();
                synchronized (blockingSync) {
                    out[bytesRead] = readNext();
                }
                bytesRead++;
            }
            return bytesRead;

        } else
            return read(out,0,out.length);
    }

    public int read(long position, byte[] out, int offset, int length)  throws IOException {
        if ((position <0l) || (position > BUFFER_SIZE))
            throw new IOException("RoverStream asked to read from an illegal position: "+Long.toString(position));
        if (readIndex != (int)position) {
            readIndex = (int) position;
            if (readIndex > writeIndex)
                readIndex = writeIndex;
            backlogSize = writeIndex - readIndex;
        }
        return read(out, offset, length);
    }

    @Override
    public int read(byte[] out, int offset, int length)  throws IOException {
        if ((out == null) || (out.length == 0) || (length <1) || (offset < 0) || (offset > length) || (length > out.length))
            throw new IOException("RoverStream.read("+((out==null)?"out==null":"out["+out.length+"]")+","+offset+","+length+") failed because a parameter was invalid");
        if (readIndex >= writeIndex) {
            blockTillData();
            //return 0;
        }
        //blockTillData();
        int bytesRead = 0;
        int maxRead = Math.min(out.length,length);
        synchronized (buffer) {
            while (hasData() && (offset+bytesRead < maxRead)) {
                out[offset+bytesRead] = readNext();
                bytesRead++;
            }
        }
        return bytesRead;
    }

    private boolean hasData() {
        return readIndex != writeIndex;
    }

    /**
     * Is this buffer full of data from writing but the reading operations are not
     * keeping up and the writing is overwriting the unread data
     * @return true == is overflowing
     */
    public boolean isOverflowing() {
        return backlogSize >= BUFFER_SIZE - 2;
    }

    private int writtenBytes = 0;

    public boolean write(byte[] data) {
        if (data == null)
            return false;
        return write(data,data.length);
    }

    /**
     * Writes data to this buffer
     * @param data
     * @param length the length of data to write
     * @return true == the buffer is overflowing (i.e. the read operations are not keeping up, so write data is writing over unread data)
     */
    public boolean write(byte[] data, int length) {
        synchronized (buffer) {
            if (data != null) {
                if (length > data.length)
                    length = data.length;
                for (int i=0;i<length;i++) {
                    if (writeIndex >= BUFFER_SIZE)
                        writeIndex = 0;
                    buffer[writeIndex] = data[i];
                    writeIndex++;
                    writtenBytes++;
                }
                backlogSize += length;

                synchronized (blockingSync) {
                    blockingSync.notify();
                }
            }
            if (isOverflowing()) {
                readIndex = writeIndex -1;
                if (readIndex < 0)
                    readIndex = BUFFER_SIZE - 1;
            }
        }
        return isOverflowing();
    }

    public int getBacklog() {
        synchronized (buffer) {
            return backlogSize;
        }
    }

    public void clear() {
        readIndex = 0;
        writeIndex = 0;
        backlogSize = 0;
    }
}