package com.iap2loc.android.engine;

import com.iap2loc.android.protocol.Iap2Link;
import com.iap2loc.android.protocol.LocationSession;
import com.iap2loc.android.protocol.MfiSigner;
import com.iap2loc.android.util.AppLog;

import java.util.Map;
import java.util.concurrent.CancellationException;

/** One session, with cancellable startup and retained results after stop or failure. */
public final class RunSession implements AutoCloseable {
    private final String id;
    private final RunRequest request;
    private final SessionDependencies dependencies;
    private final CancellationScope resources = new CancellationScope(AppLog::error);
    private final Object gate = new Object();
    private volatile Iap2Link link;
    private volatile LocationSession location;
    private volatile Thread worker;
    private volatile boolean running;
    private volatile boolean stopping;
    private volatile String error;
    private volatile String linkState = "Idle";
    private volatile boolean everSubscribed;
    public volatile Runnable onFinished;
    public volatile Runnable onSubscription;

    public RunSession(String id, RunRequest request, SessionDependencies dependencies) {
        this.id = id;
        this.request = request;
        this.dependencies = dependencies;
    }

    public String id() { return id; }
    public RunRequest request() { return request; }
    public String label() { return request.label; }
    public boolean running() { return running; }
    public String error() { return error; }
    public String linkState() { return linkState; }
    public boolean authenticated() { return location != null && location.authenticated(); }
    public boolean subscribed() { return location != null && location.subscribed(); }
    public boolean everSubscribed() { return everSubscribed; }
    public int sent() { return location == null ? 0 : location.messagesPushed(); }

    public void start() {
        synchronized (gate) {
            if (worker != null || stopping) return;
            running = true;
            worker = new Thread(this::startInternal, "iap2-session-" + id);
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void startInternal() {
        try {
            MfiSigner signer = dependencies.identity.load();
            resources.check();
            if (signer == null) AppLog.warn("[" + label() + "] 未导入 MFi 材料，不能完成认证");
            linkState = "Connecting";
            SessionDependencies.Connection connection = dependencies.connections.open(request, resources);
            resources.check();
            Iap2Link.Options linkOptions = new Iap2Link.Options();
            linkOptions.initiate = true;
            linkOptions.role = "accessory-" + id;
            linkOptions.listener = listener();
            link = resources.own(new Iap2Link(connection.transport, linkOptions));
            LocationSession.Options locationOptions = new LocationSession.Options();
            locationOptions.identity = request.profile;
            locationOptions.signer = signer;
            locationOptions.intervalMs = request.rate;
            locationOptions.adapter = connection.adapter;
            location = new LocationSession(link, dependencies.target, locationOptions);
            LocationSession activeLocation = location;
            resources.own(activeLocation::dispose);
            location.setLinkEvents(listener());
            location.setSubscriptionListener(this::notifySubscription);
            linkState = "DETECT";
            resources.check();
            link.start();
        } catch (CancellationException ex) {
            if (!stopping) fail(ex.getMessage());
        } catch (Exception ex) {
            if (!stopping) fail(ex.getMessage());
        }
    }

    private Iap2Link.Listener listener() {
        return new Iap2Link.Listener() {
            @Override public void onReady() {
                if (stopping) return;
                linkState = "NORMAL";
                AppLog.ok("[" + label() + "] iAP2 link ready；等待识别请求");
            }
            @Override public void onFailed(String reason) {
                if (!stopping) fail(reason);
            }
            @Override public void onCsm(int messageId, Map<Integer, byte[]> parameters) {
                LocationSession active = location;
                if (active != null && !stopping) active.onCsm(messageId, parameters);
            }
        };
    }

    private void fail(String reason) {
        error = reason == null ? "未知会话错误" : reason;
        linkState = "DEAD";
        AppLog.error("[" + label() + "] " + error);
        finish();
    }

    private void notifySubscription() {
        if (subscribed()) everSubscribed = true;
        Runnable listener = onSubscription;
        if (listener != null) listener.run();
    }

    public void setInterval(int intervalMs) {
        LocationSession active = location;
        if (active != null && !stopping) active.setInterval(intervalMs);
    }

    public void stop() {
        if (stopping) return;
        linkState = "Stopped";
        AppLog.info("[" + label() + "] 已停止；推送 " + sent() + " 帧");
        finish();
    }

    private void finish() {
        stopping = true;
        running = false;
        Thread active = worker;
        if (active != null && active != Thread.currentThread()) active.interrupt();
        resources.close();
        Runnable listener = onFinished;
        if (listener != null) listener.run();
    }

    @Override public void close() { stop(); }
    public void dispose() { stop(); }
}
