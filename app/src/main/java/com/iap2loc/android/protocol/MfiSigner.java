package com.iap2loc.android.protocol;

import java.math.BigInteger;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;

/** MFi authentication 3.0: P-256 ECDSA over a 32-byte challenge. */
public final class MfiSigner {
    private static final int SCALAR_BYTES = 32;
    private static final int SIGNATURE_BYTES = SCALAR_BYTES * 2;
    private static final String CURVE = "secp256r1";
    private static final BigInteger P256_ORDER = new BigInteger(
            "FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16);

    private final PrivateKey key;
    private final byte[] certificate;

    private MfiSigner(PrivateKey key, byte[] certificate) {
        this.key = key;
        this.certificate = certificate;
    }

    public byte[] certificate() {
        return certificate.clone();
    }

    public static MfiSigner fromMaterial(byte[] pk8, byte[] certificate) {
        if (pk8 == null || pk8.length == 0 || certificate == null || certificate.length == 0) {
            throw new IllegalArgumentException("MFi material missing");
        }
        try {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(pk8));
            requireP256(key);
            return new MfiSigner(key, certificate.clone());
        } catch (Exception ex) {
            throw new IllegalStateException("cannot import identity.pk8: " + ex.getMessage(), ex);
        }
    }

    private static void requireP256(PrivateKey key) throws Exception {
        if (!(key instanceof ECPrivateKey)) throw new IllegalArgumentException("expected an EC private key");
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec(CURVE));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = ((ECPrivateKey) key).getParams();
        boolean sameCurve = expected.getCurve().equals(actual.getCurve()) &&
                expected.getGenerator().equals(actual.getGenerator()) &&
                expected.getOrder().equals(actual.getOrder()) &&
                expected.getCofactor() == actual.getCofactor();
        if (!sameCurve) throw new IllegalArgumentException("MFi 3.0 requires the P-256 (secp256r1) curve");
    }

    /** Signs as a digest and normalises to the 64-byte r||s, low-S form iOS expects. */
    public synchronized byte[] signChallenge(byte[] challenge) {
        if (challenge == null || challenge.length != SCALAR_BYTES) {
            throw new IllegalArgumentException(
                    "MFi 3.0 challenge must be 32 bytes; MFi 2.0C is not supported");
        }
        try {
            // The challenge already is a SHA-256 digest, matching the upstream SignHash call.
            Signature signature = Signature.getInstance("NONEwithECDSA");
            signature.initSign(key);
            signature.update(challenge);
            byte[] p1363 = derToP1363(signature.sign());
            return normalizeLowS(p1363);
        } catch (Exception ex) {
            throw new IllegalStateException("MFi signing failed: " + ex.getMessage(), ex);
        }
    }

    private static byte[] normalizeLowS(byte[] signature) {
        if (signature.length != SIGNATURE_BYTES) throw new IllegalArgumentException("invalid P-256 signature length");
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(signature, SCALAR_BYTES, SIGNATURE_BYTES));
        if (s.compareTo(P256_ORDER.shiftRight(1)) <= 0) return signature;
        byte[] normalized = toFixed32(P256_ORDER.subtract(s));
        byte[] result = signature.clone();
        System.arraycopy(normalized, 0, result, SCALAR_BYTES, SCALAR_BYTES);
        return result;
    }

    private static byte[] derToP1363(byte[] der) {
        int offset = 0;
        if (der.length < 8 || (der[offset++] & 0xFF) != 0x30) {
            throw new IllegalArgumentException("invalid ECDSA DER signature");
        }
        offset = skipLength(der, offset);
        if ((der[offset++] & 0xFF) != 0x02) throw new IllegalArgumentException("invalid DER r");
        int rLength = readLength(der, offset);
        offset = skipLength(der, offset);
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(der, offset, offset + rLength));
        offset += rLength;
        if ((der[offset++] & 0xFF) != 0x02) throw new IllegalArgumentException("invalid DER s");
        int sLength = readLength(der, offset);
        offset = skipLength(der, offset);
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(der, offset, offset + sLength));

        byte[] result = new byte[SIGNATURE_BYTES];
        System.arraycopy(toFixed32(r), 0, result, 0, SCALAR_BYTES);
        System.arraycopy(toFixed32(s), 0, result, SCALAR_BYTES, SCALAR_BYTES);
        return result;
    }

    private static int readLength(byte[] data, int offset) {
        int first = data[offset] & 0xFF;
        if ((first & 0x80) == 0) return first;
        int count = first & 0x7F;
        int length = 0;
        for (int i = 0; i < count; i++) length = (length << 8) | (data[offset + 1 + i] & 0xFF);
        return length;
    }

    private static int skipLength(byte[] data, int offset) {
        int first = data[offset] & 0xFF;
        return (first & 0x80) == 0 ? offset + 1 : offset + 1 + (first & 0x7F);
    }

    private static byte[] toFixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[SCALAR_BYTES];
        if (raw.length <= SCALAR_BYTES) {
            System.arraycopy(raw, 0, result, SCALAR_BYTES - raw.length, raw.length);
        } else {
            System.arraycopy(raw, raw.length - SCALAR_BYTES, result, 0, SCALAR_BYTES);
        }
        return result;
    }
}
