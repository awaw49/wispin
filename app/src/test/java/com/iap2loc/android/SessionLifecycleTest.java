package com.iap2loc.android;

import com.iap2loc.android.engine.CancellationScope;
import com.iap2loc.android.engine.RunRequest;
import com.iap2loc.android.engine.RunRequests;
import com.iap2loc.android.engine.RunSession;
import com.iap2loc.android.engine.SessionDependencies;
import com.iap2loc.android.protocol.AccessoryProfile;
import com.iap2loc.android.protocol.LocationTarget;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.*;

public final class SessionLifecycleTest {
    private static final long WAIT_SECONDS = 2;

    @Test public void scopeClosesInReverseOrderAndExposesCleanupErrors() {
        List<String> events = new ArrayList<>();
        CancellationScope scope = new CancellationScope(events::add);
        scope.own(() -> events.add("socket"));
        scope.own(() -> { throw new Exception("timer close failed"); });
        scope.close();
        scope.close();
        assertEquals(2, events.size());
        assertTrue(events.get(0).contains("timer close failed"));
        assertEquals("socket", events.get(1));
    }

    @Test public void resourceRegisteredAfterStopIsImmediatelyClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        CancellationScope scope = new CancellationScope(message -> fail(message));
        scope.close();
        assertThrows(CancellationException.class, () -> scope.own(() -> closed.set(true)));
        assertTrue(closed.get());
    }

    @Test(timeout = 5000) public void stoppingDuringConnectClosesPendingResourceAndInterruptsWorker() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch exited = new CountDownLatch(1);
        SessionDependencies dependencies = new SessionDependencies(new LocationTarget(0, 0), () -> null,
                (request, scope) -> {
                    scope.own(closed::countDown);
                    entered.countDown();
                    try {
                        new CountDownLatch(1).await();
                        throw new AssertionError("connection should be interrupted");
                    } finally { exited.countDown(); }
                });
        RunSession session = new RunSession("test", new RunRequest(), dependencies);
        session.start();
        assertTrue(entered.await(WAIT_SECONDS, TimeUnit.SECONDS));
        session.stop();
        assertTrue(closed.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertTrue(exited.await(WAIT_SECONDS, TimeUnit.SECONDS));
        assertFalse(session.running());
        assertEquals("Stopped", session.linkState());
        assertNull(session.error());
    }

    @Test public void invalidSessionInputIsRejectedBeforeConnection() {
        RunRequest request = new RunRequest();
        request.mac = "garbage-AA:BB:CC:DD:EE:FF";
        assertThrows(IllegalArgumentException.class,
                () -> RunRequests.validatedCopy(request, AccessoryProfile.defaults()));
        request.mac = null;
        assertThrows(IllegalArgumentException.class,
                () -> RunRequests.validatedCopy(request, AccessoryProfile.defaults()));
    }

    @Test public void copiedRequestPreservesConnectionFieldsWithoutMutatingInput() {
        RunRequest request = new RunRequest();
        request.mac = "AA:BB:CC:DD:EE:FF";
        request.rate = 500;
        request.label = "  ";
        request.connectTimeoutSeconds = 30;
        AccessoryProfile profile = AccessoryProfile.defaults();
        RunRequest copy = RunRequests.validatedCopy(request, profile);
        assertEquals("AA:BB:CC:DD:EE:FF", copy.mac);
        assertEquals(500, copy.rate);
        assertEquals("iPhone", copy.label);
        assertEquals(30, copy.connectTimeoutSeconds);
        assertNotSame(profile, copy.profile);
        assertNotSame(request, copy);
    }
}
