package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.MfiSigner;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/** Examines public certificate details and proves that one certificate matches the private key. */
public final class IdentityInspection {
    private static final int DIGEST_BYTES = 32;
    private static final int SIGNATURE_BYTES = DIGEST_BYTES * 2;

    private IdentityInspection() {}

    public static String inspect(byte[] privateKey, byte[] certificateBytes) {
        MfiSigner signer = MfiSigner.fromMaterial(privateKey, certificateBytes);
        try {
            List<X509Certificate> chain = parseCertificates(certificateBytes);
            requireMatchingKey(signer, chain);
            StringBuilder report = new StringBuilder("私钥：P-256 (secp256r1)\n私钥与证书：匹配\n");
            report.append("证书数量：").append(chain.size()).append('\n');
            for (X509Certificate certificate : chain) report.append(certificateDescription(certificate));
            report.append("此检查未验证 Apple MFi 信任链或定位权限；最终以 iPhone 认证结果为准。");
            return report.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("MFi 身份检查失败：" + ex.getMessage(), ex);
        }
    }

    private static List<X509Certificate> parseCertificates(byte[] bytes) throws Exception {
        Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
                .generateCertificates(new ByteArrayInputStream(bytes));
        if (certificates.isEmpty()) throw new IllegalArgumentException("certificate.p7b 中没有可解析的 X.509 证书");
        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate certificate : certificates) chain.add((X509Certificate) certificate);
        return chain;
    }

    private static void requireMatchingKey(MfiSigner signer, List<X509Certificate> chain) throws Exception {
        byte[] digest = new byte[DIGEST_BYTES];
        new SecureRandom().nextBytes(digest);
        byte[] signature = toDer(signer.signChallenge(digest));
        for (X509Certificate certificate : chain) {
            if (!(certificate.getPublicKey() instanceof ECPublicKey)) continue;
            ECPublicKey key = (ECPublicKey) certificate.getPublicKey();
            if (key.getParams().getCurve().getField().getFieldSize() != DIGEST_BYTES * Byte.SIZE) continue;
            Signature verifier = Signature.getInstance("NONEwithECDSA");
            verifier.initVerify(key);
            verifier.update(digest);
            if (verifier.verify(signature)) return;
        }
        throw new IllegalArgumentException("identity.pk8 与 certificate.p7b 中的证书公钥不匹配");
    }

    private static String certificateDescription(X509Certificate certificate) throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("\nSubject: ").append(certificate.getSubjectX500Principal().getName()).append('\n');
        report.append("Issuer: ").append(certificate.getIssuerX500Principal().getName()).append('\n');
        report.append("有效期：").append(certificate.getNotBefore()).append(" .. ")
                .append(certificate.getNotAfter()).append('\n');
        Date now = new Date();
        report.append("当前有效期状态：").append(now.before(certificate.getNotBefore()) ? "尚未生效" :
                now.after(certificate.getNotAfter()) ? "已过期" : "有效").append('\n');
        report.append("Serial: ").append(certificate.getSerialNumber().toString(16)).append('\n');
        report.append("SHA-256: ").append(fingerprint(certificate.getEncoded())).append('\n');
        Set<String> oids = new TreeSet<>();
        if (certificate.getCriticalExtensionOIDs() != null) oids.addAll(certificate.getCriticalExtensionOIDs());
        if (certificate.getNonCriticalExtensionOIDs() != null) oids.addAll(certificate.getNonCriticalExtensionOIDs());
        for (String oid : oids) report.append("Extension OID: ").append(oid).append('\n');
        return report.toString();
    }

    private static String fingerprint(byte[] certificate) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte value : MessageDigest.getInstance("SHA-256").digest(certificate)) {
            if (hex.length() > 0) hex.append(':');
            hex.append(String.format(Locale.ROOT, "%02X", value & 0xff));
        }
        return hex.toString();
    }

    private static byte[] toDer(byte[] signature) {
        if (signature.length != SIGNATURE_BYTES) throw new IllegalArgumentException("签名长度无效");
        byte[] r = new BigInteger(1, Arrays.copyOfRange(signature, 0, DIGEST_BYTES)).toByteArray();
        byte[] s = new BigInteger(1, Arrays.copyOfRange(signature, DIGEST_BYTES, SIGNATURE_BYTES)).toByteArray();
        byte[] result = new byte[2 + 2 + r.length + 2 + s.length];
        result[0] = 0x30;
        result[1] = (byte) (result.length - 2);
        result[2] = 0x02;
        result[3] = (byte) r.length;
        System.arraycopy(r, 0, result, 4, r.length);
        int offset = 4 + r.length;
        result[offset++] = 0x02;
        result[offset++] = (byte) s.length;
        System.arraycopy(s, 0, result, offset, s.length);
        return result;
    }
}
