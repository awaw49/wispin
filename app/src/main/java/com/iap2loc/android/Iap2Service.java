package com.iap2loc.android;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.iap2loc.android.engine.AppConfig;
import com.iap2loc.android.engine.IdentityStore;
import com.iap2loc.android.engine.TargetCommand;
import com.iap2loc.android.protocol.AccessoryProfile;
import com.iap2loc.android.transport.BluetoothDevices;
import com.iap2loc.android.engine.Favorite;
import com.iap2loc.android.engine.RunRequest;
import com.iap2loc.android.engine.SessionManager;
import com.iap2loc.android.util.AppLog;

import org.json.JSONArray;
import org.json.JSONObject;

/** Foreground service that owns iAP2 sessions so streaming survives Activity recreation. */
public final class Iap2Service extends Service {
    private static final String CHANNEL_ID = "iap2loc";
    private static final int NOTIFICATION_ID = 1;
    private SessionManager manager;
    private BluetoothDevices bluetoothDevices;
    private final Binder binder = new ServiceBinder();

    public final class ServiceBinder extends Binder {
        public Iap2Service service() { return Iap2Service.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        AppLog.setSink(entry -> Log.i("iap2loc", entry.format()));
        manager = new SessionManager(this, 22.3825, 114.1880);
        bluetoothDevices = new BluetoothDevices(this);
        AppLog.info("iAP2 Loc 服务已启动");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification());
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (manager != null) manager.close();
        AppLog.setSink(null);
        super.onDestroy();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "iAP2 Loc", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("iAP2 定位会话正在运行");
        notificationManager.createNotificationChannel(channel);
    }

    private Notification notification() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle("iAP2 Loc")
                .setContentText("iAP2 accessory 会话运行中")
                .setOngoing(true)
                .build();
    }

    public void stopAll() {
        manager.stopAll();
    }

    public String stateJson() {
        return manager.snapshot().toString();
    }

    public String devicesJson() { return bluetoothDevices.json(); }

    public void connect(RunRequest request) { manager.add(request); }

    public void setProfile(AccessoryProfile profile) { manager.config().setProfile(profile); }

    public String inspectIdentity() { return IdentityStore.inspect(this); }

    public String favoritesJson() {
        JSONArray array = new JSONArray();
        for (Favorite favorite : manager.config().favoritesSnapshot()) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", favorite.id);
                item.put("name", favorite.name);
                item.put("latitude", favorite.latitude);
                item.put("longitude", favorite.longitude);
                item.put("altitude", favorite.altitude == null ? JSONObject.NULL : favorite.altitude);
                item.put("accuracy", favorite.accuracy == null ? JSONObject.NULL : favorite.accuracy);
                array.put(item);
            } catch (Exception ignored) { }
        }
        return array.toString();
    }

    public void addFavorite(String name, double latitude, double longitude, Double altitude, Double accuracy) {
        manager.config().addFavorite(name, latitude, longitude, altitude, accuracy);
        AppLog.ok("已保存收藏点 " + name);
    }

    public void removeFavorite(String id) {
        manager.config().removeFavorite(id);
    }

    /** Two user-facing switches map onto the four stored flags as one coherent choice each. */
    public void setConfig(boolean rotateIdentity, boolean randomAdapter) {
        AppConfig config = manager.config();
        config.randomizeIdentity = rotateIdentity;
        config.rotateIdentityOnStart = rotateIdentity;
        config.randomizeAdapter = randomAdapter;
        config.useAdapterOverride = randomAdapter;
        config.save();
    }

    public void rotateIdentity() {
        manager.rotateIdentity();
    }

    public void applyTarget(String json) {
        TargetCommand.parse(json).apply(manager);
        AppLog.ok("已应用目标参数");
    }
}
