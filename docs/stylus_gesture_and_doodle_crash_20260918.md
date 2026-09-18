# Touch-strip gestures: end-to-end trace and the two real defects

Captured 2026-09-18 ~18:50–19:10 on TB522FU (fw 2.0.12.015, kernel 6.6.82, ColorOS 16 port),
module v4.1.12 → **v4.1.13**.

Trigger: user report *"手势没有用，还是不生效！！！"* (touch-strip gestures do nothing),
then *"doodle crashed!"*.

---

## 1. Method: synthesise the gesture at hardware level

No physical pen action was needed. The pen's "Consumer Control" HID node is
`/dev/input/event9`; every strip gesture arrives as a HID **usage** carried by
`EV_MSC/MSC_SCAN`, followed by `EV_KEY/KEY_UNKNOWN`. Writing the same sequence with
`sendevent` exercises the *entire* real input stack (EventHub → InputReader → key
layout mapping → PhoneWindowManager), indistinguishable from the pen.

Scripts: `scripts/` on device `/data/local/tmp/pen_gesture.sh` (see §5).
Usage → keycode (from `/system/usr/keylayout/Vendor_17ef_Product_622e.kl`):

| HID usage | gesture | `getKeyCode()` |
|---|---|---|
| `0x0c0614` | single click | 131 (F1) |
| `0x0c0612` | slide down   | 131 (F1) |
| `0x0c0613` | slide up     | 131 (F1) |
| `0x0c0601` | two-click    | 132 (F2) |
| `0x0c0611` | long press   | 133 (F3) |

**Side finding / correction to an earlier note:** both HID interfaces merge into one
logical `InputDevice` (`dumpsys input` → `EventHub Devices: [ 10 11 ]`, name taken from
the *Mouse* interface). The **Consumer Control** interface is the one that carries the
gestures and it is `Classes: KEYBOARD | EXTERNAL` with
`KeyLayoutFile: /system/usr/keylayout/Vendor_17ef_Product_622e.kl` → it *does* feed the
key queue. The earlier claim that it "never reaches the key queue" was wrong.

## 2. The system half works — proven

One synthetic single click produced, in order:

```
key probe#63: dev=Lenovo Tab Pen Pro 2 Mouse code=131 scan=240 act=0 pen=true ctx=true
key probe#64: dev=Lenovo Tab Pen Pro 2 Mouse code=131 scan=240 act=1 pen=true ctx=true
touch strip: keyCode=131 -> single-click action
start writing haptic ...                       <- click() ran
BroadcastRecord{...PENCIL_SINGLE_CLICK/u-1}     DELIVERED -> com.coloros.note
toolkit dispatch(mode=3): no Toolkit attached   <- v4.1.13 app side
```

So `PhoneWindowManager.interceptKeyBeforeQueueing` → `SystemStylusHooks.handle()` →
`stripGesture()` → `click()` → broadcast is **fully functional**, and the broadcast is
**delivered** to `com.coloros.note`. Configured actions: `ipe_pencil_single_click=3`
(colour palette), `ipe_pencil_double_click=1` (eraser).

`isPen()` is satisfied (vendor `0x17EF`, product `0x622E`=25134 ∈ `LENOVO_PRODUCTS`,
name contains `lenovo tab pen`). `LenovoConsumerGestureReader` is **not** running (its
`libpeninput.so` is absent), so its exclusive `EVIOCGRAB` is not stealing events.

## 3. DEFECT A — the module's app-side dispatch was a permanent no-op  (FIXED, v4.1.13)

`NoteToolkitHooks.trigger()` searched

```java
String[] names = {"triggerStylusClick", "onStylusClick", "switchPenMode"};
for (Method m : obj.getClass().getMethods()) { ... }   // public members ONLY
```

Against the real APP (decompiled with r2 from `com.coloros.note` base.apk):

```
29931 LOCAL  FUNC 112  Toolkit.method.triggerStylusClick(IZ)V      <- PRIVATE, 2 args
30051 GLOBAL FUNC 112  Toolkit.method.receiverSingleClick(Ljava/lang/Integer;)V
30050 GLOBAL FUNC 114  Toolkit.method.receiverDoubleClick(Ljava/lang/Integer;)V
```

* `triggerStylusClick` is **private** → `getMethods()` never returns it.
* `onStylusClick` / `switchPenMode` **do not exist** on the class at all.
* Even if found, its arity is **2**, and the old code only handled arity 0/1 — and then
  returned `true` without invoking anything.

⇒ `trigger()` always returned `false`, `dispatch()` was always false, and every gesture
was a silent no-op. **This is exactly the reported symptom.**

The real worker (recovered Kotlin from the dex, `Toolkit.kt`):

```kotlin
private fun triggerStylusClick(mode: Int, vibrate: Boolean) = when (mode) {
    1 -> toggleWithEraserView()                    // switch_eraser
    2 -> toggleWithLastPenView()                   // switch_recent
    3 -> toolkitWrapperManager.showColorIfNeed {}  // show_color
    else -> return
}
```

i.e. the integer is exactly the `ipe_pencil_*` setting value.

**Fix (v4.1.13, `NoteToolkitHooks.trigger`)** — strategy order, first hit wins:
1. `getDeclaredMethod("triggerStylusClick", int.class, boolean.class)` + `setAccessible`
2. Kotlin bridge `triggerStylusClick$default(clazz,int,boolean,int,Object)`
3. synthetic accessor `access$triggerStylusClick(clazz,int,boolean)`
4. public `receiverSingleClick`/`receiverDoubleClick(Integer)` — **recursion-guarded**
   (`IN_DISPATCH` ThreadLocal), because `install()` hooks those very methods
5. legacy public-name scan

