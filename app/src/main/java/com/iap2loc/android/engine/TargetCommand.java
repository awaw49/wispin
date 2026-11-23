package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.LocationTarget;
import com.iap2loc.android.protocol.RoutePath;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** JSON boundary shared by native and web controls. Invalid input cannot report success. */
public final class TargetCommand {
    private static final double SECONDS_TO_MILLIS = 1000;
    private static final double KMH_TO_MPS = 3.6;
    private static final Bounds LATITUDE = new Bounds(-90, 90);
    private static final Bounds LONGITUDE = new Bounds(-180, 180);
    private static final Bounds BEARING = new Bounds(0, 360);
    private static final Bounds QUALITY = new Bounds(0, 8);
    private static final Bounds SATELLITES = new Bounds(0, 99);
    private static final int MIN_RATE_MILLIS = 10;
    private static final Bounds RATE = new Bounds(MIN_RATE_MILLIS, Integer.MAX_VALUE);
    private static final Bounds PATH_SPEED_KMH = new Bounds(0.36, 180);
    private static final class Bounds {
        final double min;
        final double max;
        Bounds(double min, double max) { this.min = min; this.max = max; }
    }
    private final LocationTarget.Update update;
    private final Integer rate;

    private TargetCommand(LocationTarget.Update update, Integer rate) {
        this.update = update;
        this.rate = rate;
    }

    public static TargetCommand parse(String raw) {
        try {
            JSONObject data = new JSONObject(raw);
            LocationTarget.Update update = fields(data);
            if (data.has("route")) update.route(route(data.getJSONArray("route")));
            Integer rate = data.has("rate") ? integerRange(data, "rate", RATE) : null;
            if (rate != null && rate < MIN_RATE_MILLIS) throw new IllegalArgumentException("上报周期至少为 10 ms");
            return new TargetCommand(update, rate);
        } catch (JSONException ex) {
            throw new IllegalArgumentException("目标参数格式错误：" + ex.getMessage(), ex);
        }
    }

    public void apply(SessionManager manager) {
        int points = update.route == null ? manager.target.snapshot().routePoints : update.route.size();
        if (update.mode == LocationTarget.Mode.PATH && points < 2) {
            throw new IllegalArgumentException("路径至少需要两个点");
        }
        manager.applyTarget(update, rate);
    }

    private static LocationTarget.Update fields(JSONObject data) throws JSONException {
        LocationTarget.Update update = new LocationTarget.Update();
        if (data.has("mode")) {
            String mode = data.getString("mode");
            if (!"point".equals(mode) && !"path".equals(mode)) throw new IllegalArgumentException("定位模式无效");
            update.mode("path".equals(mode) ? LocationTarget.Mode.PATH : LocationTarget.Mode.POINT);
        }
        update.latitude = range(data, "latitude", LATITUDE);
        update.longitude = range(data, "longitude", LONGITUDE);
        update.clearAltitude = data.has("altitude") && data.isNull("altitude");
        update.clearAccuracy = data.has("accuracy") && data.isNull("accuracy");
        update.clearSpeed = data.has("speed") && data.isNull("speed");
        update.clearBearing = data.has("bearing") && data.isNull("bearing");
        update.altitude = number(data, "altitude");
        update.accuracy = nonNegative(data, "accuracy");
        update.speed = nonNegative(data, "speed");
        update.bearing = range(data, "bearing", BEARING);
        update.jitterMeters = nonNegative(data, "jitterMeters");
        update.accuracyMin = nonNegative(data, "accuracyMin");
        update.accuracyMax = nonNegative(data, "accuracyMax");
        update.altitudeMin = number(data, "altitudeMin");
        update.altitudeMax = number(data, "altitudeMax");
        ordered(update.accuracyMin, update.accuracyMax, "精度");
        ordered(update.altitudeMin, update.altitudeMax, "高度");
        if (data.has("jitterAccuracy")) update.jitterAccuracy = data.getBoolean("jitterAccuracy");
        if (data.has("jitterAltitude")) update.jitterAltitude = data.getBoolean("jitterAltitude");
        if (data.has("quality")) update.quality = integerRange(data, "quality", QUALITY);
        if (data.has("satellites")) update.satellites = integerRange(data, "satellites", SATELLITES);
        Double speed = range(data, "pathSpeedKmh", PATH_SPEED_KMH);
        if (speed != null) update.pathSpeedMps = speed / KMH_TO_MPS;
        if (data.has("pathLoop")) update.pathLoop = data.getBoolean("pathLoop");
        return update;
    }

    private static List<RoutePath.Vertex> route(JSONArray points) throws JSONException {
        if (points.length() > LocationTarget.MAX_ROUTE_POINTS) {
            throw new IllegalArgumentException("路径点数超出 " + LocationTarget.MAX_ROUTE_POINTS + "，请先抽稀或分段");
        }
        List<RoutePath.Vertex> result = new ArrayList<>();
        for (int index = 0; index < points.length(); index++) {
            JSONObject point = points.getJSONObject(index);
            Double lat = range(point, "lat", LATITUDE);
            Double lon = range(point, "lon", LONGITUDE);
            if (lat == null || lon == null) throw new IllegalArgumentException("路径点缺少 lat/lon");
            Double time = number(point, "time");
            result.add(new RoutePath.Vertex(new Geo.Point(lat, lon, number(point, "alt")),
                    time == null ? null : time / SECONDS_TO_MILLIS));
        }
        return result;
    }

    private static Double number(JSONObject data, String key) throws JSONException {
        if (!data.has(key) || data.isNull(key)) return null;
        double result = data.getDouble(key);
        if (!Double.isFinite(result)) throw new IllegalArgumentException(key + " 必须为有限数值");
        return result;
    }

    private static Double nonNegative(JSONObject data, String key) throws JSONException {
        Double result = number(data, key);
        if (result != null && result < 0) throw new IllegalArgumentException(key + " 不能小于 0");
        return result;
    }

    private static Double range(JSONObject data, String key, Bounds bounds) throws JSONException {
        Double result = number(data, key);
        if (result != null && (result < bounds.min || result > bounds.max)) throw new IllegalArgumentException(key + " 超出范围");
        return result;
    }

    private static int integerRange(JSONObject data, String key, Bounds bounds) throws JSONException {
        double value = data.getDouble(key);
        if (!Double.isFinite(value) || value != Math.rint(value) || value < bounds.min || value > bounds.max) {
            throw new IllegalArgumentException(key + " 必须为 " + bounds.min + " 至 " + bounds.max + " 的整数");
        }
        return (int) value;
    }

    private static void ordered(Double min, Double max, String label) {
        if (min != null && max != null && min > max) throw new IllegalArgumentException(label + "下限大于上限");
    }
}
