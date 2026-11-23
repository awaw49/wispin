package com.iap2loc.android.engine;

import android.content.Context;

import com.iap2loc.android.protocol.AccessoryProfile;
import com.iap2loc.android.transport.AndroidSessionConnections;
import com.iap2loc.android.protocol.LocationTarget;
import com.iap2loc.android.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Owns all running sessions; every session follows the same target. */
public final class SessionManager implements AutoCloseable {
    private final Object gate = new Object();
    private final List<RunSession> sessions = new ArrayList<>();
    private final Context context;
    private final AppConfig config;
    private int nextId;
    private int currentRate = 250;

    public SessionManager(Context context, double latitude, double longitude) {
        this.context = context.getApplicationContext();
        this.config = AppConfig.load(context);
        this.config.prepareForRun(parseMac(
                com.iap2loc.android.transport.RfcommTransport.localAddress(this.context)));
        this.target = new LocationTarget(latitude, longitude);
    }

    public final LocationTarget target;
    public AppConfig config() { return config; }
    public Context context() { return context; }

    public AccessoryProfile profile() {
        return config.effectiveProfile();
    }

    public AccessoryProfile rotateIdentity() {
        return config.rotateIdentity(parseMac(com.iap2loc.android.transport.RfcommTransport.localAddress(context)));
    }

    public RunSession add(RunRequest request) {
        RunRequest copy = RunRequests.validatedCopy(request, profile());

        RunSession session;
        synchronized (gate) {
            SessionDependencies dependencies = new SessionDependencies(target,
                    () -> IdentityStore.load(context), new AndroidSessionConnections(context));
            session = new RunSession("s" + (++nextId), copy, dependencies);
            session.onFinished = this::onSessionFinished;
            session.onSubscription = this::syncPathClock;
            sessions.add(session);
        }
        session.start();
        return session;
    }

    private void onSessionFinished() {
        syncPathClock();
    }

    /** The path clock arms on the first subscription, then keeps wall-clock time while
     * the session is alive. iOS only adopts a fix at discrete events (subscription
     * windows opening, location-service refresh) and ignores the stream in between,
     * so each new window must find a position that has already advanced along the
     * route — a frozen position would be re-adopted unchanged, pinning the dot. */
    private void syncPathClock() {
        synchronized (gate) {
            for (RunSession candidate : sessions) {
                if (candidate.running() && candidate.everSubscribed()) {
                    target.setActive(true);
                    return;
                }
            }
        }
        target.setActive(false);
    }

    public void stopAll() {
        List<RunSession> snapshot;
        synchronized (gate) {
            snapshot = new ArrayList<>(sessions);
            sessions.clear();
        }
        target.setActive(false);
        for (RunSession session : snapshot) {
            session.stop();
            session.dispose();
        }
    }

    public void applyTarget(LocationTarget.Update update, Integer rate) {
        target.apply(update);
        if (rate != null) currentRate = rate;
        if (rate != null) {
            List<RunSession> snapshot;
            synchronized (gate) {
                snapshot = new ArrayList<>(sessions);
            }
            for (RunSession session : snapshot) session.setInterval(rate);
        }
    }

    public JSONObject snapshot() {
        List<RunSession> currentSessions;
        synchronized (gate) { currentSessions = new ArrayList<>(sessions); }
        try {
            JSONObject result = new JSONObject();
            result.put("identity", SessionSnapshot.identity(profile(), IdentityStore.describe(context),
                    IdentityStore.available(context)));
            JSONObject targetState = SessionSnapshot.target(target.snapshot());
            targetState.put("rate", currentRate);
            result.put("target", targetState);
            result.put("sessions", SessionSnapshot.sessions(currentSessions));
            return result;
        } catch (org.json.JSONException ex) {
            throw new IllegalStateException("状态序列化失败：" + ex.getMessage(), ex);
        }
    }

    @Override
    public void close() {
        stopAll();
    }

    private static byte[] parseMac(String mac) {
        if (mac == null) return null;
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) return null;
        byte[] result = new byte[6];
        for (int i = 0; i < 6; i++) {
            result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return result;
    }
}
