package com.aclaniakea.colorosporttuning;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.MediaStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Gallery-write delegate for hook code that runs inside system_server.
 *
 * <p>Why this class exists (measured on TB522FU, 2026-09-19): every capture is
 * taken in the system_server process, and system_server is simply not allowed to
 * write the FUSE view. MediaStore inserts succeed but the stream open then dies:
 *
 * <pre>
 *   avc: denied { read write } for comm="binder:23732_2"
 *     path="/mnt/user/0/emulated/0/Pictures/PenBridge/.pending-...png"
 *     dev="fuse" scontext=u:r:system_server:s0
 *     tcontext=u:object_r:fuse:s0 tclass=file permissive=0
 * </pre>
 *
 * and the plain-file fallback under {@code /storage/emulated/0} fails the same
 * way. The ROM also removed the {@code SurfaceControl} display-token statics, so
 * there is no framework angle either. A normal app uid, by contrast, has its own
 * legal FUSE mount -- so the write is delegated here, into the module APK's own
 * process, and only the bytes travel over binder.
 *
 * <p>Protocol (driven from {@code HookUtils.publishPng}):
 * <pre>
 *   call("ping")                    -> {ok, uid, version}
 *   call("selftest")                -> publishes a 1x1 PNG: {ok, uri, where}
 *   call("begin",  name)            -> {id, ok}
 *   call("chunk",  {id, data|b64})  -> {ok, received}
 *   call("commit", {id, name})      -> {ok, uri, where, bytes}
 *   call("abort",  id)              -> {ok}
 * </pre>
 *
 * <p>Chunks are capped at 192 KiB by the caller so no binder transaction comes
 * near the 1 MiB limit. The {@code b64} alias exists only so the whole chain can
 * be exercised from {@code adb shell content call} -- that CLI cannot carry a
 * {@code byte[]}; the real caller always uses {@code data}.
 *
 * <p>Touches no Xposed class on purpose: this provider runs in the module APK's
 * ordinary process where the LSPosed classloader is absent, so it logs through
 * {@code android.util.Log} directly instead of {@link HookUtils#log}.
 */
public final class PenShareProvider extends ContentProvider {

    public static final String AUTHORITY = "com.aclaniakea.lenovopenbridge.share";
    public static final String VERSION = "1";

    private static final String SUBDIR = "PenBridge";
    private static final String TAG = "LenovoPenBridge";

    /** In-flight upload sessions: id -> accumulated PNG bytes. */
    private static final Map<String, ByteArrayOutputStream> SESSIONS = new HashMap<>();
    /** Published fallback files, so openFile() can serve them by name. */
    private static final Map<String, File> SERVED = new HashMap<>();
    private static int sequence;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        return 0;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle out = new Bundle();
        if (!callerAllowed()) {
            log("call " + method + " refused for uid " + Binder.getCallingUid());
            out.putBoolean("ok", false);
            out.putString("error", "caller not allowed");
            return out;
        }
        Bundle data = extras == null ? Bundle.EMPTY : extras;
        if ("ping".equals(method)) {
            out.putBoolean("ok", true);
            out.putInt("uid", Process.myUid());
            out.putString("version", VERSION);
            return out;
        }
        if ("selftest".equals(method)) {
            String name = arg == null || arg.isEmpty()
                    ? "pen_selftest_" + System.currentTimeMillis() + ".png" : arg;
            String uri = writePng(name, tinyPng(), out);
            out.putBoolean("ok", uri != null);
            out.putString("uri", uri);
            return out;
        }
        if ("begin".equals(method)) {
            String id;
            synchronized (SESSIONS) {
                id = "s" + (++sequence) + "-" + System.nanoTime();
                SESSIONS.put(id, new ByteArrayOutputStream());
            }
            out.putString("id", id);
            out.putBoolean("ok", true);
            return out;
        }
        if ("chunk".equals(method)) {
            String id = data.getString("id");
            byte[] part = data.getByteArray("data");
            if (part == null) {
                String b64 = data.getString("b64");
                if (b64 != null) part = decodeBase64(b64);
            }
            ByteArrayOutputStream sink;
            synchronized (SESSIONS) {
                sink = id == null ? null : SESSIONS.get(id);
            }
            if (sink == null || part == null) {
                out.putBoolean("ok", false);
                out.putString("error", sink == null ? "unknown session" : "no payload");
                return out;
            }
            try {
                sink.write(part, 0, part.length);
            } catch (Throwable th) {
                out.putBoolean("ok", false);
                out.putString("error", String.valueOf(th));
                return out;
            }
            out.putBoolean("ok", true);
            out.putInt("received", sink.size());
            return out;
        }
        if ("commit".equals(method)) {
            String id = data.getString("id");
            String name = data.getString("name");
            ByteArrayOutputStream sink;
            synchronized (SESSIONS) {
                sink = id == null ? null : SESSIONS.remove(id);
            }
            if (sink == null) {
                out.putBoolean("ok", false);
                out.putString("error", "unknown session");
                return out;
            }
            byte[] png = sink.toByteArray();
            String uri = writePng(name == null ? "pen_capture.png" : name, png, out);
            out.putBoolean("ok", uri != null);
            out.putInt("bytes", png.length);
            out.putString("uri", uri);
            return out;
        }
        if ("abort".equals(method)) {
            synchronized (SESSIONS) {
                if (arg != null) SESSIONS.remove(arg);
            }
            out.putBoolean("ok", true);
            return out;
        }
        out.putBoolean("ok", false);
        out.putString("error", "unknown method");
        return out;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && !mode.startsWith("r") && !callerAllowed()) {
            throw new FileNotFoundException("caller not allowed to write");
        }
        String name = uri.getLastPathSegment();
        File file = null;
        synchronized (SERVED) {
            if (name != null) file = SERVED.get(name);
        }
        if (file == null || !file.isFile()) {
            Context ctx = getContext();
            File dir = ctx == null ? null : new File(ctx.getCacheDir(), SUBDIR);
            if (dir != null && name != null) {
                File candidate = new File(dir, name);
                if (candidate.isFile()) file = candidate;
            }
        }
        if (file == null) {
            throw new FileNotFoundException("no such image: " + uri);
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** uid 0 / system_server / shell (for adb-driven verification) / ourselves. */
    private static boolean callerAllowed() {
        int uid = Binder.getCallingUid();
        return uid == 0 || uid == Process.SYSTEM_UID || uid == Process.myUid()
                || uid == Process.SHELL_UID;
    }

    /**
     * Publish the bytes into the gallery. MediaStore first -- an app may always
     * contribute its own images, and the returned media Uri is exactly what the
     * share sheet and the clipboard want. If the insert or the stream open is
     * refused, keep the bytes in our own cache and serve them through
     * {@link #openFile}; the gallery entry is lost but the capture is still
     * shareable, which is what the lasso flow needs.
     */
    private String writePng(String name, byte[] png, Bundle out) {
        Context ctx = getContext();
        if (ctx == null) {
            out.putString("where", "no-context");
            return null;
        }
        Uri row = null;
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/" + SUBDIR);
            cv.put(MediaStore.Images.Media.IS_PENDING, 1);
            row = ctx.getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (row != null) {
                OutputStream os = ctx.getContentResolver().openOutputStream(row);
                if (os != null) {
                    try {
                        os.write(png);
                    } finally {
                        os.close();
                    }
                    ContentValues published = new ContentValues();
                    published.put(MediaStore.Images.Media.IS_PENDING, 0);
                    ctx.getContentResolver().update(row, published, null, null);
                    log("writePng " + name + " -> " + row + " via mediastore ("
                            + png.length + " bytes)");
                    out.putString("where", "mediastore");
                    return row.toString();
                }
                ctx.getContentResolver().delete(row, null, null);
                row = null;
                log("writePng " + name + ": openOutputStream returned null");
            }
        } catch (Throwable th) {
            log("writePng " + name + ": mediastore refused: " + th);
            if (row != null) {
                try {
                    ctx.getContentResolver().delete(row, null, null);
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            File dir = new File(ctx.getCacheDir(), SUBDIR);
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                throw new java.io.IOException("cannot create " + dir);
            }
            File file = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(file);
            try {
                fos.write(png);
            } finally {
                fos.close();
            }
            synchronized (SERVED) {
                SERVED.put(name, file);
            }
            Uri served = Uri.parse("content://" + AUTHORITY + "/file/" + Uri.encode(name));
            log("writePng " + name + " -> " + served + " via cache ("
                    + png.length + " bytes, no gallery entry)");
            out.putString("where", "cache");
            return served.toString();
        } catch (Throwable th) {
            log("writePng " + name + ": cache refused: " + th);
        }
        out.putString("where", "failed");
        return null;
    }

    /** 1x1 transparent PNG, so the publish path can be proven without a capture. */
    private static byte[] tinyPng() {
        return hex("89504e470d0a1a0a"          // signature
                + "0000000d49484452"          // IHDR, 13 bytes
                + "000000010000000108060000"
                + "1f15c489"                  // IHDR crc
                + "0000000a49444154"          // IDAT, 10 bytes
                + "789c6300010000050001"
                + "0d0a2db4"                  // IDAT crc
                + "0000000049454e44ae426082"); // IEND
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private static byte[] decodeBase64(String s) {
        try {
            Class<?> c = Class.forName("android.util.Base64");
            java.lang.reflect.Method m = c.getMethod("decode", String.class, int.class);
            Object r = m.invoke(null, s, 0);
            return r instanceof byte[] ? (byte[]) r : null;
        } catch (Throwable th) {
            log("decodeBase64 failed: " + th);
            return null;
        }
    }

    private static void log(String message) {
        try {
            android.util.Log.i(TAG, "LenovoPenBridge: " + message);
        } catch (Throwable ignored) {
        }
    }
}
