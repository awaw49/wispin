package com.iap2loc.android.protocol;

/** Wire checksum and packet encoder shared by the link and its sequence window. */
final class Iap2Packet {
    private static final int HEADER_SIZE = 9;
    private Iap2Packet() {}

    static final class Numbers {
        final byte sequence;
        final byte acknowledge;
        Numbers(byte sequence, byte acknowledge) {
            this.sequence = sequence;
            this.acknowledge = acknowledge;
        }
    }

    static final class Header {
        final int control;
        final int session;
        final Numbers numbers;
        Header(int control, int session, Numbers numbers) {
            this.control = control;
            this.session = session;
            this.numbers = numbers;
        }
    }

    static final class Fields {
        final Header header;
        final byte[] payload;
        Fields(Header header, byte[] payload) {
            this.header = header;
            this.payload = payload;
        }
    }

    static byte[] encode(Fields fields) {
        byte[] payload = fields.payload;
        int length = payload == null ? HEADER_SIZE : payload.length + HEADER_SIZE + 1;
        byte[] packet = new byte[length];
        packet[0] = (byte) 0xFF;
        packet[1] = (byte) 0x5A;
        packet[2] = (byte) (length >>> 8);
        packet[3] = (byte) length;
        packet[4] = (byte) fields.header.control;
        packet[5] = fields.header.numbers.sequence;
        packet[6] = fields.header.numbers.acknowledge;
        packet[7] = (byte) fields.header.session;
        packet[8] = checksum(packet, 0, HEADER_SIZE - 1);
        if (payload != null) {
            System.arraycopy(payload, 0, packet, HEADER_SIZE, payload.length);
            packet[packet.length - 1] = checksum(payload, 0, payload.length);
        }
        return packet;
    }

    static boolean checksumIsZero(byte[] data, int offset, int count) {
        return checksum(data, offset, count) == 0;
    }

    private static byte checksum(byte[] data, int offset, int count) {
        int sum = 0;
        for (int i = 0; i < count; i++) sum += data[offset + i] & 0xFF;
        return (byte) -sum;
    }
}
