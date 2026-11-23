package com.iap2loc.android;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;

import javax.security.auth.x500.X500Principal;

/** Creates an ephemeral, self-signed public certificate; no private-key fixture is stored. */
final class TestCertificate {
    private static final int SEQUENCE = 0x30;
    private static final int INTEGER = 0x02;
    private static final int UTC_TIME = 0x17;
    private static final int BIT_STRING = 0x03;
    private static final int LENGTH_MARKER = 0x80;
    private static final byte[] ALGORITHM = new byte[]{0x30, 0x0a, 0x06, 0x08,
            0x2a, (byte) 0x86, 0x48, (byte) 0xce, 0x3d, 0x04, 0x03, 0x02};

    private TestCertificate() {}

    static byte[] create(KeyPair pair) throws Exception {
        byte[] name = new X500Principal("CN=Ephemeral MFi test").getEncoded();
        byte[] validity = tagged(SEQUENCE, joined(
                tagged(UTC_TIME, "240101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tagged(UTC_TIME, "490101000000Z".getBytes(StandardCharsets.US_ASCII))));
        byte[] tbs = tagged(SEQUENCE, joined(tagged(0xa0, tagged(INTEGER, new byte[]{2})),
                tagged(INTEGER, new byte[]{1}), ALGORITHM, name, validity, name,
                pair.getPublic().getEncoded()));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        return tagged(SEQUENCE, joined(tbs, ALGORITHM,
                tagged(BIT_STRING, joined(new byte[]{0}, signer.sign()))));
    }

    private static byte[] tagged(int tag, byte[] value) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(tag);
        if (value.length < LENGTH_MARKER) result.write(value.length);
        else {
            int bytes = value.length > 0xff ? 2 : 1;
            result.write(LENGTH_MARKER | bytes);
            for (int index = bytes - 1; index >= 0; index--) result.write(value.length >>> (Byte.SIZE * index));
        }
        result.write(value, 0, value.length);
        return result.toByteArray();
    }

    private static byte[] joined(byte[]... values) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] value : values) result.write(value, 0, value.length);
        return result.toByteArray();
    }
}
