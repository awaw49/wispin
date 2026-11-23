package com.iap2loc.android;

import com.iap2loc.android.map.RouteEditor;
import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public final class RouteEditorTest {
    private static final double INITIAL_TIME = 1000;
    private static final double INITIAL_ALTITUDE = 25;

    @Test
    public void untouchedGpxPreservesAltitudeAndTimestampWithoutSharingMutableInput() {
        List<RoutePath.Vertex> source = new ArrayList<>(timedRoute());
        RouteEditor editor = new RouteEditor(source);
        source.clear();
        assertEquals(2, editor.points().size());
        assertEquals(INITIAL_TIME, editor.points().get(0).timeSeconds, 0);
        assertEquals(INITIAL_ALTITUDE, editor.points().get(0).point.altitude, 0);
        assertTrue(editor.hasTimestamps());
        assertFalse(editor.canUndo());
    }

    @Test
    public void addingThirdPointClearsAllTimesButRetainsExistingAltitudes() {
        RouteEditor original = new RouteEditor(timedRoute());
        RouteEditor edited = original.add(new Geo.Point(31.3, 121.3));
        assertEquals(3, edited.points().size());
        assertFalse(edited.hasTimestamps());
        assertEquals(INITIAL_ALTITUDE, edited.points().get(0).point.altitude, 0);
        assertTrue(original.hasTimestamps());
        assertEquals(2, original.points().size());
        assertEquals(INITIAL_TIME, edited.undo().points().get(0).timeSeconds, 0);
    }

    @Test
    public void movingWaypointKeepsItsAltitudeAndUndoRestoresOriginalGpx() {
        RouteEditor original = new RouteEditor(timedRoute());
        RouteEditor edited = original.move(0, new Geo.Point(32, 122));
        assertEquals(32, edited.points().get(0).point.latitude, 0);
        assertEquals(INITIAL_ALTITUDE, edited.points().get(0).point.altitude, 0);
        assertFalse(edited.hasTimestamps());
        assertEquals(31.1, edited.undo().points().get(0).point.latitude, 0);
        assertTrue(edited.undo().hasTimestamps());
    }

    @Test
    public void deletingAndClearingCanBothBeUndoneInOrder() {
        RouteEditor original = new RouteEditor(timedRoute());
        RouteEditor removed = original.remove(0);
        assertEquals(31.2, removed.points().get(0).point.latitude, 0);
        RouteEditor cleared = removed.clear();
        assertTrue(cleared.points().isEmpty());
        assertEquals(1, cleared.undo().points().size());
        assertTrue(cleared.undo().undo().hasTimestamps());
    }

    @Test
    public void emptyRouteSupportsClearUndoAndFirstPoint() {
        RouteEditor editor = new RouteEditor(Collections.emptyList());
        assertSame(editor, editor.undo());
        assertSame(editor, editor.clear());
        assertEquals(1, editor.add(new Geo.Point(0, 0)).points().size());
    }

    @Test
    public void invalidGeometryIsRejectedAndPreviousRouteRemainsIntact() {
        RouteEditor editor = new RouteEditor(timedRoute());
        assertThrows(IllegalArgumentException.class, () -> editor.add(new Geo.Point(Double.NaN, 0)));
        assertThrows(IllegalArgumentException.class, () -> editor.move(0, new Geo.Point(91, 0)));
        assertThrows(IllegalArgumentException.class, () -> editor.add(new Geo.Point(0, 181)));
        assertTrue(editor.hasTimestamps());
        assertEquals(2, editor.points().size());
    }

    private static List<RoutePath.Vertex> timedRoute() {
        return Arrays.asList(
                new RoutePath.Vertex(new Geo.Point(31.1, 121.1, INITIAL_ALTITUDE), INITIAL_TIME),
                new RoutePath.Vertex(new Geo.Point(31.2, 121.2, 30.0), INITIAL_TIME + 60));
    }
}
