package com.iap2loc.android.transport;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import com.iap2loc.android.util.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/** Classic Bluetooth RFCOMM client for the iAP2 device service on a paired iPhone. */
public final class RfcommTransport implements ITransport {
    public static final UUID IAP2_DEVICE_SERVICE = UUID.fromString("00000000-deca-fade-deca-deafdecacafe");
    private final BluetoothSocket socket;
    private final InputStream input;
    private final OutputStream output;
    private final String description;
    private final Object writeGate = new Object();
    private volatile boolean closed;

    RfcommTransport(BluetoothSocket socket, String address) throws IOException {
        this.socket = socket;
        this.input = socket.getInputStream();
        this.output = socket.getOutputStream();
        this.description = "RFCOMM " + address;
    }

    public static String normalize(String mac) {
        if (mac == null || !mac.matches("(?i)([0-9a-f]{12}|[0-9a-f]{2}([:-][0-9a-f]{2}){5})")) {
            throw new IllegalArgumentException("蓝牙地址格式无效");
        }
        String hex = mac.replaceAll("[^0-9A-Fa-f]", "");
        if (hex.length() != 12) throw new IllegalArgumentException("蓝牙地址必须是 12 位十六进制字符");
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 12; i += 2) {
            if (i > 0) result.append(':');
            result.append(hex.substring(i, i + 2).toUpperCase());
        }
        return result.toString();
    }

    public static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        return manager == null ? null : manager.getAdapter();
    }

    public static String localAddress(Context context) {
        return LocalBluetoothAddress.resolve(context).address;
    }

    static BluetoothSocket createSocket(Context context, String mac) throws IOException {
        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) throw new IOException("本机没有经典蓝牙适配器");
        if (!adapter.isEnabled()) throw new IOException("本机蓝牙未开启");
        String normalized = normalize(mac);
        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(normalized);
        } catch (IllegalArgumentException ex) {
            throw new IOException("蓝牙地址无效: " + normalized);
        }
        try {
            adapter.cancelDiscovery();
        } catch (SecurityException ex) {
            throw new IOException("缺少 BLUETOOTH_SCAN 权限，不能停止蓝牙扫描", ex);
        }

        try {
            return device.createRfcommSocketToServiceRecord(IAP2_DEVICE_SERVICE);
        } catch (SecurityException ex) {
            throw new IOException("缺少蓝牙连接权限: " + ex.getMessage(), ex);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    @Override
    public int read(byte[] buffer, int offset, int count) throws IOException {
        if (closed) return -1;
        return input.read(buffer, offset, count);
    }

    @Override
    public void write(byte[] buffer, int offset, int count) throws IOException {
        synchronized (writeGate) {
            if (closed) throw new IOException("RFCOMM 已关闭");
            output.write(buffer, offset, count);
            output.flush();
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { input.close(); } catch (Exception ignored) { }
        try { output.close(); } catch (Exception ignored) { }
        try { socket.close(); } catch (Exception ignored) { }
    }
}
