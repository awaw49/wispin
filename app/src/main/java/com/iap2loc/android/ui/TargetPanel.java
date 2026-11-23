package com.iap2loc.android.ui;

import com.iap2loc.android.R;
import com.iap2loc.android.protocol.RoutePath;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/** Draft target: map edits, coordinate fields and path options merged into one apply payload. */
public final class TargetPanel {
    public interface RouteListener {
        void onRouteChanged(JSONArray route);
    }

    private static final int MIN_ROUTE_POINTS = 2;
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final double METERS_PER_KILOMETER = 1000.0;
    private final ControlContext ui;
    private final Fields fields;
    private JSONArray route = new JSONArray();
    private boolean pathMode;
    private boolean initialized;
    private RouteListener routeListener;

    public TargetPanel(ControlContext ui) {
        this.ui = ui;
        fields = new Fields(ui.activity);
        defaults();
        ui.activity.findViewById(R.id.applyParams).setOnClickListener(v -> ui.run(this::apply));
    }

    private void defaults() {
        fields.set(R.id.latEdit, "22.3825");
        fields.set(R.id.lonEdit, "114.1880");
        fields.set(R.id.rateEdit, "250");
        fields.set(R.id.pathSpeedEdit, "5");
        fields.set(R.id.qualityEdit, "1");
        fields.set(R.id.satellitesEdit, "8");
        fields.set(R.id.jitterEdit, "0");
        fields.set(R.id.accuracyMinEdit, "3");
        fields.set(R.id.accuracyMaxEdit, "12");
        fields.set(R.id.altitudeMinEdit, "5");
        fields.set(R.id.altitudeMaxEdit, "20");
    }

    public void setRouteListener(RouteListener listener) { routeListener = listener; }

    public void setMode(boolean path) {
        pathMode = path;
        showInfo();
    }

    public boolean pathMode() { return pathMode; }
    public boolean pathLoop() { return fields.checked(R.id.pathLoopCheck); }

    /** Map draft arrives as WGS-84 lat/lon/alt/time objects. */
    public void applyMapDraft(JSONArray points) throws Exception {
        if (pathMode) {
            route = points;
        } else if (points.length() > 0) {
            JSONObject point = points.getJSONObject(0);
            fields.set(R.id.latEdit, String.format(Locale.US, "%.6f", point.getDouble("lat")));
            fields.set(R.id.lonEdit, String.format(Locale.US, "%.6f", point.getDouble("lon")));
        }
        showInfo();
    }

    public void setRoute(JSONArray next) {
        route = next;
        showInfo();
    }

    public void receiveGpx(List<RoutePath.Vertex> vertices) throws Exception {
        if (vertices.size() < MIN_ROUTE_POINTS) throw new IllegalArgumentException("GPX 至少需要两个点");
        JSONArray next = new JSONArray();
        for (RoutePath.Vertex vertex : vertices) {
            JSONObject point = new JSONObject();
            point.put("lat", vertex.point.latitude);
            point.put("lon", vertex.point.longitude);
            if (vertex.point.altitude != null) point.put("alt", vertex.point.altitude);
            if (vertex.timeSeconds != null) point.put("time", vertex.timeSeconds * MILLIS_PER_SECOND);
            next.put(point);
        }
        route = next;
        pathMode = true;
        showInfo();
        if (routeListener != null) routeListener.onRouteChanged(route);
        ui.message("已导入 " + route.length() + " 个点");
    }

    public void applyFavorite(JSONObject favorite) throws Exception {
        pathMode = false;
        fields.set(R.id.latEdit, favorite.getDouble("latitude"));
        fields.set(R.id.lonEdit, favorite.getDouble("longitude"));
        fields.set(R.id.altEdit, favorite.isNull("altitude") ? null : favorite.getDouble("altitude"));
        fields.set(R.id.accuracyEdit, favorite.isNull("accuracy") ? null : favorite.getDouble("accuracy"));
        showInfo();
    }

    public void apply() throws Exception {
        ui.service().applyTarget(payload().toString());
    }

    /** Map edits push only geometry so an unrelated invalid field cannot veto a live switch. */
    public void applyDraft() throws Exception {
        JSONObject data = new JSONObject();
        data.put("mode", pathMode ? "path" : "point");
        if (pathMode) {
            if (route.length() < MIN_ROUTE_POINTS) return;
            data.put("route", route);
        } else {
            data.put("latitude", fields.number(R.id.latEdit));
            data.put("longitude", fields.number(R.id.lonEdit));
        }
        ui.service().applyTarget(data.toString());
    }

