package android.view;

import android.os.Looper;

/**
 * Compile-only stub for the hidden framework class supplied by Android.
 *
 * <p>Same contract as {@code android/os/UEventObserver}: declared so javac can compile against a
 * framework type that {@code android.jar} does not ship, filtered out of {@code classes.dex} by
 * {@code hook/tools/build_hook_source.py} so the boot classpath's real class is the only one that
 * exists at runtime. Filtering is mandatory — never bake a framework class into a module.</p>
 *
 * <p>Both {@code onInputEvent} overloads are declared because the abstract/overloaded shape
 * changed across releases (single-arg used to be the abstract entry point, two-arg with a display
 * id took over later). The concrete subclass in {@link
 * com.aclaniakea.colorosporttuning.TouchSpy} overrides both, so it satisfies the abstract set of
 * either generation.</p>
 *
 * <p>{@code onBatchedInputEventPending} is deliberately <b>not</b> overridden by that subclass:
 * the base implementation drains the batched queue, and skipping it would strand events in the
 * input pipeline. It is declared here only so the compile-time type is complete.</p>
 */
public abstract class InputEventReceiver {

    public InputEventReceiver(InputChannel inputChannel, Looper looper) { }

    public void onInputEvent(InputEvent event) { }

    public void onInputEvent(InputEvent event, int displayId) { }

    public void onBatchedInputEventPending() { }

    public void onFocusEvent(boolean hasFocus) { }

    /** Spy channels must release every event; the real one is final and long-standing. */
    public final void finishInputEvent(InputEvent event, boolean handled) { }

    public void dispose() { }
}
