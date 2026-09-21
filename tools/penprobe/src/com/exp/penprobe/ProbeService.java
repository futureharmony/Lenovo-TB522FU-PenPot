package com.exp.penprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/** 前台服务：持有 GATT 连接不被回收。 */
public class ProbeService extends Service {
    private final Handler h = new Handler(Looper.getMainLooper());

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("pp", "pp",
                NotificationManager.IMPORTANCE_MIN));
        Notification n = new Notification.Builder(this, "pp")
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("PenProbe alive")
                .build();
        startForeground(1, n);
        Log.i(GattHolder.TAG, "SERVICE_CREATED");
    }

    @Override public int onStartCommand(Intent i, int flags, int startId) {
        if (i == null) return START_STICKY;
        String action = i.getAction();
        Log.i(GattHolder.TAG, "SVC_RECV action=" + action);
        if ("penprobe.CONNECT".equals(action)) {
            connect(i.getStringExtra("address"));
        } else if ("penprobe.WRITE".equals(action)) {
            write(i.getStringExtra("uuid"), i.getStringExtra("hex"));
        } else if ("penprobe.READ".equals(action)) {
            read(i.getStringExtra("uuid"));
        } else if ("penprobe.LIST".equals(action)) {
            GattHolder.dumpDb();
        } else if ("penprobe.DISCONNECT".equals(action)) {
            if (GattHolder.gatt != null) { GattHolder.gatt.close(); GattHolder.gatt = null; }
            Log.i(GattHolder.TAG, "DISCONNECTED");
        } else if ("penprobe.STOP".equals(action)) {
            if (GattHolder.gatt != null) { GattHolder.gatt.close(); GattHolder.gatt = null; }
            stopSelf();
        }
        return START_STICKY;
    }

    private void connect(String address) {
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        if (bm == null || bm.getAdapter() == null || address == null) { return; }
        BluetoothDevice dev = bm.getAdapter().getRemoteDevice(address);
        if (GattHolder.gatt != null) { GattHolder.gatt.close(); GattHolder.gatt = null; }
        GattHolder.gatt = dev.connectGatt(this, false, GattHolder.CB, BluetoothDevice.TRANSPORT_LE);
        Log.i(GattHolder.TAG, "CONNECT requested addr=" + address);
    }

    private void write(String uuid, String hex) {
        byte[] val = ProbeReceiver.hexToBytes(hex == null ? "" : hex);
        BluetoothGattCharacteristic c = GattHolder.find(uuid);
        if (GattHolder.gatt == null || !GattHolder.servicesReady || c == null) {
            Log.w(GattHolder.TAG, "WRITE_DEFERRED gatt=" + (GattHolder.gatt != null)
                    + " ready=" + GattHolder.servicesReady + " char=" + (c != null)
                    + " -> auto connect");
            connect("DC:EB:4D:06:77:DD");
            final String u = uuid;
            h.postDelayed(() -> doWrite(u, val), 4000);
            return;
        }
        doWrite(uuid, val);
    }

    private void doWrite(String uuid, byte[] val) {
        BluetoothGattCharacteristic c = GattHolder.find(uuid);
        if (c == null || GattHolder.gatt == null) {
            Log.e(GattHolder.TAG, "WRITE_FAIL char=" + uuid + " gatt=" + (GattHolder.gatt != null));
            return;
        }
        int props = c.getProperties();
        int wt = (props & 0x10) != 0
                ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
        // 策略1: 新API；策略2: 旧API(显式类型)；策略3: 旧API(DEFAULT类型)
        try {
            int rc = GattHolder.gatt.writeCharacteristic(c, val, wt);
            Log.i(GattHolder.TAG, "WRITE_REQ new uuid=" + uuid + " value=" + GattHolder.b2s(val)
                    + " rc=" + rc + " wt=" + wt);
            return;
        } catch (Throwable t1) {
            Log.w(GattHolder.TAG, "new api failed: " + t1.getMessage());
        }
        try {
            c.setValue(val);
            c.setWriteType(wt);
            boolean ok = GattHolder.gatt.writeCharacteristic(c);
            Log.i(GattHolder.TAG, "WRITE_REQ old uuid=" + uuid + " value=" + GattHolder.b2s(val)
                    + " ok=" + ok + " wt=" + wt);
            if (ok) return;
        } catch (Throwable t2) {
            Log.w(GattHolder.TAG, "old api failed: " + t2.getMessage());
        }
        try {
            c.setValue(val);
            c.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            boolean ok = GattHolder.gatt.writeCharacteristic(c);
            Log.i(GattHolder.TAG, "WRITE_REQ dft uuid=" + uuid + " value=" + GattHolder.b2s(val)
                    + " ok=" + ok + " wt=DEFAULT");
        } catch (Throwable t3) {
            Log.e(GattHolder.TAG, "all write strategies failed: " + t3.getMessage());
        }
    }

    private void read(String uuid) {
        BluetoothGattCharacteristic c = GattHolder.find(uuid);
        if (c == null || GattHolder.gatt == null) { Log.e(GattHolder.TAG, "READ_FAIL " + uuid); return; }
        boolean ok = GattHolder.gatt.readCharacteristic(c);
        Log.i(GattHolder.TAG, "READ_REQ uuid=" + uuid + " ok=" + ok);
    }
}
