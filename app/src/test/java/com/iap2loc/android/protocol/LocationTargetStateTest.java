package com.iap2loc.android.protocol;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public final class LocationTargetStateTest {
    @Test
    public void explicitClearDiffersFromOmittedNullableField() {
        LocationTarget target = new LocationTarget(31, 121);
        LocationTarget.Update values = new LocationTarget.Update();
        values.altitude = 12.0;
        values.accuracy = 5.0;
        values.speed = 2.0;
        values.bearing = 90.0;
        target.apply(values);
        target.apply(new LocationTarget.Update());
        assertEquals(12.0, target.next().altitudeMeters, 0);
        LocationTarget.Update clear = new LocationTarget.Update();
        clear.clearAltitude = true;
        clear.clearAccuracy = true;
        clear.clearSpeed = true;
        clear.clearBearing = true;
        target.apply(clear);
        LocationTarget.Fix fix = target.next();
        assertNull(fix.altitudeMeters);
        assertNull(fix.accuracyMeters);
        assertNull(fix.speedMetersPerSecond);
        assertNull(fix.bearingDegrees);
    }

    @Test
    public void routeSnapshotKeepsOriginalTimesAndCannotBeMutated() {
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        vertices.add(new RoutePath.Vertex(new Geo.Point(31, 121, 1.0), 1000.0));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.001, 121, 2.0), 1010.0));
        LocationTarget target = new LocationTarget(31, 121);
        target.apply(new LocationTarget.Update().route(vertices));
        vertices.clear();
        LocationTarget.State snapshot = target.snapshot();
        assertEquals(2, snapshot.route.size());
        assertEquals(1000.0, snapshot.route.get(0).timeSeconds, 0);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.route.clear());
        assertSame(snapshot.route, target.snapshot().route);
    }

    @Test
    public void pathClockFreezesWhileInactiveAndResumesOnActivate() throws Exception {
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        vertices.add(new RoutePath.Vertex(new Geo.Point(31, 121), null));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.01, 121), null));
        LocationTarget target = new LocationTarget(31, 121);
        LocationTarget.Update update = new LocationTarget.Update()
                .mode(LocationTarget.Mode.PATH).route(vertices);
        update.pathSpeedMps = 50.0;
        target.apply(update);
        Thread.sleep(80);
        assertEquals(0.0, target.snapshot().pathTravelledMeters, 0.001);
        target.setActive(true);
        Thread.sleep(80);
        double travelled = target.snapshot().pathTravelledMeters;
        assertTrue("激活后应有行进距离", travelled > 0.5);
        target.setActive(false);
        double frozen = target.snapshot().pathTravelledMeters;
        Thread.sleep(80);
        assertEquals("停止时行进距离应冻结", frozen,
                target.snapshot().pathTravelledMeters, 0.001);
        target.setActive(true);
        Thread.sleep(80);
        assertTrue("恢复后应从冻结点继续",
                target.snapshot().pathTravelledMeters > frozen);
    }

    @Test
    public void closedLoopWrapsContinuouslyWithoutTeleport() {
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.0), null));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.01), null));
        RoutePath open = new RoutePath(vertices);
        RoutePath closed = new RoutePath(vertices, true);

        assertFalse(open.closed());
        assertTrue(closed.closed());
        double openLength = open.lengthMeters();
        assertEquals(openLength * 2, closed.lengthMeters(), 0.001);
        // A→B 正向、B→A 闭合边对称，中点应是 (31.0, 121.005)
        Geo.Point midLeg = closed.at(openLength + (closed.lengthMeters() - openLength) / 2).point;
        assertEquals(31.0, midLeg.latitude, 1e-6);
        assertEquals(121.005, midLeg.longitude, 1e-6);
        // 环回前后位置必须连续：距整圈 0.1m 处与起点相距不超过 0.2m
        Geo.Point nearWrap = closed.at(closed.lengthMeters() - 0.1).point;
        assertTrue(Geo.distance(nearWrap, closed.at(0).point) < 0.2);
    }

    @Test
    public void loopToggleRebuildsClosedGeometryWithoutTouchingVertices() {
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.0), null));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.01), null));
        LocationTarget target = new LocationTarget(31, 121);
        LocationTarget.Update open = new LocationTarget.Update()
                .mode(LocationTarget.Mode.PATH).route(vertices);
        open.pathLoop = false;
        target.apply(open);
        double openLength = target.snapshot().routeLengthMeters;

        LocationTarget.Update loop = new LocationTarget.Update();
        loop.pathLoop = true;
        target.apply(loop);

        assertEquals(openLength * 2, target.snapshot().routeLengthMeters, 0.001);
        assertEquals("闭合边是引擎内部概念，不得污染用户顶点", 2, target.snapshot().route.size());
    }
}
