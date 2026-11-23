package com.iap2loc.android.protocol;

import com.iap2loc.android.util.AppLog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** NMEA sampling and scheduling after the device subscribes. */
final class LocationStream {
    private static final double JUMP_THRESHOLD_METERS = 100;
    private final Iap2Link link;
    private final LocationTarget target;
    private final Consumer<String> failed;
    private final Object timerGate = new Object();
    private final Object pushGate = new Object();
    private final Map<Integer, Long> lastSent = new HashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "iap2-nmea");
        thread.setDaemon(true);
        return thread;
    });

    private ScheduledFuture<?> timer;
    private volatile boolean subscribed;
    private volatile Runnable subscriptionEvents;
    private volatile LocationSubscription subscription;
    private volatile boolean disposed;
    private volatile int intervalMs;
    private volatile int pushed;
    private boolean hasLastFix;
    private double lastFixLatitude;
    private double lastFixLongitude;


    LocationStream(Iap2Link link, LocationTarget target, Consumer<String> failed) {
        this.link = link;
        this.target = target;
        this.failed = failed;
        this.intervalMs = 250;
    }

    boolean subscribed() { return subscribed; }
    int pushed() { return pushed; }

    void setSubscriptionListener(Runnable listener) { subscriptionEvents = listener; }

    void start(Map<Integer, byte[]> parameters) {
        subscription = LocationSubscription.parseLocationSubscription(parameters);
        synchronized (lastSent) { lastSent.clear(); }
        subscribed = true;
        notifySubscription();
        AppLog.ok("rx 0xFFFA StartLocationInformation, " +
                LocationSubscription.describe(subscription, plannedInterval()));
        AppLog.info("0xFFFA raw parameters: " + LocationSubscription.describeRaw(parameters));
        startTimer();
        restartTimer();
    }

    void stop() {
        boolean was = subscribed;
        subscribed = false;
        subscription = null;
        stopTimer();
        if (was) notifySubscription();
        AppLog.info("rx 0xFFFC StopLocationInformation");
    }

    private void notifySubscription() {
        Runnable listener = subscriptionEvents;
        if (listener != null) listener.run();
    }

    public void setInterval(int value) {
        int next = Math.max(10, value);
        synchronized (timerGate) {
            if (next == intervalMs) return;
            intervalMs = next;
            rearmLocked();
        }
    }

    private void startTimer() {
        synchronized (timerGate) {
            if (timer != null || disposed) return;
            LocationTarget.State state = target.snapshot();
            if (state.mode == LocationTarget.Mode.PATH) {
                String detail = state.pathTimed
                        ? String.format("%.2f km over %.0f s of GPX time", state.routeLengthMeters / 1000, state.pathDurationSeconds)
                        : String.format("%.2f km at %.1f km/h", state.routeLengthMeters / 1000, state.pathSpeedMps * 3.6);
                AppLog.info("streaming " + plannedInterval() + " ms NMEA along " + state.routePoints + " points (" + detail + ")");
            } else {
                AppLog.info("streaming " + plannedInterval() + " ms NMEA for " +
                        String.format("%.6f,%.6f", state.latitude, state.longitude));
            }
            timer = scheduler.schedule(this::push, 0, TimeUnit.MILLISECONDS);
        }
    }

    private void stopTimer() {
        synchronized (timerGate) {
            if (timer != null) {
                timer.cancel(false);
                timer = null;
            }
        }
    }

    private void restartTimer() {
        synchronized (timerGate) {
            rearmLocked();
        }
    }

    private void rearmLocked() {
        if (timer == null || disposed) return;
        timer.cancel(false);
        timer = scheduler.schedule(this::push, Math.max(10, nextDelay()), TimeUnit.MILLISECONDS);
    }

    private void push() {
        try {
            if (disposed || !subscribed || link.state() != Iap2Link.State.NORMAL) return;
            synchronized (pushGate) {
                LocationTarget.Fix fix = target.next();
                double[] motion = motionFor(fix);
                Double speed = motion[0] < -1e90 ? null : motion[0];
                Double bearing = motion[1] < -1e90 ? null : motion[1];
                LocationTarget.Fix sentFix = new LocationTarget.Fix(
                        fix.latitude, fix.longitude, fix.altitudeMeters,
                        speed, bearing, fix.accuracyMeters, fix.quality, fix.satellites);

                String nmea = Nmea.encode(sentFix, new Date());
                long tick = ticks();
                SentenceBatch batch = sentences(nmea, subscription, tick);
                if (batch.parameters.isEmpty()) return;
                if (disposed || !subscribed) return;
                link.send(0xFFFB, batch.parameters.toArray(new byte[0][]));
                recordSent(batch, fix, tick);
                pushed++;
            }
        } catch (Exception ex) {
            failed.accept("sending 0xFFFB failed: " + ex.getMessage());
        } finally {
            synchronized (timerGate) {
                rearmLocked();
            }
        }
    }

    private static final class SentenceBatch {
        final List<byte[]> parameters = new ArrayList<>();
        final List<Integer> ids = new ArrayList<>();
    }

    private SentenceBatch sentences(String nmea, LocationSubscription current, long tick) {
        SentenceBatch batch = new SentenceBatch();
        for (String sentence : nmea.split("\r\n")) {
            if (sentence.isEmpty()) continue;
            int id = LocationSubscription.sentenceId(sentence);
            if (current != null && !current.sentences.isEmpty() && !current.sentences.contains(id)) continue;
            if (!due(current, id, tick)) continue;
            batch.parameters.add(Csm.param(0, (sentence + "\r\n\0").getBytes(StandardCharsets.UTF_8)));
            batch.ids.add(id);
        }
        return batch;
    }

    private void recordSent(SentenceBatch batch, LocationTarget.Fix fix, long tick) {
        if (batch.ids.contains(2)) {
            lastFixLatitude = fix.latitude;
            lastFixLongitude = fix.longitude;
            hasLastFix = true;
        }
        synchronized (lastSent) {
            for (int id : batch.ids) lastSent.put(id, tick);
        }
    }

    private double[] motionFor(LocationTarget.Fix fix) {
        double nullMarker = -1e100;
        if (hasLastFix) {
            Geo.Point from = new Geo.Point(lastFixLatitude, lastFixLongitude);
            Geo.Point to = new Geo.Point(fix.latitude, fix.longitude);
            double distance = Geo.distance(from, to);
            if (distance >= JUMP_THRESHOLD_METERS) {
                AppLog.info("位置跳变 " + String.format("%.1f", distance / 1000) +
                        " km（按真实速度航向上报，不伪造运动参数）");
            }
        }
        return new double[]{
                fix.speedMetersPerSecond == null ? nullMarker : fix.speedMetersPerSecond,
                fix.bearingDegrees == null ? nullMarker : fix.bearingDegrees};
    }

    private boolean due(LocationSubscription current, int sentenceId, long now) {
        Long last;
        synchronized (lastSent) {
            last = lastSent.get(sentenceId);
        }
        return last == null || now - last >= effectiveInterval(current, sentenceId);
    }

    private int effectiveInterval(LocationSubscription current, int sentenceId) {
        if (current != null) {
            Integer requested = current.requestedMs.get(sentenceId);
            if (requested != null && requested > 0) return requested;
        }
        return intervalMs;
    }

    private int plannedInterval() {
        LocationSubscription current = subscription;
        if (current == null) return intervalMs;
        int fastest = Integer.MAX_VALUE;
        for (int id : LocationSubscription.tracked(current)) fastest = Math.min(fastest, effectiveInterval(current, id));
        return fastest == Integer.MAX_VALUE ? intervalMs : fastest;
    }

    private int nextDelay() {
        LocationSubscription current = subscription;
        if (current == null) return intervalMs;
        long now = ticks();
        int soonest = Integer.MAX_VALUE;
        synchronized (lastSent) {
            for (int id : LocationSubscription.tracked(current)) {
                int interval = effectiveInterval(current, id);
                Long last = lastSent.get(id);
                long elapsed = last == null ? interval : now - last;
                soonest = Math.min(soonest, (int) (interval - elapsed));
            }
        }
        return soonest == Integer.MAX_VALUE ? intervalMs : Math.max(10, soonest);
    }

    private static long ticks() { return System.nanoTime() / 1_000_000L; }

    void dispose() {
        disposed = true;
        stop();
        scheduler.shutdownNow();
    }
}
