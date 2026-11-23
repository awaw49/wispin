package com.iap2loc.android.protocol;

import com.iap2loc.android.transport.ITransport;
import com.iap2loc.android.util.AppLog;

import java.io.IOException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** iAP2 link layer: detect marker, rate negotiation, sequence/ack, retransmit and CSM framing. */
public final class Iap2Link implements AutoCloseable {
    public static final int SESSION_CONTROL = 10;

    private static final int CONTROL_ACK = 0x40;
    private static final int CONTROL_RETRANSMIT = 0x20;
    private static final int CONTROL_SYNC = 0x80;
    private static final int CONTROL_RESET = 0x10;
    private static final byte[] MARKER = {(byte) 0xFF, 0x55, 0x02, 0x00, (byte) 0xEE, 0x10};
    private static final byte[] SYNC_PAYLOAD = {
            1, 4, (byte) 255, (byte) 255, 15, (byte) 160, 1, (byte) 244, 4, 3, 10, 0, 2, 11, 2, 1, 12, 1, 2,
    };

    public enum State { DETECT, NEGOTIATE, NORMAL, DEAD }

    public interface Listener {
        void onReady();
        void onFailed(String reason);
        void onCsm(int messageId, Map<Integer, byte[]> parameters);
    }

    public static final class Options {
        public boolean initiate = true;
        public String role = "accessory";
        public Listener listener;
    }

    private final ITransport transport;
    private final boolean initiate;
    private final String role;
    private final Listener listener;
    private final Object gate = new Object();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean disposed = new AtomicBoolean();
    private final Iap2Reliability reliability;
    private final ScheduledExecutorService timer;

    private final Iap2FrameReader frames;
    private final Iap2CsmReceiver messages;
    private Thread reader;
    private State state = State.DETECT;
    private long startedAt;
    private long lastMarkerAt;
    private long lastSyncAt;
    private volatile long bytesIn;
    private volatile long bytesOut;


