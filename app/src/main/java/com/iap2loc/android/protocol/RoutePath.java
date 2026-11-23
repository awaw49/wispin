package com.iap2loc.android.protocol;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A polyline followed at constant speed or by the timestamps of an imported GPX.
 *  When {@code closed} is set, an implicit leg from the last vertex back to the
 *  first makes the route a true circuit: sampling wraps with no position jump,
 *  which iOS requires to keep adopting fixes across lap boundaries. */
public final class RoutePath {
    private static final double CLOSE_EPSILON_METERS = 0.5;

    public static final class Vertex {
        public final Geo.Point point;
        public final Double timeSeconds;

        public Vertex(Geo.Point point, Double timeSeconds) {
            this.point = point;
            this.timeSeconds = timeSeconds;
        }
    }

    public static final class Sample {
        public final Geo.Point point;
        public final double bearing;
        public final double speedMetersPerSecond;
        public final double distanceMeters;

        public Sample(Geo.Point point, double bearing, double speed, double distance) {
            this.point = point;
            this.bearing = bearing;
            this.speedMetersPerSecond = speed;
            this.distanceMeters = distance;
        }

        Sample withSpeed(double speed) {
            return new Sample(point, bearing, speed, distanceMeters);
        }
    }

    private final List<Vertex> vertices;
    private final Geo.Point[] points;
    private final double[] cumulative;
    private final double[] times;
    private final boolean closed;
    private final double openLength;
    private final double closingLegMeters;
    private final double closingLegSpeed;
    private final double closingSeconds;
    private final double length;
    private final double duration;

    public RoutePath(List<Vertex> vertices) {
        this(vertices, false);
    }

    public RoutePath(List<Vertex> vertices, boolean closed) {
        this.vertices = Collections.unmodifiableList(new ArrayList<>(vertices));
        points = new Geo.Point[vertices.size()];
        for (int i = 0; i < vertices.size(); i++) points[i] = vertices.get(i).point;
        cumulative = new double[points.length];
        for (int i = 1; i < points.length; i++) {
            cumulative[i] = cumulative[i - 1] + Geo.distance(points[i - 1], points[i]);
        }
        double[] parsedTimes = buildTimeline(vertices);
        times = parsedTimes;
        openLength = cumulative.length == 0 ? 0 : cumulative[cumulative.length - 1];
        this.closed = closed && points.length >= 2
                && Geo.distance(points[points.length - 1], points[0]) > CLOSE_EPSILON_METERS;
        closingLegMeters = this.closed
                ? Geo.distance(points[points.length - 1], points[0]) : 0;
        closingLegSpeed = computeClosingSpeed(points, cumulative, parsedTimes);
        closingSeconds = this.closed && parsedTimes != null && closingLegSpeed > 0
                ? closingLegMeters / closingLegSpeed : 0;
        length = openLength + closingLegMeters;
        duration = parsedTimes == null ? 0 : parsedTimes[parsedTimes.length - 1] + closingSeconds;
        signature = mix(computeSignature(vertices), this.closed ? 1 : 0);
    }

    public final long signature;

    public int count() { return points.length; }
    public double lengthMeters() { return length; }
    public boolean closed() { return closed; }
    public boolean hasTimeline() { return times != null; }
    public double durationSeconds() { return duration; }
    public boolean isUsable() { return points.length >= 2 && length > 0.5; }
    public List<Geo.Point> points() { return Collections.unmodifiableList(Arrays.asList(points)); }
    public List<Vertex> vertices() { return vertices; }

    public Sample at(double travelledMeters) {
        if (points.length == 0) return new Sample(new Geo.Point(0, 0), 0, 0, 0);
        if (points.length == 1 || length <= 0) {
            return new Sample(points[points.length - 1], 0, 0, 0);
        }

        double distance = Math.max(0, Math.min(travelledMeters, length));
        if (closed && distance > openLength) {
            return closingSample(distance);
        }
        int index = Arrays.binarySearch(cumulative, distance);
        if (index < 0) index = -index - 2;
        index = Math.max(0, Math.min(points.length - 1, index));

        if (index >= points.length - 1) {
            Geo.Point last = points[points.length - 1];
            return new Sample(last, Geo.bearing(points[points.length - 2], last), 0, openLength);
        }

        Geo.Point start = points[index];
        Geo.Point end = points[index + 1];
        double span = cumulative[index + 1] - cumulative[index];
        double ratio = span <= 0 ? 0 : (distance - cumulative[index]) / span;
        double latitude = start.latitude + (end.latitude - start.latitude) * ratio;
        double longitude = start.longitude + (end.longitude - start.longitude) * ratio;
        Double altitude;
        if (start.altitude != null && end.altitude != null) {
            altitude = start.altitude + (end.altitude - start.altitude) * ratio;
        } else if (start.altitude != null) {
            altitude = start.altitude;
        } else {
            altitude = end.altitude;
        }
        return new Sample(new Geo.Point(latitude, longitude, altitude),
                Geo.bearing(start, end), 0, distance);
    }