    public JSONObject payload() throws Exception {
        JSONObject data = new JSONObject();
        data.put("mode", pathMode ? "path" : "point");
        data.put("latitude", fields.number(R.id.latEdit));
        data.put("longitude", fields.number(R.id.lonEdit));
        data.put("altitude", nullable(fields.optional(R.id.altEdit)));
        data.put("accuracy", nullable(fields.optional(R.id.accuracyEdit)));
        data.put("speed", nullable(fields.optional(R.id.speedEdit)));
        data.put("bearing", nullable(fields.optional(R.id.bearingEdit)));
        data.put("rate", fields.integer(R.id.rateEdit));
        data.put("quality", fields.integer(R.id.qualityEdit));
        data.put("satellites", fields.integer(R.id.satellitesEdit));
        data.put("jitterMeters", fields.number(R.id.jitterEdit));
        data.put("jitterAccuracy", fields.checked(R.id.jitterAccuracyCheck));
        data.put("accuracyMin", fields.number(R.id.accuracyMinEdit));
        data.put("accuracyMax", fields.number(R.id.accuracyMaxEdit));
        data.put("jitterAltitude", fields.checked(R.id.jitterAltitudeCheck));
        data.put("altitudeMin", fields.number(R.id.altitudeMinEdit));
        data.put("altitudeMax", fields.number(R.id.altitudeMaxEdit));
        data.put("pathSpeedKmh", fields.number(R.id.pathSpeedEdit));
        data.put("pathLoop", fields.checked(R.id.pathLoopCheck));
        if (pathMode) {
            if (route.length() < MIN_ROUTE_POINTS) throw new IllegalArgumentException("路径至少需要两个点");
            data.put("route", route);
        }
        return data;
    }

    private static Object nullable(Double value) { return value == null ? JSONObject.NULL : value; }

    public double latitude() throws Exception { return fields.number(R.id.latEdit); }

    public double longitude() throws Exception { return fields.number(R.id.lonEdit); }

    public void refresh(JSONObject target) throws Exception {
        if (!initialized) initialize(target);
        if (!pathMode) return;
        String timing = target.optBoolean("pathTimed")
                ? String.format(Locale.US, " · 总时长 %.1f s", target.optDouble("pathDurationSeconds")) : "";
        fields.set(R.id.infoLine, String.format(Locale.US,
                "草稿 %d 点 · 已应用 %d 点 · 已走 %.0f m / %.2f km%s",
                route.length(), target.optInt("routePoints"), target.optDouble("pathTravelledMeters"),
                target.optDouble("routeLengthMeters") / METERS_PER_KILOMETER, timing));
    }

    private void initialize(JSONObject target) throws Exception {
        copy(target, "latitude", R.id.latEdit);
        copy(target, "longitude", R.id.lonEdit);
        copy(target, "altitude", R.id.altEdit);
        copy(target, "accuracy", R.id.accuracyEdit);
        copy(target, "speed", R.id.speedEdit);
        copy(target, "bearing", R.id.bearingEdit);
        copy(target, "jitterMeters", R.id.jitterEdit);
        copy(target, "quality", R.id.qualityEdit);
        copy(target, "satellites", R.id.satellitesEdit);
        copy(target, "accuracyMin", R.id.accuracyMinEdit);
        copy(target, "accuracyMax", R.id.accuracyMaxEdit);
        copy(target, "altitudeMin", R.id.altitudeMinEdit);
        copy(target, "altitudeMax", R.id.altitudeMaxEdit);
        fields.check(R.id.jitterAccuracyCheck, target.getBoolean("jitterAccuracy"));
        fields.check(R.id.jitterAltitudeCheck, target.getBoolean("jitterAltitude"));
        fields.check(R.id.pathLoopCheck, target.getBoolean("pathLoop"));
        fields.set(R.id.pathSpeedEdit, target.getDouble("pathSpeedMps") * 3.6);
        fields.set(R.id.rateEdit, target.getInt("rate"));
        pathMode = "path".equals(target.getString("mode"));
        route = target.getJSONArray("route");
        initialized = true;
        showInfo();
        if (route.length() > 0 && routeListener != null) routeListener.onRouteChanged(route);
    }

    private void copy(JSONObject source, String key, int id) throws Exception {
        fields.set(id, source.isNull(key) ? null : source.get(key));
    }

    private void showInfo() {
        String text;
        if (pathMode) {
            text = "路径 " + route.length() + " 点" + (timedRoute() ? " · 按时间戳回放" : " · 匀速");
        } else {
            String lat = fields.text(R.id.latEdit);
            String lon = fields.text(R.id.lonEdit);
            text = lat.isEmpty() || lon.isEmpty() ? "点击地图选择位置" : "目标 " + lat + ", " + lon;
        }
        fields.set(R.id.infoLine, text);
        ui.activity.findViewById(R.id.pathRow).setVisibility(pathMode
                ? android.view.View.VISIBLE : android.view.View.GONE);
        styleSegment(R.id.segPoint, !pathMode);
        styleSegment(R.id.segPath, pathMode);
    }

    private void styleSegment(int id, boolean active) {
        android.widget.TextView view = ui.activity.findViewById(id);
        view.setBackgroundResource(active ? R.drawable.bg_seg_on : android.R.color.transparent);
        view.setTextColor(ui.activity.getColor(active ? R.color.accent : R.color.muted));
        view.setTypeface(null, active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
    }

    private boolean timedRoute() {
        if (route.length() < MIN_ROUTE_POINTS) return false;
        double previous = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < route.length(); index++) {
            JSONObject point = route.optJSONObject(index);
            if (point == null || !point.has("time")) return false;
            double time = point.optDouble("time", Double.NaN);
            if (!Double.isFinite(time) || time < previous) return false;
            previous = time;
        }
        return previous > route.optJSONObject(0).optDouble("time");
    }
}