    public Iap2Link(ITransport transport, Options options) {
        this.transport = transport;
        this.initiate = options.initiate;
        this.role = options.role;
        this.listener = options.listener;
        this.messages = new Iap2CsmReceiver(options.listener, this::fail);
        this.reliability = new Iap2Reliability(packet -> writeRaw(Iap2Packet.encode(packet)), messages, this::fail);
        this.frames = new Iap2FrameReader(new Iap2FrameReader.Events() {
            @Override public void marker() { markerReceived(); }
            @Override public void packet(byte[] value) { handle(value); }
            @Override public void failed(String reason) { fail(reason); }
        });
        this.timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "iap2-tick-" + role);
            thread.setDaemon(true);
            return thread;
        });
    }

    public State state() {
        synchronized (gate) {
            return state;
        }
    }

    public long bytesIn() { return bytesIn; }
    public long bytesOut() { return bytesOut; }
    public int csmMessages() { return messages.count(); }

    public void start() {
        synchronized (gate) {
            if (disposed.get() || state == State.DEAD) throw new IllegalStateException("iAP2 link is closed");
            if (!started.compareAndSet(false, true)) return;
            startedAt = ticks();
            if (initiate) {
                writeRaw(MARKER);
                lastMarkerAt = startedAt;
            }
            if (disposed.get() || state == State.DEAD) return;
            reader = new Thread(this::readLoop, "iap2-rx-" + role);
            reader.setDaemon(true);
            reader.start();
            timer.scheduleAtFixedRate(this::tick, 100, 100, TimeUnit.MILLISECONDS);
        }
    }

    public void send(int messageId, byte[]... parameters) {
        sendRaw(Csm.encode(messageId, parameters), SESSION_CONTROL);
    }

    public void sendRaw(byte[] data, int session) {
        synchronized (gate) {
            if (state == State.DEAD) throw new IllegalStateException("iAP2 link is closed");
            reliability.enqueue(data, session);
        }
    }

    private void readLoop() {
        byte[] buffer = new byte[8192];
        while (!disposed.get()) {
            int read;
            try {
                read = transport.read(buffer, 0, buffer.length);
            } catch (Exception ex) {
                if (!disposed.get()) fail("transport read failed: " + ex.getMessage());
                return;
            }
            if (read <= 0) {
                if (!disposed.get()) fail("transport closed by the peer");
                return;
            }
            synchronized (gate) {
                bytesIn += read;
                frames.accept(buffer, read);
            }
        }
    }

    private void markerReceived() {
        if (state != State.DETECT) return;
        if (!initiate) writeRaw(MARKER);
        state = State.NEGOTIATE;
        startedAt = ticks();
        sendSync();
    }

    private void handle(byte[] packet) {
        if (!validPacket(packet)) return;
        int payloadLength = packet.length - 10;
        int control = packet[4] & 0xFF;
        int sequence = packet[5] & 0xFF;
        int peerAcknowledge = packet[6] & 0xFF;
        int session = packet[7] & 0xFF;
        byte[] payload = payloadLength > 0
                ? Arrays.copyOfRange(packet, 9, 9 + payloadLength)
                : null;

        if ((control & CONTROL_RESET) != 0) {
            fail("the device reset the iAP2 link");
            return;
        }
        if ((control & CONTROL_SYNC) != 0 && !receiveSync(payload, sequence)) return;
        if ((control & CONTROL_ACK) != 0) {
            reliability.completePending((byte) peerAcknowledge);
            if (state == State.NEGOTIATE) {
                state = State.NORMAL;
                reliability.ready = true;
                if (listener != null) listener.onReady();
            }
            reliability.flush();
        }
        if ((control & CONTROL_RETRANSMIT) != 0 && payload != null) {
            reliability.retransmit(payload);
            return;
        }
        if ((control & ~CONTROL_ACK) == 0 && payload != null) {
            reliability.acceptSequence((byte) sequence, (byte) session, payload);
        }
    }

    private boolean validPacket(byte[] packet) {
        if (!Iap2Packet.checksumIsZero(packet, 0, 9)) {
            return false;
        }
        int payloadLength = packet.length - 10;
        if (payloadLength > 0 && !Iap2Packet.checksumIsZero(packet, 9, payloadLength + 1)) {
            return false;
        }
        return true;
    }

    private boolean receiveSync(byte[] payload, int peerSequence) {
        if (payload == null || payload.length < 10 || payload[0] != 1) {
            fail("invalid iAP2 sync parameters");
            return false;
        }
        reliability.maxOutgoing = Math.max(1, Math.min(64, payload[1] & 0xFF));
        reliability.maxLength = Math.max(64, ((payload[2] & 0xFF) << 8) | (payload[3] & 0xFF));
        reliability.timeoutMs = Math.max(50, ((payload[4] & 0xFF) << 8) | (payload[5] & 0xFF));
        reliability.maxRetries = Math.max(1, payload[8] & 0xFF);
        reliability.acknowledge = (byte) peerSequence;
        reliability.sendAck();
        return true;
    }

    private void sendSync() {
        lastSyncAt = ticks();
        Iap2Packet.Numbers numbers = new Iap2Packet.Numbers(reliability.sequence, reliability.acknowledge);
        Iap2Packet.Header header = new Iap2Packet.Header(CONTROL_SYNC, 0, numbers);
        writeRaw(Iap2Packet.encode(new Iap2Packet.Fields(header, SYNC_PAYLOAD)));
    }

    private void writeRaw(byte[] data) {
        try {
            transport.write(data, 0, data.length);
            bytesOut += data.length;
        } catch (IOException ex) {
            fail("transport write failed: " + ex.getMessage());
        }
    }

    private void tick() {
        synchronized (gate) {
            if (!started.get() || state == State.DEAD || disposed.get()) return;
            long now = ticks();
            switch (state) {
                case DETECT:
                    if (now - startedAt > 15_000) {
                        fail("iAP2 detect timed out");
                        return;
                    }
                    if (initiate && now - lastMarkerAt > 1_000) {
                        writeRaw(MARKER);
                        lastMarkerAt = now;
                    }
                    break;
                case NEGOTIATE:
                    if (now - startedAt > 20_000) {
                        fail("iAP2 negotiation timed out");
                        return;
                    }
                    if (now - lastSyncAt > 500) sendSync();
                    break;
                case NORMAL:
                    reliability.tick(now);
                    break;
                default:
                    break;
            }
        }
    }

    private void fail(String reason) {
        synchronized (gate) {
            if (state == State.DEAD) return;
            state = State.DEAD;
            reliability.ready = false;
        }
        AppLog.error("[" + role + "] " + reason);
        if (listener != null) listener.onFailed(reason);
    }

    private static long ticks() {
        return System.nanoTime() / 1_000_000L;
    }

    @Override
    public void close() {
        if (!disposed.compareAndSet(false, true)) return;
        try {
            transport.close();
        } catch (IOException ex) {
            AppLog.warn("[" + role + "] transport close failed: " + ex.getMessage());
        }
        synchronized (gate) {
            state = State.DEAD;
            reliability.ready = false;
            timer.shutdownNow();
        }
    }

}
