package com.iap2loc.android.transport;

import android.Manifest;
import android.content.pm.PackageManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Build;
import android.os.ParcelUuid;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

/** Lists bonded devices with cached service UUIDs; pairing itself lives in system settings. */
public final class BluetoothDevices {
    private final Context context;

    public BluetoothDevices(Context context) {
        this.context = context.getApplicationContext();
    }

    public String json() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("需要 BLUETOOTH_CONNECT 权限");
        }
        try {
            JSONArray result = new JSONArray();
            BluetoothAdapter adapter = requireAdapter();
            for (BluetoothDevice device : adapter.getBondedDevices()) result.put(describe(device));
            return result.toString();
        } catch (JSONException ex) {
            throw new IllegalStateException("设备序列化失败", ex);
        }
    }

    private JSONObject describe(BluetoothDevice device) throws JSONException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("需要 BLUETOOTH_CONNECT 权限");
        }
        JSONObject item = new JSONObject();
        String name = device.getName();
        item.put("address", device.getAddress());
        item.put("name", name == null ? device.getAddress() : name);
        item.put("paired", device.getBondState() == BluetoothDevice.BOND_BONDED);
        ParcelUuid[] cached = device.getUuids();
        item.put("iap2", cachedState(cached));
        item.put("likelyApple", name != null && name.matches("(?i).*(iphone|ipad|ipod|apple).*"));
        return item;
    }

    private static String cachedState(ParcelUuid[] values) {
        if (values == null) return "未查询";
        return containsIap2(values) ? "缓存发现 iAP2" : "缓存未发现 iAP2";
    }

    private static boolean containsIap2(android.os.Parcelable[] values) {
        return Arrays.stream(values).filter(ParcelUuid.class::isInstance)
                .map(ParcelUuid.class::cast).anyMatch(value -> RfcommTransport.IAP2_DEVICE_SERVICE.equals(value.getUuid()));
    }

    private BluetoothAdapter requireAdapter() {
        BluetoothAdapter adapter = RfcommTransport.adapter(context);
        if (adapter == null) throw new IllegalStateException("没有经典蓝牙适配器");
        if (!adapter.isEnabled()) throw new IllegalStateException("本机蓝牙未开启");
        return adapter;
    }
}
