package android.view;

/**
 * Compile-only stub for the hidden framework class supplied by Android.
 *
 * <p>{@code android.view.InputChannel} is not part of the public SDK ({@code android.jar} has no
 * such entry), but the class exists in the boot classpath of every device and is what
 * {@code InputManager.monitorGestureInput()} hands back. {@link
 * com.aclaniakea.colorosporttuning.TouchSpy} only ever casts an already-created instance, so this
 * declaration exists purely so javac can resolve the type; the class file is filtered out of
 * {@code classes.dex} by {@code hook/tools/build_hook_source.py} (see {@code _provided_prefixes})
 * and the framework's real implementation is used at runtime.</p>
 *
 * <p>No members are declared on purpose: everything this module needs from the channel
 * ({@code dispose} / {@code close}) is invoked reflectively, because the exact set differs
 * between Android releases and a missing method would otherwise be a {@code NoSuchMethodError}
 * at runtime rather than a caught failure.</p>
 */
public final class InputChannel {
}
