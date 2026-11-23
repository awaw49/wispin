package com.iap2loc.android.engine;

import android.util.Xml;

import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;
import com.iap2loc.android.util.AppLog;

import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** GPX trkpt/rtept/wpt import. No DTD or external entities are processed. */
public final class Gpx {
    private static final int POINT_LIMIT = 5000;
    private static final int MIN_ROUTE_POINTS = 2;
    private static final double COORDINATE_SCALE = 1_000_000;
    private static final double MILLIS_PER_SECOND = 1000;
    private static final double MIN_TIMELINE_SECONDS = 0.5;
    private static final double MAX_LATITUDE = 90;
    private static final double MAX_LONGITUDE = 180;

    private Gpx() {}

    public static List<RoutePath.Vertex> parse(String text) throws Exception {
        return parse(text, Xml.newPullParser());
    }

    public static List<RoutePath.Vertex> parse(String text, XmlPullParser parser) throws Exception {
        if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("GPX 文件为空");
        if (parser.getFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL)) {
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
        }
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
        parser.setInput(new StringReader(text));
        List<RoutePath.Vertex> points = new Reader(parser).read();
        if (points.size() < MIN_ROUTE_POINTS) throw new IllegalArgumentException("GPX 至少需要两个有效点");
        List<RoutePath.Vertex> timed = applyTimeline(points);
        return Collections.unmodifiableList(downsample(timed));
    }

    private static List<RoutePath.Vertex> applyTimeline(List<RoutePath.Vertex> points) {
        Double previous = null;
        String reason = null;
        for (RoutePath.Vertex point : points) {
            if (point.timeSeconds == null) { reason = "存在没有时间戳的点"; break; }
            if (previous != null && point.timeSeconds < previous) { reason = "时间戳倒序"; break; }
            previous = point.timeSeconds;
        }
        if (reason == null && previous - points.get(0).timeSeconds <= MIN_TIMELINE_SECONDS) {
            reason = "路径总时长不超过 " + MIN_TIMELINE_SECONDS + " 秒";
        }
        if (reason == null) return points;
        AppLog.warn("GPX " + reason + "，已明确切换为匀速路径");
        List<RoutePath.Vertex> result = new ArrayList<>(points.size());
        for (RoutePath.Vertex point : points) result.add(new RoutePath.Vertex(point.point, null));
        return result;
    }

    private static List<RoutePath.Vertex> downsample(List<RoutePath.Vertex> points) {
        if (points.size() <= POINT_LIMIT) return points;
        AppLog.warn("GPX 共 " + points.size() + " 点，按原项目规则抽稀为 " + POINT_LIMIT + " 点");
        double step = (points.size() - 1) / (double) (POINT_LIMIT - 1);
        List<RoutePath.Vertex> result = new ArrayList<>(POINT_LIMIT);
        for (int index = 0; index < POINT_LIMIT; index++) {
            result.add(points.get((int) Math.round(index * step)));
        }
        return result;
    }

    private static boolean pointTag(String tag) {
        return "trkpt".equals(tag) || "rtept".equals(tag) || "wpt".equals(tag);
    }

    private static double finiteNumber(String text, String field) {
        try {
            double value = Double.parseDouble(text);
            if (!Double.isFinite(value)) throw new NumberFormatException("不是有限数值");
            return value;
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("GPX " + field + " 无效：" + text, ex);
        }
    }

    private static double coordinate(String raw, String field) {
        double value = finiteNumber(raw, field);
        double limit = "lat".equals(field) ? MAX_LATITUDE : MAX_LONGITUDE;
        if (Math.abs(value) > limit) throw new IllegalArgumentException("GPX " + field + " 超出范围：" + raw);
        return Math.round(value * COORDINATE_SCALE) / COORDINATE_SCALE;
    }

    private static double timestamp(String text) {
        try {
            return Instant.parse(text).toEpochMilli() / MILLIS_PER_SECOND;
        } catch (java.time.format.DateTimeParseException ex) {
            try {
                double value = LocalDateTime.parse(text).toInstant(ZoneOffset.UTC).toEpochMilli() / MILLIS_PER_SECOND;
                AppLog.info("GPX 未标时区的时间按 UTC 解析：" + text);
                return value;
            } catch (java.time.format.DateTimeParseException invalid) {
                throw new IllegalArgumentException("GPX time 无效：" + text, invalid);
            }
        }
    }

    private static final class Reader {
        private final XmlPullParser parser;
        private final List<RoutePath.Vertex> points = new ArrayList<>();
        private PointBuilder point;
        private String valueTag;
        private int valueDepth;
        private final StringBuilder value = new StringBuilder();
        private boolean hasRoot;

        Reader(XmlPullParser parser) { this.parser = parser; }

        List<RoutePath.Vertex> read() throws Exception {
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                process(event);
                event = parser.nextToken();
            }
            if (!hasRoot) throw new IllegalArgumentException("文件不是 GPX：缺少 gpx 根节点");
            return points;
        }

        private void process(int event) {
            if (event == XmlPullParser.DOCDECL) throw new IllegalArgumentException("GPX 禁止 DOCTYPE / 外部实体");
            if (event == XmlPullParser.ENTITY_REF) {
                if (parser.getText() == null) throw new IllegalArgumentException("GPX 包含未定义实体");
                if (valueTag != null) value.append(parser.getText());
            }
            if (event == XmlPullParser.START_TAG) start();
            if (event == XmlPullParser.END_TAG) end();
            if ((event == XmlPullParser.TEXT || event == XmlPullParser.CDSECT) && valueTag != null) {
                value.append(parser.getText());
            }
        }

        private void start() {
            String tag = parser.getName();
            if (valueTag != null) throw new IllegalArgumentException("GPX ele/time 只允许文本值");
            if (parser.getDepth() == 1) {
                if (!"gpx".equals(tag)) throw new IllegalArgumentException("文件不是 GPX：根节点应为 gpx");
                hasRoot = true;
            }
            if (pointTag(tag)) {
                if (point != null) throw new IllegalArgumentException("GPX 点不允许嵌套");
                point = new PointBuilder(coordinate(parser.getAttributeValue(null, "lat"), "lat"),
                        coordinate(parser.getAttributeValue(null, "lon"), "lon"));
                return;
            }
            if (point == null || (!"ele".equals(tag) && !"time".equals(tag))) return;
            if (valueTag != null) throw new IllegalArgumentException("GPX ele/time 不允许嵌套");
            valueTag = tag;
            valueDepth = parser.getDepth();
            value.setLength(0);
        }

        private void end() {
            String tag = parser.getName();
            if (valueTag != null && parser.getDepth() == valueDepth && valueTag.equals(tag)) {
                storeValue();
                valueTag = null;
            }
            if (!pointTag(tag)) return;
            if (point == null) throw new IllegalArgumentException("GPX 点结构无效");
            RoutePath.Vertex next = point.build();
            if (points.isEmpty() || !duplicate(points.get(points.size() - 1), next)) points.add(next);
            point = null;
        }

        private void storeValue() {
            String text = value.toString().trim();
            if ("ele".equals(valueTag)) {
                if (point.hasAltitude) throw new IllegalArgumentException("GPX 点包含重复 ele");
                point.altitude = finiteNumber(text, "ele");
                point.hasAltitude = true;
                return;
            }
            if (point.hasTime) throw new IllegalArgumentException("GPX 点包含重复 time");
            point.time = timestamp(text);
            point.hasTime = true;
        }
    }

    private static boolean duplicate(RoutePath.Vertex previous, RoutePath.Vertex next) {
        return previous.point.latitude == next.point.latitude && previous.point.longitude == next.point.longitude &&
                Objects.equals(previous.point.altitude, next.point.altitude) &&
                Objects.equals(previous.timeSeconds, next.timeSeconds);
    }

    private static final class PointBuilder {
        private final double latitude;
        private final double longitude;
        private Double altitude;
        private Double time;
        private boolean hasAltitude;
        private boolean hasTime;

        PointBuilder(double latitude, double longitude) {
            this.latitude = latitude;
            this.longitude = longitude;
        }

        RoutePath.Vertex build() { return new RoutePath.Vertex(new Geo.Point(latitude, longitude, altitude), time); }
    }
}
