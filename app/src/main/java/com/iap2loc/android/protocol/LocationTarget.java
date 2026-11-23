package com.iap2loc.android.protocol;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** Shared live target. Changes take effect on the next fix without reconnecting. */
public final class LocationTarget extends LocationTargetModels {
    public static final int MAX_ROUTE_POINTS = 20_000;

    private final Object gate = new Object();

    private Mode mode = Mode.POINT;
    private double latitude;
    private double longitude;
    private Double altitudeMeters;
    private Double accuracyMeters;
    private Double speedMetersPerSecond;
    private Double bearingDegrees;
    private double jitterMeters;
    private boolean jitterAccuracy;
    private double accuracyMinMeters = 3;
    private double accuracyMaxMeters = 12;
    private int quality = 1;
    private int satellites = 8;
    private boolean jitterAltitude;
    private double altitudeMinMeters = 5;
    private double altitudeMaxMeters = 20;

    private RoutePath route;
    private List<RoutePath.Vertex> routeVertices = java.util.Collections.emptyList();
    private long routeSignature;
    private double pathSpeedMps = 1.4;
    private boolean pathLoop = true;
    private boolean pathActive;
    private long pathAnchorTicks = ticks();
    private double pathAnchorMeters;
    private double pathAnchorSeconds;

    private double resolvedLatitude;
    private double resolvedLongitude;
    private Double resolvedAccuracyMeters;
    private Double resolvedAltitudeMeters;

    public LocationTarget(double latitude, double longitude) {
        Geo.Point initial = Geo.normalize(new Geo.Point(latitude, longitude));
        this.latitude = initial.latitude;
        this.longitude = initial.longitude;
        this.resolvedLatitude = initial.latitude;
        this.resolvedLongitude = initial.longitude;
        resolve();
    }

    public void apply(Update update) {
        synchronized (gate) {
            State before = snapshot();
            boolean restartPath = applyPath(update);
            applyParameters(update);
            if (restartPath) {
                pathAnchorMeters = 0;
                pathAnchorSeconds = 0;
                pathAnchorTicks = ticks();
            }
            if (needsResolve(before) || restartPath) {
                resolve();
                return;
            }
            if (!jitterAccuracy && !equal(accuracyMeters, before.accuracy)) resolvedAccuracyMeters = accuracyMeters;
            if (!jitterAltitude && !equal(altitudeMeters, before.altitude)) resolvedAltitudeMeters = altitudeMeters;
        }
    }

    private boolean applyPath(Update update) {
        boolean restart = update.mode != null && update.mode != mode;
        if (update.mode != null) mode = update.mode;
        boolean loopChanged = update.pathLoop != null && update.pathLoop != pathLoop;
        if (loopChanged) pathLoop = update.pathLoop;
        if (update.route != null || loopChanged) {
            restart |= replaceRoute(update.route != null ? update.route : routeVertices);
        }
        if (update.pathSpeedMps != null) {
            double next = Math.max(0.1, Math.min(50, update.pathSpeedMps));
            if (Math.abs(next - pathSpeedMps) > 1e-9) {
                rebasePath();
                pathSpeedMps = next;
            }
        }
        return restart;
    }

    private boolean replaceRoute(List<RoutePath.Vertex> vertices) {
        if (vertices.size() > MAX_ROUTE_POINTS) {
            throw new IllegalArgumentException("路径点数超出 " + MAX_ROUTE_POINTS + "；请先抽稀或分段");
        }
        routeVertices = vertices;
        RoutePath next = vertices.isEmpty() ? null : new RoutePath(vertices, pathLoop);
        long signature = next == null ? 0 : next.signature;
        int nextCount = next == null ? 0 : next.count();
        int oldCount = route == null ? 0 : route.count();
        if (signature == routeSignature && nextCount == oldCount) return false;
        route = next;
        routeSignature = signature;
        return true;
    }

