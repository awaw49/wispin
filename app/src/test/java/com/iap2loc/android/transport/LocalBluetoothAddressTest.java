package com.iap2loc.android.transport;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class LocalBluetoothAddressTest {
    @Test
    public void firstUsableCandidateWins() {
        List<LocalBluetoothAddress.Candidate> candidates = Arrays.asList(
                new LocalBluetoothAddress.Candidate(null, "Settings.Secure"),
                new LocalBluetoothAddress.Candidate("aa-bb-cc-dd-ee-ff", "BluetoothAdapter"));

        LocalBluetoothAddress.Result result =
                LocalBluetoothAddress.select(candidates, new ArrayList<>());

        assertTrue(result.isAvailable());
        assertEquals("AA:BB:CC:DD:EE:FF", result.address);
        assertEquals("BluetoothAdapter", result.source);
    }

    @Test
    public void placeholderAndInvalidCandidatesAreSkipped() {
        List<LocalBluetoothAddress.Candidate> candidates = Arrays.asList(
                new LocalBluetoothAddress.Candidate("02:00:00:00:00:00", "placeholder"),
                new LocalBluetoothAddress.Candidate("00:00:00:00:00:00", "zero"),
                new LocalBluetoothAddress.Candidate("not-a-mac", "garbage"),
                new LocalBluetoothAddress.Candidate("12:34:56:78:9A:BC", "ok"));

        LocalBluetoothAddress.Result result =
                LocalBluetoothAddress.select(candidates, new ArrayList<>());

        assertTrue(result.isAvailable());
        assertEquals("12:34:56:78:9A:BC", result.address);
        assertEquals("ok", result.source);
        assertEquals(3, result.diagnostics.size());
    }

    @Test
    public void missingWhenNoCandidateUsable() {
        LocalBluetoothAddress.Result result = LocalBluetoothAddress.select(
                Collections.singletonList(new LocalBluetoothAddress.Candidate(
                        "FF:FF:FF:FF:FF:FF", "broadcast")),
                new ArrayList<>());

        assertFalse(result.isAvailable());
    }
}
