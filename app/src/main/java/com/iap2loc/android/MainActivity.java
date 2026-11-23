package com.iap2loc.android;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import com.iap2loc.android.map.MapRouteJson;
import com.iap2loc.android.map.RouteEditor;
import com.iap2loc.android.protocol.Geo;
import com.iap2loc.android.protocol.RoutePath;
import com.iap2loc.android.ui.ConnectionPanel;
import com.iap2loc.android.ui.ControlContext;
import com.iap2loc.android.ui.DocumentController;
import com.iap2loc.android.ui.FavoritePanel;
import com.iap2loc.android.ui.Fields;
import com.iap2loc.android.ui.IdentityPanel;
import com.iap2loc.android.ui.LivePanel;
import com.iap2loc.android.ui.MapSurface;
import com.iap2loc.android.ui.TargetPanel;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Map-first shell: bottom card holds the essentials, everything else lives in the overlay sheet. */
public final class MainActivity extends Activity {
    private static final int PERMISSION_REQUEST = 1;
    private static final long POLL_INTERVAL_MILLIS = 700;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Iap2Service service;
    private boolean binding;
    private boolean pendingStart;
    private ControlContext ui;
    private ConnectionPanel devices;
    private TargetPanel target;
    private IdentityPanel identity;
    private LivePanel live;
    private FavoritePanel favorites;
    private DocumentController documents;
    private MapSurface map;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((Iap2Service.ServiceBinder) binder).service();
            handler.removeCallbacks(poll);
            handler.post(poll);
            identity.load();
            ui.run(() -> devices.refresh(true));
            if (pendingStart) {
                pendingStart = false;
                handler.postDelayed(() -> ui.run(MainActivity.this::primaryAction), 300);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            handler.removeCallbacks(poll);
            new Fields(MainActivity.this).set(R.id.statusText, "服务连接已断开");
        }
    };

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (service != null) {
                try {
                    refresh();
                } catch (Exception ex) {
                    new Fields(MainActivity.this).set(R.id.statusText, "状态读取失败：" + ex.getMessage());
                }
            }
            handler.postDelayed(this, POLL_INTERVAL_MILLIS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ui = new ControlContext(this, () -> service);
        map = new MapSurface(this, new MapSurface.Listener() {
            @Override public void onDraft(List<RoutePath.Vertex> points) { draftChanged(points); }
            @Override public void onError(String message) { ui.message(message); }
        });
        map.attach();
        target = new TargetPanel(ui);
        target.setRouteListener(this::pushRouteToMap);
        devices = new ConnectionPanel(ui);
        identity = new IdentityPanel(ui);
        live = new LivePanel(ui);
        favorites = new FavoritePanel(ui, target);
        favorites.setAppliedListener(this::jumpToFavorite);
        documents = new DocumentController(ui, target);
        bindChrome();
        applyInsets();
        requestAccess();
    }

    private void bindChrome() {
        findViewById(R.id.segPoint).setOnClickListener(v -> switchMode(false));
        findViewById(R.id.segPath).setOnClickListener(v -> switchMode(true));
        findViewById(R.id.datumToggle).setOnClickListener(v -> switchDatum());
        findViewById(R.id.settingsButton).setOnClickListener(v -> toggleOverlay(true));
        findViewById(R.id.closeOverlay).setOnClickListener(v -> toggleOverlay(false));
        findViewById(R.id.overlayScrim).setOnClickListener(v -> toggleOverlay(false));
        findViewById(R.id.ctaButton).setOnClickListener(v -> ui.run(this::primaryAction));
        findViewById(R.id.undoRoute).setOnClickListener(v -> map.undo());
        findViewById(R.id.clearRoute).setOnClickListener(v -> map.clear());
        findViewById(R.id.retryPermissions).setOnClickListener(v -> requestAccess());
        collapse(R.id.paramsHeader, R.id.paramsBody);
        collapse(R.id.identityHeader, R.id.identityBody);
    }

    private void collapse(int headerId, int bodyId) {
        View body = findViewById(bodyId);
        findViewById(headerId).setOnClickListener(v -> {
            boolean show = body.getVisibility() != View.VISIBLE;
            body.setVisibility(show ? View.VISIBLE : View.GONE);
            TextView header = (TextView) v;
            String text = header.getText().toString();
            header.setText(text.substring(0, text.length() - 1) + (show ? "▾" : "▸"));
        });
    }

    private void switchMode(boolean path) {
        target.setMode(path);
        map.setPathMode(path);
    }

    private void switchDatum() {
        String next = "gcj".equals(map.datum()) ? "wgs" : "gcj";
        map.setDatum(next);
        ((TextView) findViewById(R.id.datumToggle)).setText("gcj".equals(next) ? "高德" : "OSM");
    }

    private void toggleOverlay(boolean show) {
        findViewById(R.id.advancedOverlay).setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void draftChanged(List<RoutePath.Vertex> points) {
        ui.run(() -> {
            JSONArray json = new JSONArray(MapRouteJson.encode(new RouteEditor(points), "wgs"));
            target.applyMapDraft(json);
            if (ui.bound()) target.applyDraft();
        });
    }

    private void pushRouteToMap(JSONArray route) {
        ui.run(() -> {
            map.setPoints(MapRouteJson.parse(route.toString()).points());
            map.setPathMode(target.pathMode());
            map.setLoopClosed(target.pathLoop());
        });
    }

    private void jumpToFavorite(JSONObject favorite) {
        ui.run(() -> {
            Geo.Point point = new Geo.Point(favorite.getDouble("latitude"), favorite.getDouble("longitude"));
            switchMode(false);
            map.setPoints(Collections.singletonList(new RoutePath.Vertex(point, null)));
            map.centerOn(point);
        });
    }

    private void primaryAction() throws Exception {
        if (live.anyRunning()) {
            ui.service().stopAll();
            return;
        }
        if (!ui.bound()) {
            pendingStart = true;
            requestAccess();
            return;
        }
        target.apply();
        if (!target.pathMode()) {
            Geo.Point point = new Geo.Point(target.latitude(), target.longitude());
            map.setPoints(Collections.singletonList(new RoutePath.Vertex(point, null)));
            map.centerOn(point);
        }
        devices.connectAuto();
    }

    private void applyInsets() {
        float density = getResources().getDisplayMetrics().density;
        View top = findViewById(R.id.topBar);
        View bottom = findViewById(R.id.bottomSheet);
        top.setOnApplyWindowInsetsListener((v, insets) -> {
            int topInset = insets.getSystemWindowInsetTop();
            v.setPadding(v.getPaddingLeft(), topInset + (int) (12 * density), v.getPaddingRight(), v.getPaddingBottom());
            return insets;
        });
        bottom.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottomInset = insets.getSystemWindowInsetBottom();
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottomInset + (int) (16 * density));
            return insets;
        });
    }

    private void requestAccess() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addMissing(missing, Manifest.permission.BLUETOOTH_SCAN);
            addMissing(missing, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            addMissing(missing, Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addMissing(missing, Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
            return;
        }
        startSessionService();
    }

    private void addMissing(List<String> missing, String permission) {
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) missing.add(permission);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != PERMISSION_REQUEST) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            findViewById(R.id.retryPermissions).setVisibility(View.VISIBLE);
            ui.message("蓝牙连接权限被拒绝，服务尚未启动。可点击“申请权限 / 启动服务”重试");
            return;
        }
        startSessionService();
    }

    private void startSessionService() {
        if (binding) return;
        ui.run(() -> {
            Intent intent = new Intent(this, Iap2Service.class);
            startForegroundService(intent);
            binding = bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!binding) throw new IllegalStateException("无法绑定 iAP2 服务");
        });
    }

    private void refresh() throws Exception {
        JSONObject state = new JSONObject(service.stateJson());
        target.refresh(state.getJSONObject("target"));
        identity.refresh(state.getJSONObject("identity"));
        live.refresh(state.getJSONArray("sessions"));
        favorites.refresh();
        devices.refresh(false);
    }

    @Override
    public void onBackPressed() {
        if (findViewById(R.id.advancedOverlay).getVisibility() == View.VISIBLE) {
            toggleOverlay(false);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        documents.receive(requestCode, resultCode, data);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(poll);
        if (map != null) map.destroy();
        if (binding) unbindService(connection);
        binding = false;
        service = null;
        super.onDestroy();
    }
}