    private void applyParameters(Update update) {
        applyNmea(update);
        applyAccuracy(update);
        applyAltitude(update);
    }

    private void applyNmea(Update update) {
        applyCoordinates(update);
        if (update.clearSpeed) speedMetersPerSecond = null;
        if (update.clearBearing) bearingDegrees = null;
        if (update.speed != null) speedMetersPerSecond = update.speed;
        if (update.bearing != null) bearingDegrees = update.bearing;
        if (update.quality != null) quality = Math.max(0, Math.min(8, update.quality));
        if (update.satellites != null) satellites = Math.max(0, Math.min(99, update.satellites));
    }

    private void applyCoordinates(Update update) {
        if (update.latitude != null && Double.isFinite(update.latitude)) latitude = Geo.normalizeLatitude(update.latitude);
        if (update.longitude != null && Double.isFinite(update.longitude)) longitude = Geo.normalizeLongitude(update.longitude);
    }

    private void applyAccuracy(Update update) {
        if (update.clearAccuracy) accuracyMeters = null;
        if (update.accuracy != null) accuracyMeters = update.accuracy;
        if (update.jitterMeters != null) jitterMeters = Math.max(0, update.jitterMeters);
        if (update.jitterAccuracy != null) jitterAccuracy = update.jitterAccuracy;
        if (update.accuracyMin != null) accuracyMinMeters = Math.max(0.1, update.accuracyMin);
        if (update.accuracyMax != null) accuracyMaxMeters = Math.max(accuracyMinMeters, update.accuracyMax);
    }

    private void applyAltitude(Update update) {
        if (update.clearAltitude) altitudeMeters = null;
        if (update.altitude != null) altitudeMeters = update.altitude;
        if (update.jitterAltitude != null) jitterAltitude = update.jitterAltitude;
        if (update.altitudeMin != null) altitudeMinMeters = update.altitudeMin;
        if (update.altitudeMax != null) altitudeMaxMeters = Math.max(altitudeMinMeters, update.altitudeMax);
    }

    private boolean needsResolve(State before) {
        return latitude != before.latitude || longitude != before.longitude ||
                Math.abs(jitterMeters - before.jitterMeters) > 1e-9 ||
                jitterAccuracy != before.jitterAccuracy ||
                Math.abs(accuracyMinMeters - before.accuracyMin) > 1e-9 ||
                Math.abs(accuracyMaxMeters - before.accuracyMax) > 1e-9 ||
                jitterAltitude != before.jitterAltitude ||
                Math.abs(altitudeMinMeters - before.altitudeMin) > 1e-9 ||
                Math.abs(altitudeMaxMeters - before.altitudeMax) > 1e-9;
    }

    private static boolean equal(Double a, Double b) {
        return a == null ? b == null : a.equals(b);
    }

    public State snapshot() {
        synchronized (gate) {
            State state = new State();
            state.mode = mode;
            state.latitude = latitude;
            state.longitude = longitude;
            state.altitude = altitudeMeters;
            state.accuracy = accuracyMeters;
            state.speed = speedMetersPerSecond;
            state.bearing = bearingDegrees;
            state.jitterMeters = jitterMeters;
            state.jitterAccuracy = jitterAccuracy;
            state.accuracyMin = accuracyMinMeters;
            state.accuracyMax = accuracyMaxMeters;
            state.jitterAltitude = jitterAltitude;
            state.altitudeMin = altitudeMinMeters;
            state.altitudeMax = altitudeMaxMeters;
            state.resolvedLatitude = resolvedLatitude;
            state.resolvedLongitude = resolvedLongitude;
            state.resolvedAccuracy = resolvedAccuracyMeters;
            state.resolvedAltitude = resolvedAltitudeMeters;
            state.route = route == null ? java.util.Collections.emptyList() : route.vertices();
            state.routePoints = route == null ? 0 : route.count();
            state.routeLengthMeters = route == null ? 0 : route.lengthMeters();
            state.pathTravelledMeters = route == null ? 0 : currentSample().distanceMeters;
            state.pathSpeedMps = pathSpeedMps;
            state.pathLoop = pathLoop;
            state.pathDurationSeconds = route == null ? 0 : route.durationSeconds();
            state.pathTimed = route != null && route.hasTimeline();
            state.quality = quality;
            state.satellites = satellites;
            return state;
        }
    }

