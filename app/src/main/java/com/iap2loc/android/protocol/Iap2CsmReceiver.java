package com.iap2loc.android.protocol;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Consumer;

/** Reassembles CSM messages that span multiple control-session payloads. */
final class Iap2CsmReceiver {
    private static final int CSM_HEADER_SIZE = 6;
    private final Iap2Link.Listener listener;
    private final Consumer<String> fail;
    private byte[] buffer = new byte[64 * 1024];
    private int size;
    private volatile int count;
    private boolean failed;

    Iap2CsmReceiver(Iap2Link.Listener listener, Consumer<String> fail) {
        this.listener = listener;
        this.fail = fail;
    }

    int count() { return count; }

    void accept(int session, byte[] payload) {
        if (session != Iap2Link.SESSION_CONTROL || failed) return;
        if (size + payload.length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + payload.length));
        }
        System.arraycopy(payload, 0, buffer, size, payload.length);
        size += payload.length;
        while (!failed && size >= CSM_HEADER_SIZE) {
            int length = messageLength();
            if (length < CSM_HEADER_SIZE || size < length) return;
            if (!deliver(length)) return;
        }
    }

    private int messageLength() {
        if ((buffer[0] & 0xFF) != 0x40 || (buffer[1] & 0xFF) != 0x40) {
            failed = true;
            fail.accept("control session lost the 0x4040 marker");
            return 0;
        }
        int length = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        if (length < CSM_HEADER_SIZE) {
            failed = true;
            fail.accept("invalid control message length " + length);
        }
        return length;
    }

    private boolean deliver(int length) {
        int messageId = ((buffer[4] & 0xFF) << 8) | (buffer[5] & 0xFF);
        Map<Integer, byte[]> parameters;
        try {
            parameters = Csm.parseParams(buffer, CSM_HEADER_SIZE, length - CSM_HEADER_SIZE);
        } catch (Exception ex) {
            failed = true;
            fail.accept("invalid control parameters: " + ex.getMessage());
            return false;
        }
        System.arraycopy(buffer, length, buffer, 0, size - length);
        size -= length;
        count++;
        if (listener != null) listener.onCsm(messageId, parameters);
        return true;
    }
}
