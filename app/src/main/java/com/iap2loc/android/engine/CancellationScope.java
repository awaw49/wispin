package com.iap2loc.android.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/** Owns startup resources so cancellation also closes sockets that are still connecting. */
public final class CancellationScope implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private final Consumer<String> reportError;
    private boolean cancelled;

    public CancellationScope(Consumer<String> reportError) {
        this.reportError = reportError;
    }

    public <T extends AutoCloseable> T own(T resource) {
        synchronized (this) {
            if (!cancelled) {
                resources.add(resource);
                return resource;
            }
        }
        closeResource(resource);
        throw new CancellationException("会话已停止");
    }

    public synchronized void check() {
        if (cancelled || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("会话已停止");
        }
    }

    @Override
    public void close() {
        List<AutoCloseable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = new ArrayList<>(resources);
            resources.clear();
        }
        Collections.reverse(pending);
        pending.forEach(this::closeResource);
    }

    private void closeResource(AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception ex) {
            reportError.accept("释放会话资源失败：" + ex.getMessage());
        }
    }
}
