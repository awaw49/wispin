package com.iap2loc.android.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * iAP2 control-session messages: 40 40 | length(2) | message id(2) | parameters.
 * Each parameter is length(2) | id(2) | data, where length includes its own four header bytes.
 */
public final class Csm {
    public static final int MAGIC = 0x4040;

    private Csm() {}

    public static byte[] encode(int messageId, byte[]... parameters) {
        int size = 6;
        for (byte[] parameter : parameters) size += parameter.length;
        byte[] buffer = new byte[size];
        buffer[0] = 0x40;
        buffer[1] = 0x40;
        buffer[2] = (byte) (size >>> 8);
        buffer[3] = (byte) size;
        buffer[4] = (byte) (messageId >>> 8);
        buffer[5] = (byte) messageId;
        int offset = 6;
        for (byte[] parameter : parameters) {
            System.arraycopy(parameter, 0, buffer, offset, parameter.length);
            offset += parameter.length;
        }
        return buffer;
    }

    public static byte[] param(int id, byte[] data) {
        byte[] buffer = new byte[data.length + 4];
        int length = buffer.length;
        buffer[0] = (byte) (length >>> 8);
        buffer[1] = (byte) length;
        buffer[2] = (byte) (id >>> 8);
        buffer[3] = (byte) id;
        System.arraycopy(data, 0, buffer, 4, data.length);
        return buffer;
    }

    /** Later duplicate ids win, matching the behaviour of iOS. */
    public static Map<Integer, byte[]> parseParams(byte[] buffer, int offset, int count) {
        Map<Integer, byte[]> result = new LinkedHashMap<>();
        int end = offset + count;
        while (offset + 4 <= end) {
            int length = ((buffer[offset] & 0xFF) << 8) | (buffer[offset + 1] & 0xFF);
            if (length < 4 || offset + length > end) {
                throw new IllegalArgumentException(
                        "malformed iAP2 parameter at offset " + offset + " (length " + length + ")");
            }
            int id = ((buffer[offset + 2] & 0xFF) << 8) | (buffer[offset + 3] & 0xFF);
            byte[] data = new byte[length - 4];
            System.arraycopy(buffer, offset + 4, data, 0, data.length);
            result.put(id, data);
            offset += length;
        }
        return result;
    }

    /** Fluent builder for parameter blocks, including nested groups. */
    public static final class Params {
        private final ByteArrayOutputStream stream = new ByteArrayOutputStream();

        public Params param(int id, byte[] data) {
            byte[] encoded = Csm.param(id, data);
            stream.write(encoded, 0, encoded.length);
            return this;
        }

        public Params u8(int id, int value) {
            return param(id, new byte[]{(byte) value});
        }

        public Params u16(int id, int value) {
            return param(id, new byte[]{(byte) (value >>> 8), (byte) value});
        }

        /** iAP2 strings are NUL terminated, including NMEA sentence payloads. */
        public Params str(int id, String value) {
            return param(id, (value + "\0").getBytes(StandardCharsets.UTF_8));
        }

        public Params empty(int id) {
            return param(id, new byte[0]);
        }

        public Params u16List(int id, int... values) {
            byte[] data = new byte[values.length * 2];
            for (int i = 0; i < values.length; i++) {
                data[i * 2] = (byte) (values[i] >>> 8);
                data[i * 2 + 1] = (byte) values[i];
            }
            return param(id, data);
        }

        public Params group(int id, java.util.function.Consumer<Params> body) {
            Params nested = new Params();
            body.accept(nested);
            return param(id, nested.toByteArray());
        }

        public byte[] toByteArray() {
            return stream.toByteArray();
        }
    }
}
