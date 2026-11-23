package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.AccessoryProfile;
import com.iap2loc.android.protocol.LocationTarget;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;

/** State serialization is independent of session lifecycle and manager locks. */
final class SessionSnapshot {
    private SessionSnapshot() {}

    static JSONObject identity(AccessoryProfile current, String path, boolean material) throws JSONException {
            JSONObject identity = new JSONObject();
            identity.put("name", current.name);
            identity.put("model", current.model);
            identity.put("serial", current.serial);
            identity.put("adapter", current.adapterAddress == null ? JSONObject.NULL : current.adapterAddress);
            identity.put("path", path);
            identity.put("material", material);
            return identity;

    }

    static JSONObject target(LocationTarget.State state) throws JSONException {
            JSONObject targetJson = new JSONObject();
            targetJson.put("mode", state.mode.name().toLowerCase());
            targetJson.put("route", route(state.route));
            targetJson.put("latitude", state.latitude);
            targetJson.put("longitude", state.longitude);
            targetJson.put("altitude", state.altitude == null ? JSONObject.NULL : state.altitude);
            targetJson.put("accuracy", state.accuracy == null ? JSONObject.NULL : state.accuracy);
            targetJson.put("speed", state.speed == null ? JSONObject.NULL : state.speed);
            targetJson.put("bearing", state.bearing == null ? JSONObject.NULL : state.bearing);
            targetJson.put("jitterMeters", state.jitterMeters);
            targetJson.put("jitterAccuracy", state.jitterAccuracy);
            targetJson.put("accuracyMin", state.accuracyMin);
            targetJson.put("accuracyMax", state.accuracyMax);
            targetJson.put("jitterAltitude", state.jitterAltitude);
            targetJson.put("altitudeMin", state.altitudeMin);
            targetJson.put("altitudeMax", state.altitudeMax);
            targetJson.put("resolvedLatitude", state.resolvedLatitude);
            targetJson.put("resolvedLongitude", state.resolvedLongitude);
            targetJson.put("resolvedAccuracy", state.resolvedAccuracy == null ? JSONObject.NULL : state.resolvedAccuracy);
            targetJson.put("resolvedAltitude", state.resolvedAltitude == null ? JSONObject.NULL : state.resolvedAltitude);
            targetJson.put("routePoints", state.routePoints);
            targetJson.put("routeLengthMeters", state.routeLengthMeters);
            targetJson.put("pathTravelledMeters", state.pathTravelledMeters);
            targetJson.put("pathSpeedMps", state.pathSpeedMps);
            targetJson.put("pathLoop", state.pathLoop);
            targetJson.put("pathDurationSeconds", state.pathDurationSeconds);
            targetJson.put("pathTimed", state.pathTimed);
            targetJson.put("quality", state.quality);
            targetJson.put("satellites", state.satellites);
            return targetJson;

    }

    private static JSONArray route(List<com.iap2loc.android.protocol.RoutePath.Vertex> vertices)
            throws JSONException {
        JSONArray result = new JSONArray();
        for (com.iap2loc.android.protocol.RoutePath.Vertex vertex : vertices) {
            JSONObject item = new JSONObject();
            item.put("lat", vertex.point.latitude);
            item.put("lon", vertex.point.longitude);
            item.put("alt", vertex.point.altitude);
            item.put("time", vertex.timeSeconds == null ? null : vertex.timeSeconds * 1000);
            result.put(item);
        }
        return result;
    }

    static JSONArray sessions(List<RunSession> sessions) throws JSONException {
            JSONArray array = new JSONArray();
            for (RunSession session : sessions) {
                JSONObject item = new JSONObject();
                item.put("id", session.id());
                item.put("label", session.label());
                item.put("address", session.request().mac);
                item.put("running", session.running());
                item.put("link", session.linkState());
                item.put("authenticated", session.authenticated());
                item.put("subscribed", session.subscribed());
                item.put("sent", session.sent());
                item.put("error", session.error() == null ? JSONObject.NULL : session.error());
                array.put(item);
            }
            return array;
    }
}