    public Fix next() {
        synchronized (gate) {
            if (mode == Mode.PATH && route != null && route.isUsable()) {
                RoutePath.Sample sample = currentSample();
                return new Fix(
                        sample.point.latitude,
                        sample.point.longitude,
                        sample.point.altitude != null ? sample.point.altitude : resolvedAltitudeMeters,
                        route.hasTimeline() ? sample.speedMetersPerSecond : pathSpeedMps,
                        sample.bearing,
                        resolvedAccuracyMeters,
                        quality,
                        satellites);
            }
            return new Fix(resolvedLatitude, resolvedLongitude, resolvedAltitudeMeters,
                    speedMetersPerSecond, bearingDegrees, resolvedAccuracyMeters, quality, satellites);
        }
    }

    private RoutePath.Sample currentSample() {
        double elapsed = elapsedLocked();
        if (route.hasTimeline()) {
            return route.atTime(pathAnchorSeconds + elapsed, pathLoop);
        }
        double distance = pathAnchorMeters + pathSpeedMps * elapsed;
        double length = route.lengthMeters();
        if (length <= 0) return route.at(0);
        return route.at(pathLoop ? distance % length : Math.min(distance, length));
    }

    private void rebasePath() {
        double elapsed = elapsedLocked();
        if (route != null && route.hasTimeline()) {
            pathAnchorSeconds += elapsed;
        } else if (route != null && route.isUsable()) {
            pathAnchorMeters = currentSample().distanceMeters;
        }
        pathAnchorTicks = ticks();
    }

    /** The path clock runs once armed by a subscription while a session is alive;
     *  before that and after the session ends, progress freezes in place. */
    public void setActive(boolean active) {
        synchronized (gate) {
            if (active == pathActive) return;
            if (!active && route != null && route.isUsable()) {
                if (route.hasTimeline()) {
                    pathAnchorSeconds += elapsedLocked();
                } else {
                    pathAnchorMeters = currentSample().distanceMeters;
                }
            }
            pathAnchorTicks = ticks();
            pathActive = active;
        }
    }

    private double elapsedLocked() {
        return pathActive ? Math.max(0, ticks() - pathAnchorTicks) / 1000.0 : 0;
    }

    private void resolve() {
        resolvedLatitude = latitude;
        resolvedLongitude = longitude;
        resolvedAccuracyMeters = accuracyMeters;
        resolvedAltitudeMeters = altitudeMeters;

        if (jitterMeters > 0) {
            ThreadLocalRandom random = ThreadLocalRandom.current();
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = Math.sqrt(random.nextDouble()) * jitterMeters;
            Geo.Point shifted = Geo.normalize(Geo.offset(new Geo.Point(latitude, longitude), distance, angle));
            resolvedLatitude = shifted.latitude;
            resolvedLongitude = shifted.longitude;
        }
        if (jitterAccuracy) {
            double span = Math.max(0, accuracyMaxMeters - accuracyMinMeters);
            resolvedAccuracyMeters = Math.round((accuracyMinMeters + ThreadLocalRandom.current().nextDouble() * span) * 10) / 10.0;
        }
        if (jitterAltitude) {
            double span = Math.max(0, altitudeMaxMeters - altitudeMinMeters);
            resolvedAltitudeMeters = Math.round((altitudeMinMeters + ThreadLocalRandom.current().nextDouble() * span) * 10) / 10.0;
        }
    }

    private static long ticks() {
        return System.nanoTime() / 1_000_000L;
    }
}
