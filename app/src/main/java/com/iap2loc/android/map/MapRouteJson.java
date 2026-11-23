package com.iap2loc.android.map;

import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** JSON boundary for the native map result and its display-only WebView coordinates. */
public final class MapRouteJson {
    private static final double MILLIS_PER_SECOND = 1000;

    private MapRouteJson() {}

    public static RouteEditor parse(String raw) throws JSONException {
        JSONArray array = raw == null ? new JSONArray() : new JSONArray(raw);
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject item = array.getJSONObject(index);
            Geo.Point point = new Geo.Point(item.getDouble("lat"), item.getDouble("lon"),
                    optional(item, "alt"));
            Double millis = optional(item, "time");
            vertices.add(new RoutePath.Vertex(point,
                    millis == null ? null : millis / MILLIS_PER_SECOND));
        }
        return new RouteEditor(vertices);
    }

    public static String encode(RouteEditor editor, String datum) throws JSONException {
        JSONArray array = new JSONArray();
        for (RoutePath.Vertex vertex : editor.points()) {
            Geo.Point point = vertex.point;
            double[] coordinates = "gcj".equals(datum)
                    ? Geo.wgs84ToGcj02(point.latitude, point.longitude)
                    : new double[]{point.latitude, point.longitude};
            JSONObject item = new JSONObject();
            item.put("lat", coordinates[0]);
            item.put("lon", coordinates[1]);
            if (point.altitude != null) item.put("alt", point.altitude);
            if (vertex.timeSeconds != null) item.put("time", vertex.timeSeconds * MILLIS_PER_SECOND);
            array.put(item);
        }
        return array.toString();
    }

    private static Double optional(JSONObject object, String name) throws JSONException {
        return object.has(name) && !object.isNull(name) ? object.getDouble(name) : null;
    }
}
