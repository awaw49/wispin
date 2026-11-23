package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.AccessoryProfile;

public final class RunRequest {
    public String mac;
    public int rate = 250;
    public String label = "iPhone";
    public AccessoryProfile profile = AccessoryProfile.defaults();
    public int connectTimeoutSeconds = 20;
}
