package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.AccessoryProfile;

/** Validates UI/network input before a session starts; malformed values never become defaults. */
public final class RunRequests {
    private static final int MIN_RATE_MS = 10;
    private static final int MAX_TIMEOUT_SECONDS = Integer.MAX_VALUE / 1000;
    private RunRequests() {}

    public static RunRequest validatedCopy(RunRequest source, AccessoryProfile profile) {
        if (source.rate < MIN_RATE_MS) throw new IllegalArgumentException("周期至少 10 ms");
        if (source.connectTimeoutSeconds <= 0 || source.connectTimeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new IllegalArgumentException("连接超时秒数超出有效范围");
        }
        if (source.mac == null || !source.mac.matches("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}")) {
            throw new IllegalArgumentException("蓝牙地址格式应为 AA:BB:CC:DD:EE:FF");
        }
        RunRequest copy = new RunRequest();
        copy.mac = source.mac;
        copy.rate = source.rate;
        copy.label = source.label == null || source.label.trim().isEmpty() ? "iPhone" : source.label.trim();
        copy.connectTimeoutSeconds = source.connectTimeoutSeconds;
        copy.profile = profile.copy();
        return copy;
    }
}
