package com.iap2loc.android.transport;

import android.bluetooth.BluetoothSocket;
import android.content.Context;

import com.iap2loc.android.util.AppLog;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Holds the socket before connect(), allowing Stop to cancel SDP and RFCOMM establishment. */
public final class RfcommConnectionAttempt implements AutoCloseable {
    private final BluetoothSocket socket;
    private final String address;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean timedOut;

    private RfcommConnectionAttempt(BluetoothSocket socket, String address) {
        this.socket = socket;
        this.address = address;
    }

    public static RfcommConnectionAttempt prepare(Context context, String address) throws IOException {
        return new RfcommConnectionAttempt(RfcommTransport.createSocket(context, address),
                RfcommTransport.normalize(address));
    }

    public RfcommTransport connect(int timeoutSeconds) throws IOException {
        if (timeoutSeconds <= 0) throw new IllegalArgumentException("连接超时必须大于 0 秒");
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "iap2-rfcomm-timeout");
            thread.setDaemon(true);
            return thread;
        });
        timer.schedule(this::expire, timeoutSeconds, TimeUnit.SECONDS);
        try {
            if (cancelled.get()) throw new IOException("连接已取消");
            AppLog.step("SDP/RFCOMM → " + address + "，iAP2 UUID " + RfcommTransport.IAP2_DEVICE_SERVICE);
            socket.connect();
            if (cancelled.get()) throw new IOException("连接已取消");
            AppLog.ok("RFCOMM 已连接；接下来验证 iAP2 marker、识别和 MFi 认证");
            return new RfcommTransport(socket, address);
        } catch (IOException | SecurityException ex) {
            close();
            String reason = timedOut ? "SDP/RFCOMM 连接超时" : ex.getMessage();
            throw new IOException("iAP2 RFCOMM " + address + " 失败：" + reason, ex);
        } finally {
            timer.shutdownNow();
        }
    }

    private void expire() {
        timedOut = true;
        close();
    }

    @Override
    public void close() {
        if (!cancelled.compareAndSet(false, true)) return;
        try {
            socket.close();
        } catch (IOException ex) {
            AppLog.warn("关闭 RFCOMM 连接尝试失败：" + ex.getMessage());
        }
    }
}