`dispatch()` also gained: a log when `active` is empty, a final `-> true/false` log, and
a 150 ms same-mode de-duplication window (both our own receiver and the engine's own
receiver can deliver the same gesture; every mode is a *toggle*, so running it twice
opens and immediately closes the palette = "nothing happened").

Verified live: the new code path executes and logs
`toolkit dispatch(mode=3): no Toolkit attached` when the doodle canvas is not open.

## 4. DEFECT B — the OPPO doodle engine dies in its own EGL init  (BLOCKER, not ours)

Opening any handwriting/doodle canvas makes `com.coloros.note` SIGSEGV. Identical
tombstones before and after this session (`tombstone_29/30/31`, and `tombstone_02`
19:05:54 pid 25629):

```
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference      pc = 0x0000000000000000     thread: canvas render thread
 #00 pc 0x0            <unknown>
 #01 pc 0x697cbc  libSuniaEngine.so  EglContext3::eglInit()+76
 #02 pc 0x698024  libSuniaEngine.so  EglContext3::EglContext3(EglContext3*, bool)+68
 #03 pc 0x704860  libSuniaEngine.so  ToolFactory::ToolFactory()+156
 #04 pc 0x697bf0  libSuniaEngine.so  IToolFactory::NEW(IToolFactory::ToolRenderDevice)+32
 #05 pc 0x5da59c  libSuniaEngine.so  GroupFactoryManager::createFactory(unsigned int const&)
 #06 pc 0x59ff50  libSuniaEngine.so  CanvasManager::CanvasThreadHandlerFunc(...)
```

Disassembly of `EglContext3::eglInit()`:

```asm
0x697cb0  mov  x0, xzr          ; EGL_DEFAULT_DISPLAY
0x697cb4  ldr  x8, [x8, 0x3a8]  ; x8 = &__eglewGetDisplay   (from .got, cell 0xc2a3a8)
0x697cb8  ldr  x8, [x8]         ; x8 = __eglewGetDisplay    = NULL
0x697cbc  blr  x8               ; *** SIGSEGV at pc=0 ***
```

Relocation proof:

```
0x00c2a3a8  SET_64 -> __eglewGetDisplay      (variable at 0xe12f88, .bss)
```

`libSuniaEngine.so` is `14658800` bytes, statically links glew, and exports
`eglewInit` (0x81d73c, 46 kB) and `glewInit` (0x828bcc, 48 B, **no arguments**).
The indirection table holds **149 `__eglew*` + 2754 `__glew*` cells**.

**Why the pointer is NULL:** in glew, `eglewInit()`'s first action is an unconditional
`__eglewGetDisplay = eglGetDisplay;`. It therefore cannot remain NULL if `eglewInit()`
ever runs. A scan of every `bl` in `.text`/`.plt` found **zero call sites** for
`eglewInit`/`glewInit`, and no other `.so` in the APK imports them. ⇒ **nothing on this
port ever initialises glew.** (Consistent with the port's other "missing OPPO component"
findings, e.g. the absent `/odm/lib64/libuahcore.so` behind the urcc crash-loop.)

**A/B — it is NOT our module.** `vector-cli scope set` was run without
`com.coloros.note/0`, the note app was restarted (no `handleLoadPackage` line ⇒ module
not injected), and the canvas still crashed with the byte-identical backtrace
(`tombstone_02`). Scope was then restored to all 8 packages.

**Why patch-one-call-site does not work:** the very next block
(`0x697ccc…0x697ce4`) goes through the same table for `eglInitialize` (cell `+0x3b0`).
Fixing `eglGetDisplay` alone just moves the crash.

**Viable fixes (all need the engine's own `glewInit()` to be called):**
* **(B1) Native shim** — Zygisk module that, once `libSuniaEngine.so` is loaded, resolves
  its exported `glewInit()` and calls it. Cleanest and complete; needs an Android NDK
  (none present on this Mac yet).
* **(B2) ELF patch of `libSuniaEngine.so`** — make the .so initialise itself at load.
  `.init_array` is 314 entries, all populated by R_AARCH64_RELATIVE relocs, and it is
  immediately followed by `.dynamic` (0xc296e8), so the array cannot be extended in
  place; it would mean sacrificing one existing constructor or adding a segment.
  Requires writing the patched file into the app's `lib/arm64/` with root, plus a
  boot-time re-apply in the KSU module (the file is re-extracted on app update).
* **(B3) Find the missing OPPO component** that normally calls `glewInit` — likely a
  system/plugin piece absent from this port.

Note: even with glew initialised, the **GL** half of the table (2754 cells) is normally
resolved against a current context, so a load-time-only `glewInit()` may need to be
supplemented with a second call after the engine has created its context.

## 5. Reproduction commands

```sh
# node names (Consumer Control is event9 on this boot; indices move between reboots)
adb shell su -c 'sh /data/local/tmp/pen_nodes.sh'

# a single click (usage 0x0c0614 = 787988)
adb shell su -c 'sh /data/local/tmp/pen_gesture.sh /dev/input/event9 787988'

# watch the decision
adb logcat -v time -s LenovoPenBridge
```

Scripts live in `scripts/` (`pen_gesture.sh`, `pen_nodes.sh`).

## 6. Device state touched during this investigation

* Module APK updated to **v4.1.13** (`pm install -r -d`), versionCode 410013.
* `vector-cli scope` for `com.aclaniakea.lenovopenbridge`: temporarily reduced to 7
  packages, then **restored to the full 8** (incl. `com.coloros.note/0`).
* `settings put system screen_off_timeout 1800000` was set to keep the screen awake;
  **restored to 30000**.
* `svc power stayon` toggled; **restored to false**.
* No file on the device was patched.
