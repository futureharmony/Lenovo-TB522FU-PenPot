package com.exp.penprobe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 仅转发给前台服务，保持进程常驻。 */
public class ProbeReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent i) {
        Log.i(GattHolder.TAG, "RECV action=" + i.getAction());
        Intent s = new Intent(ctx, ProbeService.class);
        if (i.getAction() != null) s.setAction(i.getAction());
        s.putExtras(i);
        ctx.startForegroundService(s);
    }

    static byte[] hexToBytes(String hex) {
        hex = hex.replaceAll("[^0-9a-fA-F]", "");
        byte[] out = new byte[hex.length() / 2];
        for (int k = 0; k < out.length; k++) {
            out[k] = (byte) Integer.parseInt(hex.substring(k * 2, k * 2 + 2), 16);
        }
        return out;
    }
}
