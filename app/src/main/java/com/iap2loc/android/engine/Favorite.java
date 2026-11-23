package com.iap2loc.android.engine;

public final class Favorite {
    public String id;
    public String name;
    public double latitude;
    public double longitude;
    public Double altitude;
    public Double accuracy;

    public Favorite() {}

    public Favorite(String id, String name, double latitude, double longitude,
                    Double altitude, Double accuracy) {
        this.id = id;
        this.name = name;
        this.latitude = latitude;
        this.longitude = longitude;
        this.altitude = altitude;
        this.accuracy = accuracy;
    }
}
