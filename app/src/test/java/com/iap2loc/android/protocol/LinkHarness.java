package com.iap2loc.android.protocol;

import com.iap2loc.android.transport.ITransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

/** Real loopback byte streams and two production link instances, with deterministic packet loss. */
final class LinkHarness implements AutoCloseable {
    static final int WAIT_MS = 3000;
    private static final int POLL_MS = 10;
    final Endpoint accessoryEvents = new Endpoint();
    final Endpoint deviceEvents = new Endpoint();
    final SocketTransport accessoryTransport;
    final SocketTransport deviceTransport;
    final Iap2Link accessory;
    final Iap2Link device;
    LocationSession session;

    LinkHarness() throws IOException {
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Socket outgoing = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
            Socket incoming = server.accept();
            outgoing.setTcpNoDelay(true);
            incoming.setTcpNoDelay(true);
            accessoryTransport = new SocketTransport(outgoing);
            deviceTransport = new SocketTransport(incoming);
        }
        Iap2Link.Options accessoryOptions = new Iap2Link.Options();
        accessoryOptions.role = "test-accessory";
        accessoryOptions.listener = accessoryEvents;
        Iap2Link.Options deviceOptions = new Iap2Link.Options();
        deviceOptions.initiate = false;
        deviceOptions.role = "test-device";
        deviceOptions.listener = deviceEvents;
        accessory = new Iap2Link(accessoryTransport, accessoryOptions);
        device = new Iap2Link(deviceTransport, deviceOptions);
    }

    void start() throws InterruptedException {
        device.start();
        accessory.start();
        assertTrue("accessory handshake", accessoryEvents.ready.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertTrue("device handshake", deviceEvents.ready.await(WAIT_MS, TimeUnit.MILLISECONDS));
        assertEquals(Iap2Link.State.NORMAL, accessory.state());
        assertEquals(Iap2Link.State.NORMAL, device.state());
    }

    void attachLocation() {
        LocationSession.Options options = new LocationSession.Options();
        options.intervalMs = 500;
        options.adapter = new byte[]{1, 2, 3, 4, 5, 6};
        session = new LocationSession(accessory, new LocationTarget(31.2304, 121.4737), options);
        accessoryEvents.session = session;
    }

    void assertHealthy() {
        assertTrue(accessoryEvents.failures.toString(), accessoryEvents.failures.isEmpty());
        assertTrue(deviceEvents.failures.toString(), deviceEvents.failures.isEmpty());
        assertEquals(Iap2Link.State.NORMAL, accessory.state());
        assertEquals(Iap2Link.State.NORMAL, device.state());
    }

    static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(POLL_MS);
        assertTrue("condition did not arrive before test deadline", condition.getAsBoolean());
    }

    @Override public void close() {
        if (session != null) session.dispose();
        accessory.close();
        device.close();
    }

    static final class Message {
        final int id;
        final Map<Integer, byte[]> parameters;
        Message(int id, Map<Integer, byte[]> parameters) { this.id = id; this.parameters = parameters; }
    }

    static final class Endpoint implements Iap2Link.Listener {
        final CountDownLatch ready = new CountDownLatch(1);
        final BlockingQueue<Message> messages = new LinkedBlockingQueue<>();
        final BlockingQueue<String> failures = new LinkedBlockingQueue<>();
        volatile LocationSession session;
        @Override public void onReady() { ready.countDown(); }
        @Override public void onFailed(String reason) { failures.add(reason); }
        @Override public void onCsm(int id, Map<Integer, byte[]> params) {
            messages.add(new Message(id, params));
            if (session != null) session.onCsm(id, params);
        }
        Message take(int id) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
            while (System.nanoTime() < deadline) {
                Message message = messages.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (message != null && message.id == id) return message;
            }
            throw new AssertionError("missing CSM message 0x" + Integer.toHexString(id));
        }
    }

    static final class SocketTransport implements ITransport {
        private static final int READ_FRAGMENT_BYTES = 3;
        final Socket socket;
        final AtomicInteger dataPackets = new AtomicInteger();
        final AtomicInteger pureAcks = new AtomicInteger();
        volatile boolean dropNextAck;
        SocketTransport(Socket socket) { this.socket = socket; }
        @Override public String describe() { return "test loopback socket"; }
        @Override public int read(byte[] buffer, int offset, int count) throws IOException {
            return socket.getInputStream().read(buffer, offset, Math.min(count, READ_FRAGMENT_BYTES));
        }
        @Override public synchronized void write(byte[] buffer, int offset, int count) throws IOException {
            byte[] bytes = Arrays.copyOfRange(buffer, offset, offset + count);
            if (isPacket(bytes) && bytes.length == 9 && bytes[4] == 0x40) {
                pureAcks.incrementAndGet();
                if (dropNextAck) { dropNextAck = false; return; }
            }
            if (isPacket(bytes) && bytes.length > 9 && bytes[4] == 0x40) dataPackets.incrementAndGet();
            socket.getOutputStream().write(negotiatedSmallWindow(bytes));
            socket.getOutputStream().flush();
        }
        private static boolean isPacket(byte[] bytes) {
            return bytes.length >= 9 && bytes[0] == (byte) 0xFF && bytes[1] == 0x5A;
        }
        private static byte[] negotiatedSmallWindow(byte[] bytes) {
            if (!isPacket(bytes) || bytes[4] != (byte) 0x80) return bytes;
            byte[] payload = Arrays.copyOfRange(bytes, 9, bytes.length - 1);
            payload[2] = 0;
            payload[3] = (byte) 128;
            payload[4] = 0;
            payload[5] = 100;
            Iap2Packet.Numbers numbers = new Iap2Packet.Numbers(bytes[5], bytes[6]);
            return Iap2Packet.encode(new Iap2Packet.Fields(new Iap2Packet.Header(0x80, 0, numbers), payload));
        }
        @Override public void close() throws IOException { socket.close(); }
    }
}
