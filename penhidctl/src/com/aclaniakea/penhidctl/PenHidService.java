package com.aclaniakea.penhidctl;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;

/**
 * Short-lived root-started controller for the system HID Host profile.
 * It intentionally does not synthesize pen input, battery, or connection
 * broadcasts; the Bluetooth stack remains the source of truth.
 */
public final class PenHidService extends Service {
    private static final String TAG = "PenHidCtl";
    private static final int HID_HOST = 4;
    private static final int POLICY_FORBIDDEN = 0;
    private static final int POLICY_ALLOWED = 100;

    private Handler handler;
    private BluetoothAdapter adapter;
    private BluetoothProfile profile;
    private Notification notification;
    private boolean stopped;
    private int startId;
    // 执行回执：am 的 rc=0 只证明"投递成功"，调用方（service.sh）无法从
    // logcat 可靠取证。结束前把 "<elapsedRealtime> <action> ok|fail <detail>"
    // 写进自己的 files 目录（锁屏 DBA 态 = /data/user_de/0/...，解锁后 =
    // /data/user/0/...，调用方两个都探测）。默认 fail：只有显式确认两条
    // Bluetooth 调用都发出去了才置 ok —— 判不了 ≠ 成功（本项目静默失败
    // 已踩过五次，见 docs/pen_wake_guard_silent_noop_20260921.md）。
    private static final String RESULT_NAME = "penhid.result";
    private String lastAction = "?";
    private boolean resultOk;
    private boolean resultWritten;

    @Override
    public void onCreate() {
        super.onCreate();
        handler = new Handler(getMainLooper());
        createForegroundNotification();
    }

    private void createForegroundNotification() {
        try {
            String channelId = "penhid";
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null && Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        channelId, "Pen HID Control", NotificationManager.IMPORTANCE_MIN);
                channel.setShowBadge(false);
                manager.createNotificationChannel(channel);
            }
            notification = new Notification.Builder(this, channelId)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("Pen HID")
                    .setContentText("Controlling pen Bluetooth link")
                    .setOngoing(true)
                    .build();
        } catch (Throwable error) {
            Log.w(TAG, "foreground notification unavailable", error);
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int id) {
        startId = id;
        final String action = intent == null ? null : intent.getStringExtra("action");
        final String mac = intent == null ? null : intent.getStringExtra("mac");
        lastAction = action == null ? "?" : action;
        resultOk = false;
        resultWritten = false;
        Log.i(TAG, "service start action=" + action + " mac=" + mac);
        // The caller (root shell) starts this as a foreground service because
        // the ROM blocks background starts. Become a real FGS immediately so
        // the system does not kill us after the 5 s startForeground timeout.
        try {
            if (notification != null) {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
                } else {
                    startForeground(1, notification);
                }
            }
        } catch (Throwable error) {
            Log.w(TAG, "startForeground failed", error);
        }

        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager == null ? BluetoothAdapter.getDefaultAdapter() : manager.getAdapter();
        if (adapter == null || mac == null || mac.length() == 0) {
            stopNow("adapter/device unavailable");
            return START_NOT_STICKY;
        }

        final BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(mac);
        } catch (Throwable error) {
            stopNow("invalid device " + error);
            return START_NOT_STICKY;
        }

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                stopNow("profile timeout");
            }
        }, 8000L);

        try {
            if (!adapter.getProfileProxy(this, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profileId, BluetoothProfile proxy) {
                    profile = proxy;
                    boolean connect = "connect".equalsIgnoreCase(action);
                    if (connect) {
                        // 这台 ROM 的 BluetoothHidHost **没有** 1 参
                        // connect(BluetoothDevice) 隐藏方法（实测 "connect method
                        // unavailable"）；把 policy 从 FORBIDDEN 切回 ALLOWED 就是
                        // 蓝牙栈的重连触发器（实测回链），等价于原实现想做的事。
                        resultOk = invoke(proxy, "setConnectionPolicy", device, POLICY_ALLOWED);
                        stopNow("connect requested");
                    } else {
                        // 同上：1 参 disconnect(BluetoothDevice) 也不存在。把 policy
                        // 切到 FORBIDDEN 会让栈**立即踢掉** HOGP 链路并阻止自动重连
                        // （实测 hogp connection state 2 -> 0）—— 正是断链需要的动作。
                        resultOk = invoke(proxy, "setConnectionPolicy", device, POLICY_FORBIDDEN);
                        stopNow("disconnect requested");
                    }
                }

                @Override
                public void onServiceDisconnected(int profileId) {
                    stopNow("profile service disconnected");
                }
            }, HID_HOST)) {
                stopNow("HID Host profile unavailable");
            }
        } catch (Throwable error) {
            stopNow("profile request failed " + error);
        }
        return START_NOT_STICKY;
    }

    /** @return true 只表示找到了同名方法且反射调用没有抛异常（Bluetooth 结果本身异步）。 */
    private static boolean invoke(Object target, String name, Object... args) {
        if (target == null) {
            return false;
        }
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!name.equals(method.getName()) || method.getParameterTypes().length != args.length) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    Object result = method.invoke(target, args);
                    Log.i(TAG, name + " result=" + result);
                    return true;
                } catch (Throwable error) {
                    Log.e(TAG, name + " failed", error);
                    return false;
                }
            }
        }
        Log.w(TAG, name + " method unavailable");
        return false;
    }

    private void writeResult(String detail) {
        resultWritten = true;
        FileOutputStream out = null;
        try {
            File dir = getFilesDir();
            if (dir == null) {
                Log.w(TAG, "result write skipped: files dir unavailable");
                return;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "result write skipped: cannot create " + dir);
                return;
            }
            StringBuilder line = new StringBuilder();
            line.append(SystemClock.elapsedRealtime()).append(' ').append(lastAction).append(' ')
                    .append(resultOk ? "ok" : "fail").append(' ').append(detail).append('\n');
            out = new FileOutputStream(new File(dir, RESULT_NAME), false);
            out.write(line.toString().getBytes("UTF-8"));
            Log.i(TAG, "receipt written: " + line.toString().trim());
        } catch (Throwable error) {
            Log.w(TAG, "result write failed", error);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private synchronized void stopNow(String reason) {
        if (stopped) {
            return;
        }
        stopped = true;
        Log.i(TAG, reason);
        if (!resultWritten) {
            // 没走到显式成功路径就结束（超时/崩溃/组件不可用）一律按 fail 落回执。
            writeResult(reason);
        }
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (adapter != null && profile != null) {
            try {
                adapter.closeProfileProxy(HID_HOST, profile);
            } catch (Throwable ignored) {
            }
        }
        stopSelf(startId);
    }

    @Override
    public void onDestroy() {
        stopNow("service destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
