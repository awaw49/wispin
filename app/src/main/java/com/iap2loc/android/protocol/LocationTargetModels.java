package com.iap2loc.android.protocol;

import java.util.List;

/** Public target value types, inherited by LocationTarget to retain its existing API. */
public abstract class LocationTargetModels {
    public enum Mode { POINT, PATH }

    public static final class Fix {
        public final double latitude;
        public final double longitude;
        public final Double altitudeMeters;
        public final Double speedMetersPerSecond;
        public final Double bearingDegrees;
        public final Double accuracyMeters;
        public final int quality;
        public final int satellites;

        public Fix(double lat, double lon, Double alt, Double speed, Double bearing,
                   Double accuracy, int quality, int satellites) {
            this.latitude = lat;
            this.longitude = lon;
            this.altitudeMeters = alt;
            this.speedMetersPerSecond = speed;
            this.bearingDegrees = bearing;
            this.accuracyMeters = accuracy;
            this.quality = quality;
            this.satellites = satellites;
        }
    }

    /** Partial update from the UI; null fields keep their current value. */
    public static final class Update {
        public Mode mode;
        /** null keeps the route; an empty list clears it. */
        public List<RoutePath.Vertex> route;
        public Double pathSpeedMps;
        public Boolean pathLoop;
        public Double latitude;
        public Double longitude;
        public Double altitude;
        public Double accuracy;
        public Double speed;
        public Double bearing;
        public boolean clearAltitude;
        public boolean clearAccuracy;
        public boolean clearSpeed;
        public boolean clearBearing;
        public Double jitterMeters;
        public Boolean jitterAccuracy;
        public Double accuracyMin;
        public Double accuracyMax;
        public Integer quality;
        public Integer satellites;
        public Boolean jitterAltitude;
        public Double altitudeMin;
        public Double altitudeMax;

        public Update mode(Mode value) { mode = value; return this; }
        public Update route(List<RoutePath.Vertex> value) { route = value; return this; }
        public Update latLon(double lat, double lon) { latitude = lat; longitude = lon; return this; }
    }

    public static final class State {
        public List<RoutePath.Vertex> route = java.util.Collections.emptyList();
        public Mode mode;
        public double latitude;
        public double longitude;
        public Double altitude;
        public Double accuracy;
        public Double speed;
        public Double bearing;
        public double jitterMeters;
        public boolean jitterAccuracy;
        public double accuracyMin;
        public double accuracyMax;
        public boolean jitterAltitude;
        public double altitudeMin;
        public double altitudeMax;
        public double resolvedLatitude;
        public double resolvedLongitude;
        public Double resolvedAccuracy;
        public Double resolvedAltitude;
        public int routePoints;
        public double routeLengthMeters;
        public double pathTravelledMeters;
        public double pathSpeedMps;
        public boolean pathLoop;
        public double pathDurationSeconds;
        public boolean pathTimed;
        public int quality;
        public int satellites;
    }

}
