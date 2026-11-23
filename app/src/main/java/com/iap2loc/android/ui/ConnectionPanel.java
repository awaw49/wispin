package com.iap2loc.android.ui;

import android.content.Intent;
import android.provider.Settings;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import com.iap2loc.android.R;
import com.iap2loc.android.engine.RunRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Bonded-device picker; the visible selection is what the start button connects to. */
public final class ConnectionPanel {
    private static final String PREFS = "ui";
    private static final String LAST_MAC = "lastMac";
    private final ControlContext ui;
    private final Fields fields;
    private final List<String> addresses = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private String previousDevices;

    public ConnectionPanel(ControlContext ui) {
        this.ui = ui;
        fields = new Fields(ui.activity);
        ui.activity.findViewById(R.id.refreshDevices)
                .setOnClickListener(v -> ui.run(() -> refresh(true)));
        ui.activity.findViewById(R.id.bluetoothSettings).setOnClickListener(v ->
                ui.run(() -> ui.activity.startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS))));
    }

    public void refresh(boolean force) throws Exception {
        String json = ui.service().devicesJson();
        if (!force && json.equals(previousDevices)) return;
        previousDevices = json;
        Spinner spinner = ui.activity.findViewById(R.id.deviceSpinner);
        String selected = currentAddress(spinner);
        JSONArray devices = new JSONArray(json);
        addresses.clear();
        names.clear();
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < devices.length(); index++) {
            JSONObject device = devices.getJSONObject(index);
            addresses.add(device.getString("address"));
            names.add(device.optString("name", "未知设备"));
            labels.add(device.optString("name", "未知设备") + " · " + device.getString("address") +
                    " · iAP2 " + device.optString("iap2", "未查询"));
        }
        if (labels.isEmpty()) labels.add("无已配对设备：请先完成系统蓝牙配对");
        ArrayAdapter<String> adapter = new ArrayAdapter<>(ui.activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(defaultPosition(selected, devices));
    }

    /** Keeps an explicit user pick; otherwise prefers Apple-looking then last-used devices. */
    private int defaultPosition(String selected, JSONArray devices) {
        String remembered = ui.activity.getSharedPreferences(PREFS, 0).getString(LAST_MAC, null);
        int apple = -1;
        int memory = -1;
        for (int index = 0; index < devices.length(); index++) {
            JSONObject device = devices.optJSONObject(index);
            if (device == null) continue;
            String address = device.optString("address");
            if (address.equals(selected)) return index;
            if (apple < 0 && device.optBoolean("likelyApple")) apple = index;
            if (memory < 0 && address.equals(remembered)) memory = index;
        }
        if (apple >= 0) return apple;
        if (memory >= 0) return memory;
        return 0;
    }

    private String currentAddress(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        return position >= 0 && position < addresses.size() ? addresses.get(position) : null;
    }

    /** One-tap flow: connect to the device currently shown in the picker. */
    public void connectAuto() {
        Spinner spinner = ui.activity.findViewById(R.id.deviceSpinner);
        String mac = currentAddress(spinner);
        if (mac == null) {
            throw new IllegalStateException("没有已配对的 iPhone：请先在系统蓝牙设置中完成配对");
        }
        RunRequest request = new RunRequest();
        request.mac = mac;
        request.label = names.get(spinner.getSelectedItemPosition());
        request.rate = fields.integer(R.id.rateEdit);
        ui.service().connect(request);
        remember(mac);
    }

    private void remember(String mac) {
        ui.activity.getSharedPreferences(PREFS, 0).edit().putString(LAST_MAC, mac).apply();
    }
}
