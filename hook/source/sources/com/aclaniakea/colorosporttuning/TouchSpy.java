package com.aclaniakea.colorosporttuning;

import android.content.Context;
import android.hardware.input.InputManager;
import android.os.HandlerThread;
import android.view.Display;
import android.view.InputChannel;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.MotionEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Read-only observer of the live touch stream, for the swipe-range recorder.
 *
 * <p><b>Why this exists.</b> The recorder used to be a full-screen <em>touchable</em> overlay, so
 * calibrating a swipe meant swiping at a surface where the app underneath was frozen — the user
 * aimed blind, and what looked like "the gesture did not reach the app" was baked into the
 * recording. The fix is to let the gesture go where it would have gone anyway (the calibration
 * window is {@code FLAG_NOT_TOUCHABLE} while recording) and observe a <em>copy</em> of the event
 * stream instead of consuming it. A gesture monitor is exactly that: the input dispatcher
 * delivers spy copies to it and the app still receives, and reacts to, the original.</p>
 *
 * <p><b>Channels, in order of preference</b> (all hidden, all reached reflectively because the
 * signatures moved between releases and {@code android.jar} has neither):</p>
 * <ol>
 *   <li>{@code InputManager.monitorGestureInput(name, displayId)} — the API SystemUI's own edge
 *       back gesture uses. The server-side check is {@code callingUid == SYSTEM_UID || SHELL_UID},
 *       which this module satisfies because it runs inside {@code system_server}. Multiple
 *       monitors coexist, so an already-running SystemUI monitor is not a conflict. The return
 *       type moved too: older releases hand back an {@code InputChannel} directly, newer ones wrap
 *       it in an {@code InputMonitor} whose {@code getInputChannel()} is the way in — measured on
 *       Android 15 / ColorOS, where taking the raw return value at face value made the whole spy
 *       silently unavailable.</li>
 *   <li>{@code monitorInput(name)} / {@code monitorInput(name, displayId)} — the older spawn-a-spy
 *       entry point. Guarded by {@code MONITOR_INPUT}.</li>
 *   <li>The same attempts against the client's {@code mIm} AIDL proxy, in case the client
 *       class stopped exposing one of them.</li>
 * </ol>
 *
 * <p>Every failure is non-fatal by design: {@link #start} returns {@code null} and the caller
 * stays in its old blocking-capture mode. A missing permission or a vendor-removed method must
 * degrade the recorder, never disable it.</p>
 *
 * <p><b>Threading.</b> The receiver is created on (and serviced by) its own handler thread, so
 * nothing here touches {@code system_server}'s main looper — the caller marshals to the main
 * thread itself. Coordinates handed to the listener are raw display pixels; event times are the
 * input clock, which is the same base as {@code MotionEvent} timestamps seen anywhere else.</p>
 */
final class TouchSpy {

    private static final String TAG = "PenTouchSpy";

    private static final String CHANNEL_NAME = "pen-calibrate-monitor";

    /** One observed pointer event. */
    interface Listener {
        /**
         * @param action    {@link MotionEvent#getActionMasked()} value
         * @param x,y       display pixels
         * @param eventTime input-clock milliseconds
         */
        void onSpyTouch(int action, float x, float y, long eventTime);

        /** The channel died (dispatcher teardown, display change). Informational only. */
        void onSpyLost();
    }

    private final Object handle;
    private final InputChannel channel;
    private final InputEventReceiver receiver;
    private final HandlerThread thread;

    private TouchSpy(Object handle, InputChannel channel, InputEventReceiver receiver,
                     HandlerThread thread) {
        this.handle = handle;
        this.channel = channel;
        this.receiver = receiver;
        this.thread = thread;
    }

    /** @return an armed spy, or {@code null} if this build of Android will not hand one out. */
    static TouchSpy start(Context ctx, final Listener listener) {
        Object[] found = null;
        try {
            found = openChannel(ctx);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": openChannel: " + th);
        }
        if (found == null) {
            HookUtils.log(TAG + ": no monitor channel available, recorder stays blocking");
            return null;
        }
        final Object handle = found[0];
        final InputChannel ch = (InputChannel) found[1];
        HandlerThread t = null;
        try {
            t = new HandlerThread("pen-calib-spy");
            t.start();
            InputEventReceiver r = new InputEventReceiver(ch, t.getLooper()) {
                @Override public void onInputEvent(InputEvent event) {
                    deliver(this, event, listener);
                }
                @Override public void onInputEvent(InputEvent event, int displayId) {
                    deliver(this, event, listener);
                }
            };
            HookUtils.log(TAG + ": spy armed (" + CHANNEL_NAME + ")");
            return new TouchSpy(handle, ch, r, t);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": cannot arm spy: " + th);
            // If a monitor wrapper owns the channel, closing the wrapper releases both.
            closeQuietly(handle instanceof InputChannel ? handle : ch);
            closeQuietly(handle);
            if (t != null) {
                try { t.quitSafely(); } catch (Throwable ignored) { }
            }
            return null;
        }
    }

    void dispose() {
        try {
            if (receiver != null) receiver.dispose();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": receiver.dispose: " + th);
        }
        // The wrapper (when there is one) is what unregisters the monitor, and it disposes the
        // channel itself -- disposing the bare channel as well would be a double free.
        closeQuietly(handle);
        if (handle == null || handle instanceof InputChannel) closeQuietly(channel);
        try {
            if (thread != null) thread.quitSafely();
        } catch (Throwable th) {
            HookUtils.log(TAG + ": thread.quit: " + th);
        }
        HookUtils.log(TAG + ": spy disarmed");
    }

    // ------------------------------------------------------------------

    /**
     * Runs on the spy thread. The event is released in a {@code finally} regardless of what the
     * listener does: an unfinished spy event sits in the dispatcher's queue, and a few hundred of
     * those are enough to stall the whole input pipeline.
     */
    private static void deliver(InputEventReceiver self, InputEvent event, Listener listener) {
        try {
            if (event instanceof MotionEvent) {
                MotionEvent m = (MotionEvent) event;
                listener.onSpyTouch(m.getActionMasked(), m.getX(), m.getY(), m.getEventTime());
            }
        } catch (Throwable th) {
            HookUtils.log(TAG + ": deliver: " + th);
        } finally {
            try {
                self.finishInputEvent(event, false);
            } catch (Throwable th) {
                HookUtils.log(TAG + ": finishInputEvent: " + th);
            }
        }
    }

    /**
     * @return {@code {handle, InputChannel}} where {@code handle} is whatever the framework
     *         returned (a bare channel on older releases, an {@code InputMonitor} wrapper on
     *         newer ones) and is what teardown has to close; {@code null} when nothing works.
     */
    private static Object[] openChannel(Context ctx) {
        Object svc = null;
        try {
            svc = ctx.getSystemService(Context.INPUT_SERVICE);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": getSystemService: " + th);
        }
        if (!(svc instanceof InputManager)) {
            HookUtils.log(TAG + ": input service is " + (svc == null ? "null" : svc.getClass()));
            return null;
        }
        InputManager im = (InputManager) svc;

        Object[] found = tryAll(im, "client");
        if (found == null) {
            // The client class may have dropped one of them; the AIDL proxy behind it is the
            // last place the transaction still exists.
            Object proxy = readField(im, "mIm");
            if (proxy != null) found = tryAll(proxy, "aidl");
        }
        return found;
    }

    private static Object[] tryAll(Object target, String where) {
        for (String name : new String[]{"monitorGestureInput", "monitorInput"}) {
            Object raw = invokeAny(target, name, new Object[]{CHANNEL_NAME, Display.DEFAULT_DISPLAY});
            if (raw == null) raw = invokeAny(target, name, new Object[]{CHANNEL_NAME});
            if (raw == null) continue;
            InputChannel ch = unwrap(raw);
            if (ch != null) {
                HookUtils.log(TAG + ": " + name + " via " + where + " -> channel ("
                        + raw.getClass().getSimpleName() + ")");
                return new Object[]{raw, ch};
            }
            HookUtils.log(TAG + ": " + name + " via " + where + " returned "
                    + raw.getClass() + ", no channel inside");
        }
        return null;
    }

    /**
     * Android 15 hands back an {@code InputMonitor} that owns the channel; older releases hand
     * back the channel itself. Both are unwrapped here rather than at the call site so the
     * preference list above stays readable.
     */
    private static InputChannel unwrap(Object raw) {
        if (raw instanceof InputChannel) return (InputChannel) raw;
        Object inner = invokeAny(raw, "getInputChannel", new Object[0]);
        if (inner == null) inner = readField(raw, "mInputChannel");
        return inner instanceof InputChannel ? (InputChannel) inner : null;
    }

    /** Call {@code name} with whatever overload matches the argument count. */
    private static Object invokeAny(Object target, String name, Object[] args) {
        if (target == null) return null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Object out = invoke(m, target, args);
            if (out != null) return out;
        }
        for (Method m : target.getClass().getDeclaredMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Object out = invoke(m, target, args);
            if (out != null) return out;
        }
        return null;
    }

    private static Object invoke(Method m, Object target, Object[] args) {
        try {
            m.setAccessible(true);
            return m.invoke(target, args);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": " + m.getName() + "/" + args.length + " -> " + th);
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        try {
            Field f = target.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable th) {
            HookUtils.log(TAG + ": field " + name + ": " + th);
            return null;
        }
    }

    /**
     * Both {@code dispose()} and {@code close()} are attempted reflectively: the channel is
     * leaked if the socket stays open, and the dispatcher would keep a dead monitor registered.
     */
    private static void closeQuietly(Object channel) {
        if (channel == null) return;
        for (String name : new String[]{"dispose", "close"}) {
            try {
                Method m = channel.getClass().getMethod(name);
                m.setAccessible(true);
                m.invoke(channel);
                return;
            } catch (Throwable ignored) {
                // try the next spelling
            }
        }
        HookUtils.log(TAG + ": channel not closed, it has neither dispose() nor close()");
    }
}
