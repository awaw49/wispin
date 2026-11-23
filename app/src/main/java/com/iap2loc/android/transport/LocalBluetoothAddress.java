package com.iap2loc.android.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Resolves the controller BD_ADDR through public Android APIs only. */
public final class LocalBluetoothAddress {
    private static final Pattern MAC_PATTERN =
            Pattern.compile("(?i)[0-9a-f]{2}([:-][0-9a-f]{2}){5}");
    private static final String PLACEHOLDER = "02:00:00:00:00:00";
    private static final String ZERO = "00:00:00:00:00:00";
    private static final String BROADCAST = "FF:FF:FF:FF:FF:FF";

    private LocalBluetoothAddress() {}

    public static Result resolve(Context context) {
        List<Candidate> candidates = new ArrayList<>();
        List<String> diagnostics = new ArrayList<>();
        Context appContext = context.getApplicationContext();
        try {
            candidates.add(new Candidate(Settings.Secure.getString(
                    appContext.getContentResolver(), "bluetooth_address"),
                    "Settings.Secure.bluetooth_address"));
        } catch (Exception ex) {
            diagnostics.add("Settings.Secure.bluetooth_address：" + message(ex));
        }
        try {
            BluetoothManager manager = (BluetoothManager) appContext.getSystemService(
                    Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
            candidates.add(new Candidate(adapter == null ? null : adapter.getAddress(),
                    "BluetoothAdapter.getAddress()"));
        } catch (Exception ex) {
            diagnostics.add("BluetoothAdapter.getAddress()：" + message(ex));
        }
        return select(candidates, diagnostics);
    }

    static Result select(List<Candidate> candidates, List<String> diagnostics) {
        for (Candidate candidate : candidates) {
            String address = usable(candidate.address);
            if (address != null) return new Result(address, candidate.source, diagnostics);
            diagnostics.add(candidate.source + " 返回空值或占位地址");
        }
        return new Result(null, null, diagnostics);
    }

    private static String usable(String value) {
        if (value == null || !MAC_PATTERN.matcher(value.trim()).matches()) return null;
        String compact = value.replaceAll("[^0-9A-Fa-f]", "");
        StringBuilder normalized = new StringBuilder();
        for (int index = 0; index < compact.length(); index += 2) {
            if (index > 0) normalized.append(':');
            normalized.append(compact.substring(index, index + 2)
                    .toUpperCase(Locale.ROOT));
        }
        String address = normalized.toString();
        return PLACEHOLDER.equals(address) || ZERO.equals(address) || BROADCAST.equals(address)
                ? null : address;
    }

    private static String message(Exception ex) {
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    public static final class Candidate {
        public final String address;
        public final String source;

        public Candidate(String address, String source) {
            this.address = address;
            this.source = source;
        }
    }

    public static final class Result {
        public final String address;
        public final String source;
        public final List<String> diagnostics;

        private Result(String address, String source, List<String> diagnostics) {
            this.address = address;
            this.source = source;
            this.diagnostics = Collections.unmodifiableList(new ArrayList<>(diagnostics));
        }

        public boolean isAvailable() {
            return address != null;
        }
    }
}
