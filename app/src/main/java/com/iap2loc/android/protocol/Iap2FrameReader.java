package com.iap2loc.android.protocol;

import java.util.Arrays;

/** Incremental stream framing; never assumes one transport read equals one iAP2 packet. */
final class Iap2FrameReader {
    private static final int HEADER_SIZE = 9;
    private static final byte[] MARKER = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};
    interface Events {
        void marker();
        void packet(byte[] value);
        void failed(String reason);
    }
    private final Events events;
    private byte[] buffer = new byte[64 * 1024];
    private int size;
    private boolean detected;
    private boolean failed;

    Iap2FrameReader(Events events) { this.events = events; }

    void accept(byte[] data, int count) {
        if (failed) return;
        if (size + count > buffer.length) buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, size + count));
        System.arraycopy(data, 0, buffer, size, count);
        size += count;
        if (!detected && !detect()) return;
        while (!failed && size >= HEADER_SIZE && readPacket()) { /* consume complete frames */ }
    }

    private boolean detect() {
        if (size < MARKER.length) return false;
        if (!startsWithMarker()) {
            fail("the device did not answer the iAP2 detect marker");
            return false;
        }
        consume(MARKER.length);
        detected = true;
        events.marker();
        return true;
    }

    private boolean readPacket() {
        if (startsWithMarker()) {
            consume(MARKER.length);
            return true;
        }
        if ((buffer[0] & 0xFF) != 0xFF || (buffer[1] & 0xFF) != 0x5A) {
            consume(1);
            return true;
        }
        int length = ((buffer[2] & 0xFF) << 8) | (buffer[3] & 0xFF);
        if (length < HEADER_SIZE) {
            fail("invalid iAP2 packet length " + length);
            return false;
        }
        if (size < length) return false;
        byte[] packet = Arrays.copyOf(buffer, length);
        consume(length);
        events.packet(packet);
        return true;
    }

    private boolean startsWithMarker() {
        if (size < MARKER.length) return false;
        for (int i = 0; i < MARKER.length; i++) if (buffer[i] != MARKER[i]) return false;
        return true;
    }

    private void consume(int count) {
        System.arraycopy(buffer, count, buffer, 0, size - count);
        size -= count;
    }

    private void fail(String reason) {
        failed = true;
        events.failed(reason);
    }
}
