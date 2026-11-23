package com.iap2loc.android.protocol;

/** WGS-84 geometry plus the GCJ-02 conversion used by the original web console. */
public final class Geo {
    private static final double EARTH_RADIUS_METERS = 6_371_000.0;
    private static final double METRES_PER_DEGREE = 111_320.0;
    private static final double PI = Math.PI;
    private static final double AXIS = 6_378_245.0;
    private static final double EE = 0.00669342162296594323;

    private Geo() {}

    public static final class Point {
        public final double latitude;
        public final double longitude;
        public final Double altitude;

        public Point(double latitude, double longitude) {
            this(latitude, longitude, null);
        }

        public Point(double latitude, double longitude, Double altitude) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.altitude = altitude;
        }
    }

    public static double distance(Point a, Point b) {
        double lat1 = a.latitude * PI / 180;
        double lat2 = b.latitude * PI / 180;
        double dLat = (b.latitude - a.latitude) * PI / 180;
        double dLon = (b.longitude - a.longitude) * PI / 180;
        double sinLat = Math.sin(dLat / 2);
        double sinLon = Math.sin(dLon / 2);
        double h = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    public static double bearing(Point a, Point b) {
        double lat1 = a.latitude * PI / 180;
        double lat2 = b.latitude * PI / 180;
        double dLon = (b.longitude - a.longitude) * PI / 180;
        double y = Math.sin(dLon) * Math.cos(lat2);
        double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
        double degrees = Math.atan2(y, x) * 180 / PI;
        return (degrees + 360) % 360;
    }

    public static Point offset(Point origin, double distanceMeters, double angleRadians) {
        double dLat = distanceMeters * Math.cos(angleRadians) / METRES_PER_DEGREE;
        double dLon = distanceMeters * Math.sin(angleRadians) /
                (METRES_PER_DEGREE * Math.max(0.01, Math.cos(origin.latitude * PI / 180)));
        return new Point(origin.latitude + dLat, origin.longitude + dLon, origin.altitude);
    }

    public static double normalizeLatitude(double latitude) {
        return Double.isFinite(latitude) ? Math.max(-90, Math.min(90, latitude)) : 0;
    }

    public static double normalizeLongitude(double longitude) {
        if (!Double.isFinite(longitude)) return 0;
        double wrapped = longitude % 360;
        if (wrapped > 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    public static Point normalize(Point point) {
        return new Point(normalizeLatitude(point.latitude), normalizeLongitude(point.longitude), point.altitude);
    }

    public static boolean outOfChina(double latitude, double longitude) {
        return !(longitude > 73.66 && longitude < 135.05 && latitude > 3.86 && latitude < 53.55);
    }

    private static double transformLat(double x, double y) {
        double r = -100 + 2 * x + 3 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
        r += (20 * Math.sin(y * PI) + 40 * Math.sin(y / 3 * PI)) * 2 / 3;
        r += (160 * Math.sin(y / 12 * PI) + 320 * Math.sin(y * PI / 30)) * 2 / 3;
        return r;
    }

    private static double transformLon(double x, double y) {
        double r = 300 + x + 2 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x));
        r += (20 * Math.sin(6 * x * PI) + 20 * Math.sin(2 * x * PI)) * 2 / 3;
        r += (20 * Math.sin(x * PI) + 40 * Math.sin(x / 3 * PI)) * 2 / 3;
        r += (150 * Math.sin(x / 12 * PI) + 300 * Math.sin(x / 30 * PI)) * 2 / 3;
        return r;
    }

    /** Converts a WGS-84 coordinate to GCJ-02 (as used by Amap tiles in China). */
    public static double[] wgs84ToGcj02(double latitude, double longitude) {
        if (outOfChina(latitude, longitude)) return new double[]{latitude, longitude};
        double dLat = transformLat(longitude - 105, latitude - 35);
        double dLon = transformLon(longitude - 105, latitude - 35);
        double radLat = latitude / 180 * PI;
        double magic = Math.sin(radLat);
        magic = 1 - EE * magic * magic;
        double sqrtMagic = Math.sqrt(magic);
        dLat = (dLat * 180) / ((AXIS * (1 - EE)) / (magic * sqrtMagic) * PI);
        dLon = (dLon * 180) / (AXIS / sqrtMagic * Math.cos(radLat) * PI);
        return new double[]{latitude + dLat, longitude + dLon};
    }

    /** Inverse GCJ-02 conversion, solved iteratively to match the original console. */
    public static double[] gcj02ToWgs84(double latitude, double longitude) {
        double wLat = latitude;
        double wLon = longitude;
        for (int i = 0; i < 8; i++) {
            double[] guessed = wgs84ToGcj02(wLat, wLon);
            wLat += latitude - guessed[0];
            wLon += longitude - guessed[1];
        }
        return new double[]{wLat, wLon};
    }
}
