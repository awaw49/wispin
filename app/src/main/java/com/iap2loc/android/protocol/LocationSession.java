package com.iap2loc.android.protocol;

import com.iap2loc.android.util.AppLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Answers identification/authentication and streams NMEA whenever the phone subscribes. */
public final class LocationSession implements Iap2Link.Listener {
    public static final class Options {
        public AccessoryProfile identity = AccessoryProfile.defaults();
        public MfiSigner signer;
        public int intervalMs = 250;
        public byte[] adapter;
    }

    private final Iap2Link link;
    private final AccessoryProfile identity;
    private final MfiSigner signer;
    private final byte[] adapter;
    private final LocationStream stream;
    private volatile boolean authenticated;
    private Iap2Link.Listener linkEvents;

    public LocationSession(Iap2Link link, LocationTarget target, Options options) {
        this.link = link;
        this.identity = options.identity.copy();
        this.signer = options.signer;
        this.stream = new LocationStream(link, target, this::onFailed);
        this.stream.setInterval(options.intervalMs);
        this.adapter = options.adapter == null ? null : options.adapter.clone();
    }

    public void setLinkEvents(Iap2Link.Listener value) { linkEvents = value; }

    public void setSubscriptionListener(Runnable listener) { stream.setSubscriptionListener(listener); }

    public boolean authenticated() { return authenticated; }
    public boolean subscribed() { return stream.subscribed(); }
    public int messagesPushed() { return stream.pushed(); }

    public void setInterval(int value) { stream.setInterval(value); }

    @Override
    public void onReady() {
        if (linkEvents != null) linkEvents.onReady();
    }

    @Override
    public void onFailed(String reason) {
        if (linkEvents != null) linkEvents.onFailed(reason);
    }

    @Override
    public void onCsm(int messageId, Map<Integer, byte[]> parameters) {
        try {
            if (identification(messageId, parameters)) return;
            switch (messageId) {
                case 0xAA00:
                    sendCertificate();
                    break;
                case 0xAA02:
                    answerChallenge(parameters.get(0));
                    break;
                case 0xAA04:
                    authenticated = false;
                    AppLog.error("rx 0xAA04 AuthenticationFailed: the phone rejected the certificate or signature");
                    onFailed("MFi AuthenticationFailed：iPhone 拒绝证书或签名");
                    break;
                case 0xAA05:
                    authenticated = true;
                    AppLog.ok("rx 0xAA05 AuthenticationSucceeded");
                    break;
                case 0xFFFA:
                    stream.start(parameters);
                    break;
                case 0xFFFC:
                    stream.stop();
                    break;
                case 0xEA00:
                case 0xEA01:
                    break;
                default:
                    break;
            }
        } catch (Exception ex) {
            String reason = "handling 0x" + hex4(messageId) + " failed: " + ex.getMessage();
            AppLog.error(reason);
            onFailed(reason);
        }
    }

    private boolean identification(int messageId, Map<Integer, byte[]> parameters) {
        switch (messageId) {
            case 0x1D00:
                AppLog.step("rx 0x1D00 StartIdentification → tx 0x1D01 (transport 17 " +
                        (adapter == null ? "absent" : "present") + ")");
                link.send(0x1D01, Identification.build(identity, adapter));
                return true;
            case 0x1D02:
                AppLog.ok("rx 0x1D02 IdentificationAccepted");
                return true;
            case 0x1D03:
                String detail = describe(parameters);
                AppLog.error("rx 0x1D03 IdentificationRejected, offending parameters: " + detail + hint(parameters));
                onFailed("IdentificationRejected：" + detail + hint(parameters));
                return true;
            default:
                return false;
        }
    }

    private void sendCertificate() {
        if (signer == null) {
            AppLog.warn("rx 0xAA00 certificate request, but no identity was given");
            return;
        }
        AppLog.step("rx 0xAA00 RequestAuthenticationCertificate → tx 0xAA01 (" +
                signer.certificate().length + " bytes)");
        link.send(0xAA01, Csm.param(0, signer.certificate()));
    }

    private void answerChallenge(byte[] challenge) {
        if (signer == null) {
            AppLog.warn("rx 0xAA02 challenge, but no identity was given; authentication cannot succeed");
            return;
        }
        if (challenge == null || challenge.length == 0) {
            throw new IllegalArgumentException("rx 0xAA02 without a challenge in parameter 0");
        }
        byte[] signature = signer.signChallenge(challenge);
        AppLog.step("rx 0xAA02 challenge (" + challenge.length + " B) → tx 0xAA03 signature (" +
                signature.length + " B)");
        link.send(0xAA03, Csm.param(0, signature));
    }

    private static String describe(Map<Integer, byte[]> parameters) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> entry : parameters.entrySet()) {
            parts.add(entry.getKey() + ":" + entry.getValue().length + "B");
        }
        return String.join(", ", parts);
    }

    private static String hint(Map<Integer, byte[]> parameters) {
        List<String> hints = new ArrayList<>();
        if (parameters.containsKey(6)) hints.add("6 = declared messages sent, iOS refuses one of them");
        if (parameters.containsKey(7)) hints.add("7 = declared messages received, iOS refuses one of them");
        if (parameters.containsKey(16)) hints.add("16 = USB transport component");
        if (parameters.containsKey(17)) hints.add("17 = Bluetooth transport component missing or malformed");
        if (parameters.containsKey(22)) hints.add("22 = location component, check nested parameters");
        if (parameters.containsKey(24)) hints.add("24 = Wi-Fi transport component");
        return hints.isEmpty() ? "" : " (" + String.join("; ", hints) + ")";
    }

    private static String hex4(int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    public void dispose() { stream.dispose(); }
}
