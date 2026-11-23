package com.iap2loc.android;

import com.iap2loc.android.protocol.Csm;
import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.Identification;
import com.iap2loc.android.protocol.LocationTarget;
import com.iap2loc.android.protocol.MfiSigner;
import com.iap2loc.android.protocol.Nmea;
import com.iap2loc.android.protocol.RoutePath;

import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** JVM-side checks for the protocol port; no Android device is required. */
public class ProtocolSelfTest {

    @Test
    public void csmRoundTrip() {
        byte[] params = new Csm.Params()
                .str(0, "abc")
                .empty(1)
                .u16(0x8001, 250)
                .toByteArray();
        Map<Integer, byte[]> parsed = Csm.parseParams(params, 0, params.length);
        assertEquals("abc\0", new String(parsed.get(0), StandardCharsets.UTF_8));
        assertEquals(0, parsed.get(1).length);
        assertArrayEquals(new byte[]{0, (byte) 250}, parsed.get(0x8001));

        byte[] message = Csm.encode(0xFFFA, Csm.param(1, new byte[0]));
        assertEquals(0x40, message[0] & 0xFF);
        assertEquals(0xFF, message[4] & 0xFF);
        assertEquals(0xFA, message[5] & 0xFF);
    }

    @Test
    public void nmeaContainsGgaAndRmcWithValidChecksum() {
        LocationTarget.Fix fix = new LocationTarget.Fix(
                31.2304, 121.4737, 10.0, 1.0, 90.0, 5.0, 1, 8);
        String[] sentences = Nmea.encode(fix, new Date(0L)).split("\r\n");
        assertEquals(2, sentences.length);
        assertTrue(sentences[0].startsWith("$GPGGA,"));
        assertTrue(sentences[1].startsWith("$GPRMC,"));
        assertTrue(sentences[0].contains(",N,"));
        assertTrue(sentences[1].contains(",E,"));
        for (String sentence : sentences) {
            String body = sentence.substring(1, sentence.indexOf('*'));
            int expected = 0;
            for (char c : body.toCharArray()) expected ^= c;
            String checksum = sentence.substring(sentence.indexOf('*') + 1);
            assertEquals(String.format("%02X", expected), checksum);
        }
    }

    @Test
    public void gcjConversionIsReversibleInsideChina() {
        double[] gcj = Geo.wgs84ToGcj02(31.2304, 121.4737);
        assertNotEquals(31.2304, gcj[0], 1e-6);
        double[] back = Geo.gcj02ToWgs84(gcj[0], gcj[1]);
        assertEquals(31.2304, back[0], 1e-6);
        assertEquals(121.4737, back[1], 1e-6);

        double[] outside = Geo.wgs84ToGcj02(40.7128, -74.0060);
        assertEquals(40.7128, outside[0], 0);
        assertEquals(-74.0060, outside[1], 0);
    }

    @Test
    public void nmeaPadsSingleDigitMinutesToTwoIntegerDigits() {
        // 114.164175°E -> 9.8505' must encode as "11409.8505", not "1149.8505" (Sahara bug).
        LocationTarget.Fix fix = new LocationTarget.Fix(
                22.430296, 114.164175, null, null, null, null, 1, 8);
        String gga = Nmea.encode(fix, new Date(0L)).split("\r\n")[0];
        assertTrue(gga.contains(",11409.8505,E,"));
    }

    @Test
    public void routeSupportsGpxTimelineAndConstantSpeed() {
        List<RoutePath.Vertex> vertices = new ArrayList<>();
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.0), 0.0));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.001, 121.0), 10.0));
        vertices.add(new RoutePath.Vertex(new Geo.Point(31.002, 121.0), 20.0));
        RoutePath path = new RoutePath(vertices);
        assertTrue(path.hasTimeline());
        assertEquals(20, path.durationSeconds(), 0.001);
        RoutePath.Sample middle = path.atTime(10, false);
        assertEquals(31.001, middle.point.latitude, 1e-6);
        assertTrue(middle.speedMetersPerSecond > 0);

        List<RoutePath.Vertex> plain = new ArrayList<>();
        plain.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.0), null));
        plain.add(new RoutePath.Vertex(new Geo.Point(31.0, 121.001), null));
        assertFalse(new RoutePath(plain).hasTimeline());
    }

    @Test
    public void identificationDeclaresLocationComponent() throws Exception {
        byte[] params = Identification.build(new com.iap2loc.android.protocol.AccessoryProfile(),
                new byte[]{0x11, 0x22, 0x33, 0x44, 0x55, 0x66});
        Map<Integer, byte[]> parsed = Csm.parseParams(params, 0, params.length);
        assertTrue(parsed.containsKey(17));
        assertTrue(parsed.containsKey(22));
        assertTrue(parsed.containsKey(6));
        assertTrue(parsed.containsKey(7));
    }

    @Test
    public void mfiSignerProducesLowSP1363Signature() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        byte[] challenge = new byte[32];
        for (int i = 0; i < 32; i++) challenge[i] = (byte) i;

        MfiSigner signer = MfiSigner.fromMaterial(pair.getPrivate().getEncoded(), new byte[]{1, 2, 3});
        byte[] signature = signer.signChallenge(challenge);
        assertEquals(64, signature.length);

        BigInteger order = new BigInteger(
                "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signature, 32, 64));
        assertTrue(s.compareTo(order.shiftRight(1)) <= 0);
        Signature verifier = Signature.getInstance("NONEwithECDSA");
        verifier.initVerify(pair.getPublic());
        verifier.update(challenge);
        assertTrue(verifier.verify(toDer(signature)));
        Signature doubleHashVerifier = Signature.getInstance("SHA256withECDSA");
        doubleHashVerifier.initVerify(pair.getPublic());
        doubleHashVerifier.update(challenge);
        assertFalse(doubleHashVerifier.verify(toDer(signature)));
    }

    private static byte[] toDer(byte[] p1363) {
        byte[] r = trim(p1363, 0);
        byte[] s = trim(p1363, 32);
        int contentLength = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + contentLength];
        der[0] = 0x30;
        der[1] = (byte) contentLength;
        der[2] = 0x02;
        der[3] = (byte) r.length;
        System.arraycopy(r, 0, der, 4, r.length);
        int offset = 4 + r.length;
        der[offset++] = 0x02;
        der[offset++] = (byte) s.length;
        System.arraycopy(s, 0, der, offset, s.length);
        return der;
    }

    private static byte[] trim(byte[] source, int offset) {
        return new BigInteger(1, java.util.Arrays.copyOfRange(source, offset, offset + 32)).toByteArray();
    }
}
