package com.iap2loc.android.transport;

import android.content.Context;
import android.os.PowerManager;

import com.iap2loc.android.engine.CancellationScope;
import com.iap2loc.android.engine.RunRequest;
import com.iap2loc.android.engine.SessionDependencies;
import com.iap2loc.android.util.AppLog;

import java.io.IOException;

/** Android composition boundary for RFCOMM sessions and adapter address reads. */
public final class AndroidSessionConnections implements SessionDependencies.ConnectionFactory {
    private final Context context;

    public AndroidSessionConnections(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public SessionDependencies.Connection open(RunRequest request, CancellationScope scope) throws Exception {
        scope.check();
        keepCpuAwake(scope);
        byte[] adapter = address(request);
        scope.check();
        RfcommConnectionAttempt attempt = scope.own(RfcommConnectionAttempt.prepare(context, request.mac));
        ITransport transport = scope.own(attempt.connect(request.connectTimeoutSeconds));
        return new SessionDependencies.Connection(transport, adapter);
    }

    private void keepCpuAwake(CancellationScope scope) {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (power == null) throw new IllegalStateException("无法获取 Android PowerManager");
        PowerManager.WakeLock lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "iap2loc:session");
        lock.setReferenceCounted(false);
        lock.acquire();
        scope.own(() -> { if (lock.isHeld()) lock.release(); });
    }

    private byte[] address(RunRequest request) throws IOException {
        byte[] reported = request.profile.adapterOverride();
        if (reported != null) return reported;
        LocalBluetoothAddress.Result result = LocalBluetoothAddress.resolve(context);
        result.diagnostics.forEach(AppLog::warn);
        if (!result.isAvailable()) {
            throw new IOException("无法读取本机蓝牙地址，iAP2 识别需要该值。" +
                    "请开启“上报随机蓝牙地址”后重试");
        }
        AppLog.info("本机蓝牙地址 " + result.address + "，来源 " + result.source);
        String[] parts = result.address.split(":");
        byte[] bytes = new byte[parts.length];
        for (int i = 0; i < parts.length; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        return bytes;
    }
}
