package com.iap2loc.android.protocol;

import java.security.SecureRandom;

/** Everything iOS sees as "which accessory is this". */
public final class AccessoryProfile {
    public String name = "iAP2 Loc";
    public String model = "Loc1,1";
    public String manufacturer = "carplay-loc";
    public String serial = "LOC000000001";
    public String firmware = "0.1.0";
    public String hardware = "1.0";
    /** Optional 6-byte override reported in iAP2 transport component 17. */
    public String adapterAddress;

    public static AccessoryProfile defaults() {
        return new AccessoryProfile();
    }

    public AccessoryProfile copy() {
        AccessoryProfile other = new AccessoryProfile();
        other.name = name;
        other.model = model;
        other.manufacturer = manufacturer;
        other.serial = serial;
        other.firmware = firmware;
        other.hardware = hardware;
        other.adapterAddress = adapterAddress;
        return other;
    }

    public byte[] adapterOverride() {
        if (adapterAddress == null || adapterAddress.trim().isEmpty()) return null;
        String[] parts = adapterAddress.split("[:-]");
        if (parts.length != 6) return null;
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) {
            try {
                int value = Integer.parseInt(parts[i].trim(), 16);
                if (value < 0 || value > 255) return null;
                bytes[i] = (byte) value;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return bytes;
    }

    public AccessoryProfile rotate() {
        SecureRandom random = new SecureRandom();
        byte[] bytes = new byte[6];
        random.nextBytes(bytes);
        StringBuilder hex = new StringBuilder();
        for (byte value : bytes) hex.append(String.format("%02X", value & 0xFF));
        AccessoryProfile next = copy();
        next.serial = "LC" + hex;
        next.name = "iAP2 Loc " + hex.substring(hex.length() - 4);
        return next;
    }

    /** Generates an address from the same vendor prefix as the local adapter when available. */
    public static String randomAdapterAddress(byte[] localAdapter) {
        byte[] bytes = new byte[6];
        new SecureRandom().nextBytes(bytes);
        if (localAdapter != null && localAdapter.length == 6) {
            System.arraycopy(localAdapter, 0, bytes, 0, 3);
        }
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) text.append(':');
            text.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return text.toString();
    }

    public AccessoryProfile sanitize() {
        AccessoryProfile clean = copy();
        clean.name = clean(name, defaults().name);
        clean.model = clean(model, defaults().model);
        clean.manufacturer = clean(manufacturer, defaults().manufacturer);
        clean.serial = clean(serial, defaults().serial);
        clean.firmware = clean(firmware, defaults().firmware);
        clean.hardware = clean(hardware, defaults().hardware);
        return clean;
    }

    private static String clean(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
