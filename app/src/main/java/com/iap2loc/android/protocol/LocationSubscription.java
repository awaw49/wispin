package com.iap2loc.android.protocol;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Sentence selection and per-sentence periods requested by StartLocationInformation. */
final class LocationSubscription {
        final Set<Integer> sentences = new HashSet<>();
        final Map<Integer, Integer> requestedMs = new HashMap<>();

    static int[] tracked(LocationSubscription subscription) {
        return subscription.sentences.isEmpty()
                ? new int[]{1, 2}
                : subscription.sentences.stream().mapToInt(Integer::intValue).toArray();
    }

    static LocationSubscription parseLocationSubscription(Map<Integer, byte[]> parameters) {
        LocationSubscription result = new LocationSubscription();
        for (Map.Entry<Integer, byte[]> entry : parameters.entrySet()) {
            int id = entry.getKey();
            byte[] data = entry.getValue();
            if ((id == 1 || id == 2) && data.length == 0) {
                result.sentences.add(id);
            } else if ((id == 0x8001 || id == 0x8002) && data.length == 4) {
                long milliseconds = ((long) (data[0] & 0xFF) << 24) |
                        ((long) (data[1] & 0xFF) << 16) |
                        ((long) (data[2] & 0xFF) << 8) |
                        (data[3] & 0xFF);
                if (milliseconds > 0 && milliseconds < Integer.MAX_VALUE) {
                    result.requestedMs.put(id - 0x8000, (int) milliseconds);
                }
            }
        }
        return result;
    }

    static int sentenceId(String sentence) {
        if (sentence.startsWith("$GPGGA") || sentence.startsWith("$GNGGA")) return 1;
        if (sentence.startsWith("$GPRMC") || sentence.startsWith("$GNRMC")) return 2;
        return 0;
    }

    static String describeRaw(Map<Integer, byte[]> parameters) {
        if (parameters.isEmpty()) return "none";
        List<Map.Entry<Integer, byte[]>> entries = new ArrayList<>(parameters.entrySet());
        entries.sort((a, b) -> Integer.compare(a.getKey(), b.getKey()));
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : entries) {
            int id = entry.getKey();
            byte[] data = entry.getValue();
            if ((id == 0x8001 || id == 0x8002) && data.length == 4) {
                long ms = ((long) (data[0] & 0xFF) << 24) | ((long) (data[1] & 0xFF) << 16) |
                        ((long) (data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                parts.add("0x" + hex4(id) + "=" + ms + "ms");
            } else {
                parts.add("0x" + hex4(id) + (data.length == 0 ? "(empty)" : "(" + data.length + "B)"));
            }
        }
        return String.join(", ", parts);
    }

    static String describe(LocationSubscription subscription, int plannedInterval) {
        String sentences = subscription.sentences.isEmpty()
                ? "all"
                : (subscription.sentences.contains(1) ? "gga" : "") +
                  (subscription.sentences.contains(1) && subscription.sentences.contains(2) ? "," : "") +
                  (subscription.sentences.contains(2) ? "rmc" : "");
        List<String> periods = new ArrayList<>();
        if (subscription.requestedMs.containsKey(1)) periods.add("gga " + subscription.requestedMs.get(1) + "ms");
        if (subscription.requestedMs.containsKey(2)) periods.add("rmc " + subscription.requestedMs.get(2) + "ms");
        return "sentences=[" + sentences + "], requested=[" + String.join(",", periods) +
                "], sending every " + plannedInterval + " ms";
    }

    private static String hex4(int value) { return String.format("%04X", value & 0xFFFF); }
}
