package com.iap2loc.android;

import com.iap2loc.android.engine.IdentityInspection;
import com.iap2loc.android.protocol.MfiSigner;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class IdentityInspectionTest {
    @Test
    public void describesMatchingPublicCertificateWithoutExposingPrivateKey() throws Exception {
        KeyPair pair = pair("secp256r1");
        String description = IdentityInspection.inspect(pair.getPrivate().getEncoded(), TestCertificate.create(pair));
        assertTrue(description.contains("私钥与证书：匹配"));
        assertTrue(description.contains("Subject: CN=Ephemeral MFi test"));
        assertTrue(description.contains("Issuer:"));
        assertTrue(description.contains("有效期："));
        assertTrue(description.contains("SHA-256:"));
        assertTrue(description.contains("未验证 Apple MFi 信任链"));
    }

    @Test
    public void rejectsCertificateForAnotherKey() throws Exception {
        KeyPair identity = pair("secp256r1");
        byte[] differentCertificate = TestCertificate.create(pair("secp256r1"));
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> IdentityInspection.inspect(identity.getPrivate().getEncoded(), differentCertificate));
        assertTrue(error.getMessage().contains("不匹配"));
    }

    @Test
    public void acceptsPkcs7CertificateChain() throws Exception {
        KeyPair pair = pair("secp256r1");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        Certificate certificate = factory.generateCertificate(new ByteArrayInputStream(TestCertificate.create(pair)));
        byte[] pkcs7 = factory.generateCertPath(Collections.singletonList(certificate)).getEncoded("PKCS7");
        String description = IdentityInspection.inspect(pair.getPrivate().getEncoded(), pkcs7);
        assertTrue(description.contains("证书数量：1"));
        assertTrue(description.contains("私钥与证书：匹配"));
    }

    @Test
    public void rejectsInvalidCertificateBytes() throws Exception {
        KeyPair identity = pair("secp256r1");
        assertThrows(IllegalStateException.class,
                () -> IdentityInspection.inspect(identity.getPrivate().getEncoded(), new byte[]{1, 2, 3}));
    }

    @Test
    public void rejectsAnotherEcCurveAndInvalidChallengeSize() throws Exception {
        KeyPair wrongCurve = pair("secp384r1");
        assertThrows(IllegalStateException.class,
                () -> MfiSigner.fromMaterial(wrongCurve.getPrivate().getEncoded(), new byte[]{1}));
        MfiSigner signer = MfiSigner.fromMaterial(pair("secp256r1").getPrivate().getEncoded(), new byte[]{1});
        assertThrows(IllegalArgumentException.class, () -> signer.signChallenge(new byte[20]));
    }

    private static KeyPair pair(String curve) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }
}
