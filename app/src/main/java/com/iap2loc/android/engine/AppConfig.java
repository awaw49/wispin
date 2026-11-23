package com.iap2loc.android.engine;

import android.content.Context;
import android.content.SharedPreferences;

import com.iap2loc.android.protocol.AccessoryProfile;
import com.iap2loc.android.util.AppLog;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists accessory identity and favourite points, equivalent to iap2loc.json in the CLI. */
public final class AppConfig {
    private static final String PREF = "iap2loc.json";
    private static final String KEY = "config";
    private static final int MAX_FAVORITES = 200;
    private final SharedPreferences preferences;

    public boolean randomizeIdentity = true;
    public boolean randomizeAdapter = true;
    public AccessoryProfile accessory = AccessoryProfile.defaults();
    public final List<Favorite> favorites = new ArrayList<>();

    public boolean rotateIdentityOnStart = true;
    public boolean useAdapterOverride = true;

    private AppConfig(Context context) {
        preferences = context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static AppConfig load(Context context) {
        AppConfig config = new AppConfig(context);
        String raw = config.preferences.getString(KEY, null);
        if (raw == null || raw.isEmpty()) return config;
        try {
            ConfigJson.read(config, raw);
        } catch (JSONException ex) {
            throw new IllegalStateException("保存的配置无法解析，请检查应用数据：" + ex.getMessage(), ex);
        }
        return config;
    }

    public synchronized void save() {
        try {
            String raw = ConfigJson.write(this);
            if (!preferences.edit().putString(KEY, raw).commit()) {
                throw new IllegalStateException("配置写入失败");
            }
        } catch (JSONException ex) {
            throw new IllegalStateException("配置序列化失败：" + ex.getMessage(), ex);
        }
    }

    public synchronized void setProfile(AccessoryProfile profile) {
        ConfigJson.validateProfile(profile);
        accessory = profile.copy();
        save();
    }

    public synchronized AccessoryProfile effectiveProfile() {
        if (randomizeAdapter && useAdapterOverride) return accessory.copy();
        AccessoryProfile profile = accessory.copy();
        profile.adapterAddress = null;
        return profile;
    }

    public synchronized void prepareForRun(byte[] localAdapter) {
        if (randomizeIdentity && rotateIdentityOnStart) {
            accessory = accessory.rotate();
        }
        if (randomizeAdapter && useAdapterOverride && accessory.adapterOverride() == null) {
            accessory.adapterAddress = AccessoryProfile.randomAdapterAddress(localAdapter);
        }
        AppLog.info("配件身份 " + accessory.serial + "，蓝牙地址 " +
                (effectiveProfile().adapterAddress == null ? "本机" : effectiveProfile().adapterAddress));
        save();
    }

    public synchronized AccessoryProfile rotateIdentity(byte[] localAdapter) {
        AccessoryProfile next = accessory.rotate();
        if (randomizeAdapter && useAdapterOverride) {
            next.adapterAddress = AccessoryProfile.randomAdapterAddress(localAdapter);
        }
        accessory = next;
        save();
        return effectiveProfile();
    }

    public synchronized List<Favorite> favoritesSnapshot() {
        return new ArrayList<>(favorites);
    }

    public synchronized Favorite addFavorite(String name, double latitude, double longitude,
                                             Double altitude, Double accuracy) {
        ConfigJson.validatePoint(latitude, longitude);
        String label = name == null || name.trim().isEmpty()
                ? String.format("%.4f, %.4f", latitude, longitude)
                : name.trim();
        Favorite favorite = new Favorite(
                UUID.randomUUID().toString().replace("-", "").substring(0, 8),
                label, latitude, longitude, altitude, accuracy);
        favorites.add(0, favorite);
        while (favorites.size() > MAX_FAVORITES) favorites.remove(favorites.size() - 1);
        save();
        return favorite;
    }

    public synchronized boolean removeFavorite(String id) {
        boolean removed = favorites.removeIf(favorite -> favorite.id.equals(id));
        if (removed) save();
        return removed;
    }
}