    public Sample atTime(double elapsedSeconds, boolean loop) {
        if (times == null || !isUsable()) return at(0);
        double seconds = loop
                ? ((elapsedSeconds % duration) + duration) % duration
                : Math.max(0, Math.min(elapsedSeconds, duration));
        if (closed && seconds > times[times.length - 1]) {
            double distance = openLength + closingLegSpeed * (seconds - times[times.length - 1]);
            return closingSample(Math.min(distance, length)).withSpeed(closingLegSpeed);
        }
        int index = Arrays.binarySearch(times, seconds);
        if (index < 0) index = -index - 2;
        index = Math.max(0, Math.min(points.length - 1, index));

        if (index >= points.length - 1) {
            Geo.Point last = points[points.length - 1];
            return new Sample(last, Geo.bearing(points[points.length - 2], last),
                    speedAt(points.length - 2), openLength);
        }

        double span = times[index + 1] - times[index];
        double ratio = span <= 0 ? 0 : (seconds - times[index]) / span;
        double distance = cumulative[index] + (cumulative[index + 1] - cumulative[index]) * ratio;
        return at(distance).withSpeed(speedAt(index));
    }

    private Sample closingSample(double distance) {
        Geo.Point last = points[points.length - 1];
        Geo.Point first = points[0];
        double ratio = closingLegMeters <= 0 ? 0 : (distance - openLength) / closingLegMeters;
        ratio = Math.max(0, Math.min(1, ratio));
        double latitude = last.latitude + (first.latitude - last.latitude) * ratio;
        double longitude = last.longitude + (first.longitude - last.longitude) * ratio;
        Double altitude;
        if (last.altitude != null && first.altitude != null) {
            altitude = last.altitude + (first.altitude - last.altitude) * ratio;
        } else if (last.altitude != null) {
            altitude = last.altitude;
        } else {
            altitude = first.altitude;
        }
        return new Sample(new Geo.Point(latitude, longitude, altitude),
                Geo.bearing(last, first), 0, distance);
    }

    private static double computeClosingSpeed(Geo.Point[] points, double[] cumulative, double[] times) {
        if (times == null || points.length < 2) return 0;
        double span = times[times.length - 1] - times[times.length - 2];
        if (span <= 0) return 0;
        return (cumulative[cumulative.length - 1] - cumulative[cumulative.length - 2]) / span;
    }

    private double speedAt(int index) {
        if (times != null && index >= 0 && index < points.length - 1) {
            double span = times[index + 1] - times[index];
            if (span > 0) return (cumulative[index + 1] - cumulative[index]) / span;
        }
        return duration > 0 ? length / duration : 0;
    }

    private static double[] buildTimeline(List<Vertex> vertices) {
        if (vertices.size() < 2 || vertices.get(0).timeSeconds == null) return null;
        double start = vertices.get(0).timeSeconds;
        double[] result = new double[vertices.size()];
        double previous = 0;
        for (int i = 0; i < vertices.size(); i++) {
            Double value = vertices.get(i).timeSeconds;
            if (value == null) return null;
            double offset = value - start;
            if (Double.isNaN(offset) || offset < previous) return null;
            result[i] = offset;
            previous = offset;
        }
        return result[result.length - 1] > 0.5 ? result : null;
    }

    private static long computeSignature(List<Vertex> vertices) {
        long hash = 0xcbf29ce484222325L; // FNV-1a 64 offset basis
        hash = mix(hash, vertices.size());
        for (Vertex vertex : vertices) {
            hash = mix(hash, bits(vertex.point.latitude, 7));
            hash = mix(hash, bits(vertex.point.longitude, 7));
            hash = mix(hash, vertex.point.altitude == null
                    ? -1L : Double.doubleToLongBits(Math.round(vertex.point.altitude * 10) / 10.0));
            hash = mix(hash, vertex.timeSeconds == null
                    ? -1L : Double.doubleToLongBits(Math.round(vertex.timeSeconds * 10) / 10.0));
        }
        return hash;
    }

    private static long bits(double value, int decimals) {
        double factor = Math.pow(10, decimals);
        return Double.doubleToLongBits(Math.round(value * factor) / factor);
    }

    private static long mix(long hash, long value) {
        for (int i = 0; i < 8; i++) {
            hash ^= (value >>> (i * 8)) & 0xFF;
            hash *= 1099511628211L;
        }
        return hash;
    }
}
