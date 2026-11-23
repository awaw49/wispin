package com.iap2loc.android;

import com.iap2loc.android.engine.TargetCommand;
import org.junit.Test;
import static org.junit.Assert.assertThrows;

public final class TargetCommandTest {
    @Test public void rejectsMalformedPayloadAndOutOfRangeCoordinates() {
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("not-json"));
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"latitude\":91}"));
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"longitude\":181}"));
    }

    @Test public void rejectsFractionalIntegerParametersAndInvalidRandomIntervals() {
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"rate\":250.5}"));
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"quality\":1.5}"));
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse(
                "{\"accuracyMin\":12,\"accuracyMax\":3}"));
    }

    @Test public void acceptsExplicitNullFieldsButRejectsNonfiniteValues() {
        TargetCommand.parse("{\"altitude\":null,\"accuracy\":null,\"speed\":null,\"bearing\":null}");
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"altitude\":\"NaN\"}"));
    }

    @Test public void rejectsPathSpeedsThatWouldOtherwiseBeSilentlyClamped() {
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"pathSpeedKmh\":0}"));
        assertThrows(IllegalArgumentException.class, () -> TargetCommand.parse("{\"pathSpeedKmh\":200}"));
        TargetCommand.parse("{\"pathSpeedKmh\":5}");
    }
}
