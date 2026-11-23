package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.AccessoryProfile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Serialized configuration contains identity metadata and favorites, never MFi private material. */
final class ConfigJson {
    private ConfigJson() {}

    static void read(AppConfig config, String raw) throws JSONException {
        JSONObject json = new JSONObject(raw);
        config.randomizeIdentity = json.optBoolean("randomizeIdentity", true);
        config.randomizeAdapter = json.optBoolean("randomizeAdapter", true);
        config.rotateIdentityOnStart = json.optBoolean("rotateIdentityOnStart", true);
        config.useAdapterOverride = json.optBoolean("useAdapterOverride", true);
        JSONObject profile = json.optJSONObject("accessory");
        if (profile != null) config.accessory = readProfile(profile);
        JSONArray favorites = json.optJSONArray("favorites");
        if (favorites == null) return;
        for (int index = 0; index < favorites.length(); index++) {
            JSONObject item = favorites.getJSONObject(index);
            double lat = item.getDouble("latitude");
            double lon = item.getDouble("longitude");
            validatePoint(lat, lon);
            config.favorites.add(new Favorite(item.getString("id"), item.getString("name"), lat, lon,
                    optionalNumber(item, "altitude"), optionalNumber(item, "accuracy")));
        }
    }

    static String write(AppConfig config) throws JSONException {
        JSONObject json = new JSONObject();
        json.put("randomizeIdentity", config.randomizeIdentity);
        json.put("randomizeAdapter", config.randomizeAdapter);
        json.put("rotateIdentityOnStart", config.rotateIdentityOnStart);
        json.put("useAdapterOverride", config.useAdapterOverride);
        json.put("accessory", profile(config.accessory));
        JSONArray favorites = new JSONArray();
        for (Favorite favorite : config.favorites) {
            JSONObject item = new JSONObject();
            item.put("id", favorite.id);
            item.put("name", favorite.name);
            item.put("latitude", favorite.latitude);
            item.put("longitude", favorite.longitude);
            item.put("altitude", favorite.altitude);
            item.put("accuracy", favorite.accuracy);
            favorites.put(item);
        }
        json.put("favorites", favorites);
        return json.toString();
    }

    private static AccessoryProfile readProfile(JSONObject json) throws JSONException {
        AccessoryProfile profile = AccessoryProfile.defaults();
        profile.name = json.optString("name", profile.name);
        profile.model = json.optString("model", profile.model);
        profile.manufacturer = json.optString("manufacturer", profile.manufacturer);
        profile.serial = json.optString("serial", profile.serial);
        profile.firmware = json.optString("firmware", profile.firmware);
        profile.hardware = json.optString("hardware", profile.hardware);
        profile.adapterAddress = json.optString("adapterAddress", "");
        validateProfile(profile);
        return profile;
    }

    private static JSONObject profile(AccessoryProfile profile) throws JSONException {
        JSONObject result = new JSONObject();
        result.put("name", profile.name);
        result.put("model", profile.model);
        result.put("manufacturer", profile.manufacturer);
        result.put("serial", profile.serial);
        result.put("firmware", profile.firmware);
        result.put("hardware", profile.hardware);
        result.put("adapterAddress", profile.adapterAddress);
        return result;
    }

    static void validateProfile(AccessoryProfile profile) {
        String[] values = {profile.name, profile.model, profile.manufacturer, profile.serial,
                profile.firmware, profile.hardware};
        for (String value : values) {
            if (value == null || value.trim().isEmpty() || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("配件名称、型号、制造商、序号和版本不可为空或包含 NUL");
            }
        }
        String address = profile.adapterAddress;
        if (address != null && !address.isEmpty() &&
                !address.matches("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")) {
            throw new IllegalArgumentException("上报蓝牙地址格式应为 AA:BB:CC:DD:EE:FF");
        }
    }

    static void validatePoint(double lat, double lon) {
        if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < -90 || lat > 90 || lon < -180 || lon > 180) {
            throw new IllegalArgumentException("收藏点经纬度无效");
        }
    }

    private static Double optionalNumber(JSONObject item, String key) throws JSONException {
        if (!item.has(key) || item.isNull(key)) return null;
        double value = item.getDouble(key);
        if (!Double.isFinite(value)) throw new IllegalArgumentException(key + " 必须为有限数值");
        return value;
    }
}
