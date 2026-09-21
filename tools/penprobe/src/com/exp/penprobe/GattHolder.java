package com.exp.penprobe;

import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.util.Log;

import java.util.Arrays;
import java.util.List;

/** 单例 GATT 持有者：跨广播实例保存连接与特征值。 */
public final class GattHolder {
    public static final String TAG = "PenProbe";

    public static BluetoothGatt gatt;
    public static volatile boolean servicesReady = false;

    private GattHolder() {}

    public static final BluetoothGattCallback CB = new BluetoothGattCallback() {
        @Override public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            Log.i(TAG, "CONN state=" + newState + " status=" + status
                    + " (0=disconnected 2=connected)");
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "discoverServices...");
                g.discoverServices();
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                servicesReady = false;
            }
        }
        @Override public void onServicesDiscovered(BluetoothGatt g, int status) {
            Log.i(TAG, "SERVICES_DISCOVERED status=" + status);
            servicesReady = (status == BluetoothGatt.GATT_SUCCESS);
            dumpDb();
            enableNotify("0000000a", "INF_NOTIFY");
            enableNotify("18093046", "COLOROS_INTERFACE");
            enableNotify("1809396a", "COLOROS_CHARGE");
            enableNotify("180937bc", "COLOROS_BATTERY");
        }
        @Override public void onCharacteristicWrite(BluetoothGatt g,
                BluetoothGattCharacteristic c, int status) {
            Log.i(TAG, "WRITE_DONE uuid=" + c.getUuid() + " status=" + status
                    + " (0=success)");
        }
        @Override public void onCharacteristicRead(BluetoothGatt g,
                BluetoothGattCharacteristic c, int status) {
            Log.i(TAG, "READ uuid=" + c.getUuid() + " status=" + status
                    + " value=" + b2s(c.getValue()));
        }
        @Override public void onDescriptorWrite(BluetoothGatt g,
                BluetoothGattDescriptor d, int status) {
            Log.i(TAG, "DESC_WRITE uuid=" + d.getUuid() + " status=" + status);
        }
        @Override public void onCharacteristicChanged(BluetoothGatt g,
                BluetoothGattCharacteristic c) {
            Log.i(TAG, "NOTIFY(uuid=" + c.getUuid() + ") value=" + b2s(c.getValue()));
        }
    };

    public static BluetoothGattCharacteristic find(String shortUuid) {
        if (gatt == null) return null;
        for (BluetoothGattService s : gatt.getServices()) {
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                if (c.getUuid().toString().startsWith(shortUuid)) return c;
            }
        }
        return null;
    }

    public static void dumpDb() {
        if (gatt == null) { Log.i(TAG, "DUMP no-gatt"); return; }
        List<BluetoothGattService> svcs = gatt.getServices();
        Log.i(TAG, "DUMP_BEGIN services=" + svcs.size());
        for (BluetoothGattService s : svcs) {
            Log.i(TAG, "SVC " + s.getUuid());
            for (BluetoothGattCharacteristic c : s.getCharacteristics()) {
                int p = c.getProperties();
                Log.i(TAG, "  CHR " + c.getUuid() + " props="
                        + ((p & 0x08) != 0 ? "W " : "")
                        + ((p & 0x04) != 0 ? "WN " : "")
                        + ((p & 0x02) != 0 ? "R " : "")
                        + ((p & 0x10) != 0 ? "N " : "")
                        + ((p & 0x80) != 0 ? "I " : ""));
            }
        }
        Log.i(TAG, "DUMP_END");
    }

    public static void enableNotify(String shortUuid, String label) {
        BluetoothGattCharacteristic c = find(shortUuid);
        if (c == null) { Log.i(TAG, "NOTIFY_SKIP " + label + " not-found"); return; }
        BluetoothGattDescriptor d = c.getDescriptor(
                java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
        if (d == null) { Log.i(TAG, "NOTIFY_SKIP " + label + " no-2902"); return; }
        d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
        boolean ok = gatt.writeDescriptor(d);
        Log.i(TAG, "NOTIFY_ENABLE " + label + " uuid=" + c.getUuid() + " ok=" + ok);
    }

    public static String b2s(byte[] v) {
        if (v == null) return "null";
        StringBuilder sb = new StringBuilder();
        for (byte b : v) sb.append(String.format("%02x", b));
        return sb.length() + "[" + sb + "]";
    }
}
