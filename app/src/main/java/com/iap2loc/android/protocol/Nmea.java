package com.iap2loc.android.protocol;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Encodes a fix into the GPGGA/GPRMC sentences carried by 0xFFFB LocationInformation. */
public final class Nmea {
    private static final double KNOTS_PER_METER_PER_SECOND = 1.94384449;
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HHmmss", Locale.US);
    private static final SimpleDateFormat DATE = new SimpleDateFormat("ddMMyy", Locale.US);

    static {
        TIME.setTimeZone(TimeZone.getTimeZone("UTC"));
        DATE.setTimeZone(TimeZone.getTimeZone("UTC"));
    }

    private Nmea() {}

    public static String encode(LocationTarget.Fix fix, Date utc) {
        if (!Double.isFinite(fix.latitude) || fix.latitude < -90 || fix.latitude > 90) {
            throw new IllegalArgumentException("latitude must be within -90..90");
        }
        if (!Double.isFinite(fix.longitude) || fix.longitude < -180 || fix.longitude > 180) {
            throw new IllegalArgumentException("longitude must be within -180..180");
        }

        String time;
        String date;
        synchronized (TIME) {
            time = TIME.format(utc) + ".00";
            date = DATE.format(utc);
        }
        String[] lat = coordinate(fix.latitude, true);
        String[] lon = coordinate(fix.longitude, false);

        double hdop = fix.accuracyMeters != null && Double.isFinite(fix.accuracyMeters)
                && fix.accuracyMeters > 0
                ? Math.max(0.5, Math.min(50.0, fix.accuracyMeters / 5.0))
                : 1.0;
        String altitude = fix.altitudeMeters != null && Double.isFinite(fix.altitudeMeters)
                ? String.format(Locale.US, "%.1f", fix.altitudeMeters)
                : "0.0";

        StringBuilder payload = new StringBuilder();
        String gga = "GPGGA," + time + "," + lat[0] + "," + lat[1] + "," +
                lon[0] + "," + lon[1] + "," +
                Math.max(0, Math.min(8, fix.quality)) + "," +
                String.format(Locale.US, "%02d", Math.max(0, Math.min(99, fix.satellites))) + "," +
                String.format(Locale.US, "%.1f", hdop) + "," + altitude + ",M,0.0,M,,";
        append(payload, gga);

        String speed = fix.speedMetersPerSecond != null && Double.isFinite(fix.speedMetersPerSecond)
                && fix.speedMetersPerSecond >= 0
                ? String.format(Locale.US, "%.2f", fix.speedMetersPerSecond * KNOTS_PER_METER_PER_SECOND)
                : "0.00";
        String course = fix.bearingDegrees != null && Double.isFinite(fix.bearingDegrees)
                ? String.format(Locale.US, "%.2f", fix.bearingDegrees)
                : "";

        String rmc = "GPRMC," + time + ",A," + lat[0] + "," + lat[1] + "," +
                lon[0] + "," + lon[1] + "," + speed + "," + course + "," + date + ",,";
        append(payload, rmc);
        return payload.toString();
    }

    private static void append(StringBuilder payload, String body) {
        payload.append('$').append(body).append('*').append(checksum(body)).append("\r\n");
    }

    private static String[] coordinate(double value, boolean latitude) {
        double absolute = Math.abs(value);
        int degrees = (int) absolute;
        double minutes = (absolute - degrees) * 60.0;
        if (minutes >= 60.0 - 0.00005) {
            degrees += 1;
            minutes = 0.0;
        }
        String degreeText = latitude
                ? String.format(Locale.US, "%02d", degrees)
                : String.format(Locale.US, "%03d", degrees);
        String hemisphere = latitude ? (value >= 0 ? "N" : "S") : (value >= 0 ? "E" : "W");
        // Minutes always carry two integer digits ("%07.4f"), matching upstream "00.0000";
        // a single-digit minute would shift the degree digits and move the fix by ~100°.
        return new String[]{degreeText + String.format(Locale.US, "%07.4f", minutes), hemisphere};
    }

    private static String checksum(String body) {
        int value = 0;
        for (int i = 0; i < body.length(); i++) value ^= body.charAt(i);
        return String.format(Locale.US, "%02X", value);
    }
}
