package com.iap2loc.android.map;

import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable route edits keep original GPX metadata until a user changes the geometry. */
public final class RouteEditor {
    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;
    private final List<RoutePath.Vertex> points;
    private final List<List<RoutePath.Vertex>> history;

    public RouteEditor(List<RoutePath.Vertex> points) {
        this(points, Collections.emptyList());
    }

    private RouteEditor(List<RoutePath.Vertex> points, List<List<RoutePath.Vertex>> history) {
        for (RoutePath.Vertex vertex : points) validate(vertex);
        this.points = Collections.unmodifiableList(new ArrayList<>(points));
        this.history = Collections.unmodifiableList(new ArrayList<>(history));
    }

    public List<RoutePath.Vertex> points() { return points; }
    public boolean canUndo() { return !history.isEmpty(); }
    public boolean hasTimestamps() {
        return points.stream().anyMatch(vertex -> vertex.timeSeconds != null);
    }

    public RouteEditor add(Geo.Point point) {
        List<RoutePath.Vertex> next = new ArrayList<>(points);
        next.add(new RoutePath.Vertex(point, null));
        return edited(next);
    }

    public RouteEditor move(int index, Geo.Point point) {
        List<RoutePath.Vertex> next = new ArrayList<>(points);
        Geo.Point previous = next.get(index).point;
        next.set(index, new RoutePath.Vertex(
                new Geo.Point(point.latitude, point.longitude, previous.altitude), null));
        return edited(next);
    }

    public RouteEditor remove(int index) {
        List<RoutePath.Vertex> next = new ArrayList<>(points);
        next.remove(index);
        return edited(next);
    }

    public RouteEditor clear() {
        return points.isEmpty() ? this : edited(Collections.emptyList());
    }

    public RouteEditor undo() {
        if (!canUndo()) return this;
        int lastIndex = history.size() - 1;
        return new RouteEditor(history.get(lastIndex), history.subList(0, lastIndex));
    }

    private RouteEditor edited(List<RoutePath.Vertex> next) {
        List<RoutePath.Vertex> untimed = new ArrayList<>();
        for (RoutePath.Vertex vertex : next) untimed.add(new RoutePath.Vertex(vertex.point, null));
        List<List<RoutePath.Vertex>> snapshots = new ArrayList<>(history);
        snapshots.add(points);
        return new RouteEditor(untimed, snapshots);
    }

    private static void validate(RoutePath.Vertex vertex) {
        Geo.Point point = vertex.point;
        if (!Double.isFinite(point.latitude) || Math.abs(point.latitude) > MAX_LATITUDE
                || !Double.isFinite(point.longitude) || Math.abs(point.longitude) > MAX_LONGITUDE) {
            throw new IllegalArgumentException("地图坐标不在有效经纬度范围内");
        }
        if (point.altitude != null && !Double.isFinite(point.altitude)) {
            throw new IllegalArgumentException("路径高度必须是有限数值");
        }
        if (vertex.timeSeconds != null && !Double.isFinite(vertex.timeSeconds)) {
            throw new IllegalArgumentException("路径时间戳必须是有限数值");
        }
    }
}
