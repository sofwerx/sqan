package org.sofwerx.sqan.ipc;

import android.util.Log;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class ChatMessage {
    private final static String TAG = "SqANTest";
    private long time;
    private String message;

    public ChatMessage(long time, String message) {
        this.time = time;
        this.message = message;
    }

    public ChatMessage(String message) {
        this(System.currentTimeMillis(),message);
    }

    public ChatMessage(byte[] data) {
        parse(data);
    }

    public byte[] toBytes() {
        byte[] messageBytes = null;
        if (message != null)
            messageBytes = message.getBytes(StandardCharsets.UTF_8);
        int len = 8 + 4;
        if (messageBytes != null)
            len += messageBytes.length;
        ByteBuffer out = ByteBuffer.allocate(len);
        out.putLong(time);
        if (messageBytes == null)
            out.putInt(0);
        else {
            out.putInt(messageBytes.length);
            out.put(messageBytes);
        }
        return out.array();
    }

    public void parse(byte[] data) {
        if (data == null) {
            Log.w(TAG,"Unable to parse a chat message from a nul byte array");
            return;
        }
        try {
            ByteBuffer in = ByteBuffer.wrap(data);
            time = in.getLong();
            int len = in.getInt();
            if (len > 0) {
                byte[] messageBytes = new byte[len];
                in.get(messageBytes);
                message = new String(messageBytes,StandardCharsets.UTF_8);
            }
        } catch (BufferUnderflowException e) {
            Log.e(TAG,"Unable to parse chat: "+e.getMessage());
        }
    }

    public long getTime() { return time; }
    public String getMessage() { return message; }
}
