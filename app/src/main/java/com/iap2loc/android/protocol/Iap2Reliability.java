package com.iap2loc.android.protocol;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Sequence windows, ACK recovery, reordering and retransmission; called under the link lock. */
final class Iap2Reliability {
    private static final int CONTROL_ACK = 0x40;
    private static final int CONTROL_RETRANSMIT = 0x20;
    private final List<Pending> pending = new ArrayList<>();
    private final ArrayDeque<Queued> queue = new ArrayDeque<>();
    private final Map<Integer, Queued> outOfOrder = new HashMap<>();
    private final Consumer<Iap2Packet.Fields> output;
    private final Iap2CsmReceiver messages;
    private final Consumer<String> fail;
    byte sequence;
    byte acknowledge;
    int maxOutgoing = 4;
    int maxLength = 4000;
    int timeoutMs = 500;
    int maxRetries = 4;
    boolean ready;

    Iap2Reliability(Consumer<Iap2Packet.Fields> output, Iap2CsmReceiver messages,
                    Consumer<String> fail) {
        this.output = output;
        this.messages = messages;
        this.fail = fail;
    }

    void enqueue(byte[] data, int session) {
        int chunkSize = Math.max(64, maxLength - 10);
        for (int offset = 0; offset < data.length; offset += chunkSize) {
            int size = Math.min(chunkSize, data.length - offset);
            queue.addLast(new Queued((byte) session, Arrays.copyOfRange(data, offset, offset + size)));
        }
        flush();
    }

    void retransmit(byte[] missingSequences) {
        for (byte missing : missingSequences) {
            Pending entry = findPending(missing & 0xFF);
            if (entry != null) writePacket(CONTROL_ACK, entry, entry.sequence);
        }
    }

    void tick(long now) {
        for (Pending entry : new ArrayList<>(pending)) {
            if (now - entry.sentAt < timeoutMs) continue;
            if (++entry.retries > maxRetries) {
                fail.accept("iAP2 acknowledge timed out");
                return;
            }
            entry.sentAt = now;
            writePacket(CONTROL_ACK, entry, entry.sequence);
        }
    }

    void sendAck() { writePacket(CONTROL_ACK, new Queued((byte) 0, null), sequence); }

    private void writePacket(int control, Queued frame, byte seq) {
        Iap2Packet.Numbers numbers = new Iap2Packet.Numbers(seq, acknowledge);
        Iap2Packet.Header header = new Iap2Packet.Header(control, frame.session, numbers);
        output.accept(new Iap2Packet.Fields(header, frame.data));
    }

    private static long ticks() { return System.nanoTime() / 1_000_000L; }

    void acceptSequence(byte sequence, byte session, byte[] payload) {
        int distance = (sequence - acknowledge) & 0xFF;
        if (distance == 1) {
            acknowledge = sequence;
            messages.accept(session & 0xFF, payload);
            while (true) {
                int expected = (acknowledge + 1) & 0xFF;
                Queued next = outOfOrder.remove(expected);
                if (next == null) break;
                acknowledge = (byte) expected;
                messages.accept(next.session & 0xFF, next.data);
            }
            sendAck();
        } else if (distance == 0 || distance >= 128) {
            // A lost ACK makes the peer retransmit an already delivered sequence.
            sendAck();
        } else if (distance > 1 && distance < 128 && outOfOrder.size() < 64) {
            outOfOrder.put(sequence & 0xFF, new Queued(session, payload));
            List<Byte> missing = new ArrayList<>();
            for (int expected = (acknowledge + 1) & 0xFF; expected != (sequence & 0xFF); expected = (expected + 1) & 0xFF) {
                missing.add((byte) expected);
            }
            byte[] bytes = new byte[missing.size()];
            for (int i = 0; i < bytes.length; i++) bytes[i] = missing.get(i);
            writePacket(CONTROL_RETRANSMIT, new Queued((byte) 0, bytes), this.sequence);
        }
    }

    private Pending findPending(int sequence) {
        for (Pending entry : pending) {
            if ((entry.sequence & 0xFF) == sequence) return entry;
        }
        return null;
    }

    void completePending(byte acknowledge) {
        Iterator<Pending> iterator = pending.iterator();
        while (iterator.hasNext()) {
            Pending entry = iterator.next();
            if (((acknowledge - entry.sequence) & 0xFF) < 128) iterator.remove();
        }
    }

    void flush() {
        if (!ready) return;
        while (ready && !queue.isEmpty() && pending.size() < maxOutgoing) {
            Queued item = queue.removeFirst();
            sequence = (byte) ((sequence + 1) & 0xFF);
            writePacket(CONTROL_ACK, item, sequence);
            pending.add(new Pending(sequence, item, ticks()));
        }
    }

    private static final class Pending extends Queued {
        final byte sequence;
        long sentAt;
        int retries;
        Pending(byte sequence, Queued frame, long sentAt) {
            super(frame.session, frame.data);
            this.sequence = sequence;
            this.sentAt = sentAt;
        }
    }

    private static class Queued {
        final byte session;
        final byte[] data;

        Queued(byte session, byte[] data) {
            this.session = session;
            this.data = data;
        }
    }
}
