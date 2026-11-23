package com.iap2loc.android.protocol;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class ProtocolIntegrationTest {
    private static final int TEST_TIMEOUT_MS = 10000;
    private static final int SUBSCRIBED_PERIOD_MS = 40;
    private static final int STOP_SETTLE_MS = 150;
    private static final int ACK_RECOVERY_WAIT_MS = 800;

    @Test(timeout = TEST_TIMEOUT_MS)
    public void fragmentedTransportNegotiatesAndReassemblesCsm() throws Exception {
        try (LinkHarness harness = new LinkHarness()) {
            harness.start();
            byte[] content = new byte[2048];
            Arrays.fill(content, (byte) 0x5A);
            harness.accessory.send(0xCAFE, Csm.param(42, content));
            LinkHarness.Message received = harness.deviceEvents.take(0xCAFE);
            assertArrayEquals(content, received.parameters.get(42));
            assertTrue("CSM spans actual negotiated iAP2 packets", harness.accessoryTransport.dataPackets.get() > 1);
            harness.assertHealthy();
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void lostAckTriggersRetransmitAndDuplicateIsAcknowledgedOnce() throws Exception {
        try (LinkHarness harness = new LinkHarness()) {
            harness.start();
            int before = harness.accessoryTransport.dataPackets.get();
            harness.deviceTransport.dropNextAck = true;
            harness.accessory.send(0xCAFE, Csm.param(0, new byte[]{7}));
            harness.deviceEvents.take(0xCAFE);
            LinkHarness.await(() -> harness.accessoryTransport.dataPackets.get() > before + 1);
            Thread.sleep(ACK_RECOVERY_WAIT_MS);
            assertTrue("retransmission must not redeliver a CSM message", harness.deviceEvents.messages.isEmpty());
            assertEquals("one original packet and one recovered retransmission", before + 2,
                    harness.accessoryTransport.dataPackets.get());
            harness.assertHealthy();
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void identificationAndDeviceSubscriptionsDriveGgaRmcThenStop() throws Exception {
        try (LinkHarness harness = new LinkHarness()) {
            harness.attachLocation();
            harness.start();
            harness.device.send(0x1D00);
            LinkHarness.Message identity = harness.deviceEvents.take(0x1D01);
            assertTrue(identity.parameters.containsKey(17));
            harness.device.send(0xFFFA, Csm.param(1, new byte[0]), period(0x8001));
            assertSentence(harness.deviceEvents.take(0xFFFB), "$GPGGA,");
            int pushed = harness.session.messagesPushed();
            LinkHarness.await(() -> harness.session.messagesPushed() >= pushed + 3);
            harness.device.send(0xFFFC);
            LinkHarness.await(() -> !harness.session.subscribed());
            Thread.sleep(STOP_SETTLE_MS);
            harness.deviceEvents.messages.clear();
            assertNull(harness.deviceEvents.messages.poll(STOP_SETTLE_MS, TimeUnit.MILLISECONDS));
            harness.device.send(0xFFFA, Csm.param(2, new byte[0]), period(0x8002));
            assertSentence(harness.deviceEvents.take(0xFFFB), "$GPRMC,");
            harness.assertHealthy();
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void pathProgressArmsOnFirstSubscribeAndSurvivesUnsubscribe() throws Exception {
        try (LinkHarness harness = new LinkHarness()) {
            LocationTarget target = new LocationTarget(31.0, 121.0);
            List<RoutePath.Vertex> vertices = new ArrayList<>();
            vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.0), null));
            vertices.add(new RoutePath.Vertex(new Geo.Point(31.02, 121.0), null));
            LocationTarget.Update update = new LocationTarget.Update()
                    .mode(LocationTarget.Mode.PATH).route(vertices);
            update.pathSpeedMps = 50.0;
            target.apply(update);

            LocationSession.Options options = new LocationSession.Options();
            LocationSession session = new LocationSession(harness.accessory, target, options);
            session.setSubscriptionListener(() -> {
                if (session.subscribed()) target.setActive(true);
            });
            harness.session = session;
            harness.accessoryEvents.session = session;
            harness.start();
            harness.device.send(0x1D00);
            harness.deviceEvents.take(0x1D01);

            Thread.sleep(STOP_SETTLE_MS);
            assertEquals("链接建立但未订阅时路径不得前进",
                    0.0, target.snapshot().pathTravelledMeters, 0.001);

            harness.device.send(0xFFFA, Csm.param(1, new byte[0]), period(0x8001));
            assertSentence(harness.deviceEvents.take(0xFFFB), "$GPGGA,");
            LinkHarness.await(() -> target.snapshot().pathTravelledMeters > 0.5);

            harness.device.send(0xFFFC);
            LinkHarness.await(() -> !session.subscribed());
            double beforeGap = target.snapshot().pathTravelledMeters;
            LinkHarness.await(() -> target.snapshot().pathTravelledMeters > beforeGap + 0.5);
            harness.assertHealthy();
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void startingClosedLinkFailsWithoutSpawningReader() throws Exception {
        try (LinkHarness harness = new LinkHarness()) {
            harness.accessory.close();
            assertThrows(IllegalStateException.class, harness.accessory::start);
            assertEquals(Iap2Link.State.DEAD, harness.accessory.state());
        }
    }

    @Test(timeout = TEST_TIMEOUT_MS)
    public void concurrentStartAndCloseNeverSchedulesAfterShutdown() throws Exception {
        final int attempts = 20;
        for (int i = 0; i < attempts; i++) {
            try (LinkHarness harness = new LinkHarness()) {
                java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
                java.util.concurrent.atomic.AtomicReference<Throwable> error = new java.util.concurrent.atomic.AtomicReference<>();
                Thread start = new Thread(() -> {
                    try {
                        go.await();
                        harness.accessory.start();
                    } catch (IllegalStateException expectedWhenClosedFirst) {
                        // A closed link rejects a later start explicitly.
                    } catch (Throwable failure) { error.set(failure); }
                });
                start.start();
                go.countDown();
                harness.accessory.close();
                start.join(LinkHarness.WAIT_MS);
                assertFalse("start thread must finish", start.isAlive());
                assertNull(error.get());
                assertEquals(Iap2Link.State.DEAD, harness.accessory.state());
            }
        }
    }

    private static byte[] period(int parameter) {
        return Csm.param(parameter, new byte[]{0, 0, 0, SUBSCRIBED_PERIOD_MS});
    }

    private static void assertSentence(LinkHarness.Message message, String prefix) {
        String nmea = new String(message.parameters.get(0), StandardCharsets.UTF_8);
        assertTrue(nmea, nmea.startsWith(prefix));
        int star = nmea.indexOf('*');
        assertTrue(star > 0);
        int checksum = 0;
        for (int i = 1; i < star; i++) checksum ^= nmea.charAt(i);
        assertEquals(String.format("%02X", checksum), nmea.substring(star + 1, star + 3));
    }
}
