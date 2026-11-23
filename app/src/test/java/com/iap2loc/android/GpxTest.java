package com.iap2loc.android;

import com.iap2loc.android.engine.Gpx;
import com.iap2loc.android.protocol.RoutePath;
import com.iap2loc.android.util.AppLog;

import org.junit.Before;
import org.junit.Test;
import org.kxml2.io.KXmlParser;

import java.util.List;

import static org.junit.Assert.*;

/** Tests the actual XML token parser using the same XmlPullParser contract as Android. */
public final class GpxTest {
    private static final String START = "2026-11-23T00:00:";

    @Before public void clearLogs() { AppLog.clear(); }

    @Test public void readsTrackRouteAndWaypointCoordinatesAndElevation() throws Exception {
        String xml = "<gpx xmlns='http://www.topografix.com/GPX/1/1'><trk><trkseg>" +
                "<trkpt lat='1.1234567' lon='2'><ele>3.5</ele></trkpt>" +
                "</trkseg></trk><rte><rtept lat='3' lon='4'/></rte>" +
                "<wpt lat='5' lon='6'/></gpx>";
        List<RoutePath.Vertex> points = parse(xml);
        assertEquals(3, points.size());
        assertEquals(1.123457, points.get(0).point.latitude, 0);
        assertEquals(3.5, points.get(0).point.altitude, 0);
        assertEquals(6, points.get(2).point.longitude, 0);
        assertNull(points.get(0).timeSeconds);
        assertTrue(AppLog.text(10).contains("没有时间戳"));
        assertThrows(UnsupportedOperationException.class, points::clear);
    }

    @Test public void retainsStationaryRecordingPeriodAndAltitude() throws Exception {
        String xml = "<gpx>" + point(1, "00", 3) + point(1, "10", 4) + point(1.001, "20", 5) + "</gpx>";
        List<RoutePath.Vertex> points = parse(xml);
        assertEquals(3, points.size());
        RoutePath route = new RoutePath(points);
        assertTrue(route.hasTimeline());
        assertEquals(20, route.durationSeconds(), 0);
        RoutePath.Sample waiting = route.atTime(5, false);
        assertEquals(1, waiting.point.latitude, 0);
        assertEquals(0, waiting.speedMetersPerSecond, 0);
        assertEquals(5, points.get(2).point.altitude, 0);
        assertTrue(route.atTime(15, false).point.latitude > 1);
    }

    @Test public void missingOrReversedTimeExplicitlySelectsConstantSpeed() throws Exception {
        List<RoutePath.Vertex> missing = parse("<gpx>" + point(1, "00", 1) +
                "<wpt lat='2' lon='2'/></gpx>");
        assertNull(missing.get(0).timeSeconds);
        assertTrue(AppLog.text(10).contains("切换为匀速"));
        AppLog.clear();
        List<RoutePath.Vertex> reversed = parse("<gpx>" + point(1, "20", 1) + point(2, "10", 2) + "</gpx>");
        assertNull(reversed.get(1).timeSeconds);
        assertTrue(AppLog.text(10).contains("时间戳倒序"));
    }

    @Test public void rejectsInvalidCoordinatesTimeAltitudeAndXml() {
        String[] invalid = {
                "<gpx><wpt lat='nan' lon='2'/><wpt lat='3' lon='4'/></gpx>",
                "<gpx><wpt lat='91' lon='2'/><wpt lat='3' lon='4'/></gpx>",
                "<gpx><wpt lat='1' lon='2'><time>bad</time></wpt><wpt lat='3' lon='4'/></gpx>",
                "<gpx><wpt lat='1' lon='2'><ele>Infinity</ele></wpt><wpt lat='3' lon='4'/></gpx>",
                "<gpx><wpt lat='1' lon='2'><ele><x>1</x></ele></wpt><wpt lat='3' lon='4'/></gpx>",
                "<gpx><wpt lat='1' lon='2'></gpx>",
                "<anything><wpt lat='1' lon='2'/><wpt lat='3' lon='4'/></anything>"
        };
        for (String xml : invalid) assertThrows(Exception.class, () -> parse(xml));
    }

    @Test public void rejectsDoctypeAndExternalEntityBeforeReadingIt() {
        String xml = "<!DOCTYPE gpx [<!ENTITY xxe SYSTEM 'file:///no-such-file'>]>" +
                "<gpx><wpt lat='1' lon='2'><ele>&xxe;</ele></wpt><wpt lat='3' lon='4'/></gpx>";
        Exception failure = assertThrows(Exception.class, () -> parse(xml));
        assertTrue(failure.getMessage().contains("DOCTYPE"));
    }

    @Test public void retainsNumericXmlEntitiesWithoutAllowingDtd() throws Exception {
        List<RoutePath.Vertex> points = parse("<gpx><wpt lat='1' lon='2'><ele>&#51;</ele></wpt>" +
                "<wpt lat='2' lon='3'/></gpx>");
        assertEquals(3, points.get(0).point.altitude, 0);
    }

    @Test public void reportsDecimationAndRetainsBothEndpointsAndMetadata() throws Exception {
        StringBuilder xml = new StringBuilder("<gpx>");
        final int count = 5001;
        for (int index = 0; index < count; index++) {
            xml.append("<wpt lat='").append(index / 10000.0).append("' lon='2'><ele>")
                    .append(index).append("</ele><time>2026-11-23T")
                    .append(String.format(java.util.Locale.ROOT, "%02d:%02d:%02dZ", index / 3600,
                            (index / 60) % 60, index % 60)).append("</time></wpt>");
        }
        List<RoutePath.Vertex> points = parse(xml.append("</gpx>").toString());
        assertEquals(5000, points.size());
        assertEquals(0, points.get(0).point.altitude, 0);
        assertEquals(5000, points.get(4999).point.altitude, 0);
        assertEquals(5000, new RoutePath(points).durationSeconds(), 0);
        assertTrue(AppLog.text(10).contains("5001 点"));
        assertTrue(AppLog.text(10).contains("抽稀为 5000"));
    }

    @Test public void supportsUnzonedUtcTimesWithAnExplicitDiagnostic() throws Exception {
        List<RoutePath.Vertex> points = parse("<gpx>" +
                "<wpt lat='1' lon='2'><time>2026-11-23T00:00:00</time></wpt>" +
                "<wpt lat='2' lon='3'><time>2026-11-23T00:00:10</time></wpt></gpx>");
        assertEquals(10, new RoutePath(points).durationSeconds(), 0);
        assertTrue(AppLog.text(10).contains("按 UTC 解析"));
    }

    private static List<RoutePath.Vertex> parse(String xml) throws Exception {
        return Gpx.parse(xml, new KXmlParser());
    }

    private static String point(double lat, String second, double altitude) {
        return "<wpt lat='" + lat + "' lon='2'><ele>" + altitude + "</ele><time>" +
                START + second + "Z</time></wpt>";
    }
}
