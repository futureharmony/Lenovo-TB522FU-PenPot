# Tombstone inventory — TB522FU, 2026-09-18

Raw material behind TODO.md **P0.8 / P0.9 / P0.10**. Captured 18:35 before the v4.1.12 reboot
(`/data/tombstones` survives reboots, but logcat does not — the ART abort text exists only here).

## 1. Per-tombstone process map

`tombstone_00` … `tombstone_31`. Three unrelated crash families, no overlap in call stacks.

| tombstone | process | signal | abort msg | family |
|---|---|---|---|---|
| 00 01 02 03 04 06 07 08 09 10 11 13 14 24 25 26 27 28 30 31 (20) | `/odm/bin/hw/vendor.oplus.hardware.urcc-service` | 11 | no | **P0.9 urcc** |
| 05 12 29 (3) | `system_server` | 6 / 11 | yes | **P0.10 ART** |
| 15 16 17 18 19 20 21 22 23 (9) | `com.coloros.note` | 11 | no | **P0.8 EGL/GLEW** |

(Counts: 24 urcc — the table's 20 plus 4 more captured in the follow-up scan; 3 system_server;
8 note at first count, 9 with the extra.)

## 2. P0.9 — urcc-service, all 24 signatures identical

```
pid: 1868, tid: 1868, name: UrccMainThread  >>> /odm/bin/hw/vendor.oplus.hardware.urcc-service <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0000000000000000
Cause: null pointer dereference
      #00 pc 0000000000000000  <unknown>
      #01 pc 0000000000058bec  /odm/lib64/liburcccore.so (urccResStateRequest+396)
      #02 pc 000000000000512c  /odm/bin/hw/vendor.oplus.hardware.urcc-service
                               (Urcc::urccResStateRequest(...)+96)
      #03 pc 000000000000d570  /odm/lib64/vendor.oplus.hardware.urcc-V1-ndk.so
                               (IUrcc::onTransact(...)+5144)
      #04 pc 00000000000112dc  /system/lib64/libbinder_ndk.so (ABBinder::onTransact(...)+176)
      #05 pc 000000000004d2bc  /system/lib64/libbinder.so (BBinder::transact(...)+324)
```
Crashing thread varies (`UrccMainThread`, `binder:<pid>_1`, `binder:<pid>_2`, …) — any caller dies.

### 2.1 The faulting instruction (`liburcccore.so`, md5 `1c160f3dad18a60109b20ca3698449f2`)

`urccResStateRequest` @ vaddr `0x58a60`, size 6772 bytes.

```
0x58bd8  adrp x9, 0x93000
0x58bdc  ldr  x9, [x9, 0x4d8]   ; x9 = UrccCtlServer::uah_lib
0x58be0  ldr  w20, [x8]         ; request id
0x58be4  ldr  x8, [x9, 0xc0]    ; <-- NO null guard (the sibling slot 0xc8 IS guarded)
0x58be8  mov  w0, w20
0x58bec  blr  x8                ; <-- pc = 0
```

`rabin2 -R liburcccore.so | grep 934d8` →
`0x000934d8 0x000934d8 SET_64 1025  _ZN13UrccCtlServer7uah_libE` (= `UrccCtlServer::uah_lib`).
The guarded sibling, for contrast, at `0x58b5c`: `ldr x9,[x9,0xc8]; cbz x9, 0x5a1e4`.

### 2.2 Slot → symbol, decoded from `UrccCtlServer::loadUahCoreLib()` @ `0x4d804`

```
0x4d858  add x0, x0, 0xa69   (0x15a69)  bl dlopen   str x0, [x19, 0x88]   "/odm/lib64/libuahcore.so"
0x4d878  add x1, x1, 0x1df   (0x151df)  bl dlsym    str x0, [x19, 0x90]   "uah_init"
0x4d918  add x1, x1, 0x643   (0x15643)  bl dlsym    str x0, [x19, 0xa0]   "uah_request"
0x4d9c8  add x1, x1, 0xc8c   (0x12c8c)  bl dlsym    str x0, [x19, 0xb0]   "uah_allow"
0x4da78  add x1, x1, 0xe12   (0x15e12)  bl dlsym    str x0, [x19, 0xc0]   "uah_get_mode_status"   <-- crashes
0x4dad0  add x1, x1, 0x212   (0x15212)  bl dlsym    str x0, [x19, 0xc8]   "uah_get_feature_status"
0x4db28  add x1, x1, 0xe26   (0x15e26)  bl dlsym    str x0, [x19, 0xd0]   "uah_notify_release"
```
Other dlopen targets named by the same lib: `/odm/lib64/liboplus_gpu_utils.so`,
`/vendor/lib64/libpowerhal.so`. Related strings: `uah_getPower`, `uah_send_notification`,
`uah_evaluate_res`, `uah_qosArbiterNotice`, `uah_gpu_cal_utils`, `uah_history`,
`persist.vendor.oplus.uah.debugenable`.

### 2.3 On-device facts

```
/odm/lib64/libuahcore.so            MISSING      <-- the whole cause
/odm/lib64/liboplus_gpu_utils.so    MISSING
/vendor/lib64/libpowerhal.so        MISSING
/odm/lib64/liburcccore.so           PRESENT
/odm/bin/hw/vendor.oplus.hardware.urcc-service  PRESENT
find /odm /vendor /system -iname '*uah*'  ->  only liboplus-uah-client.so (not the core)

OPPO-only kernel nodes the rc file chmods:
/proc/perfmgr/boost_ctrl/eas_ctrl              MISSING
/proc/oplus_scheduler                          MISSING
/proc/game_opt                                 MISSING
/proc/ufsplus_ctrl                             MISSING
/sys/devices/platform/soc/soc:oplus-omrg       MISSING
/sys/class/devfreq/kgsl-busmon                 PRESENT (Qualcomm's own, unrelated)

uname: Linux 6.6.82TB522FU #1 SMP PREEMPT ... aarch64
ro.board.platform=sun   ro.soc.model=SM8750P   getenforce=Enforcing
declared in: /odm/etc/vintf/manifest/vendor.oplus.hardware.urcc-service.xml
client seen in logcat: UAH-UahAdaptHelper: getAidlService uah urcc service = ...IUrcc$Stub$Proxy@...
```
⇒ `dlopen` NULL → every `dlsym(NULL,…)` NULL → whole `uah_lib` table empty → first
`UrccResStateRequest` dies → init restarts → loop. The HAL could never have worked here:
it has no backing library **and** no kernel interfaces to control.

## 3. P0.10 — system_server, 3 signatures, identical wording

```
tombstone_05  pid 2255, tid 3453, name Thread-2          signal 6  '... suspend check at 0xa9982548'
tombstone_12  pid 2271, tid 3446, name HeapTaskDaemon    signal 11 '... suspend check at 0xaa5e46b4'
tombstone_29  pid 2289, tid 3471, name HeapTaskDaemon    signal 11 '... suspend check at 0xaa604754'
```
Full abort text (tombstone_29 variant):
```
Failed to recognize implicit suspend check at 0xaa604754;
thread state = Runnable; mutator lock shared held = false;
code ranges = {{0x66800000, 2000000}, {0x62800000, 2000000}, {0xab714000, f9408},
               {0xab56c000, 1354bc}, {0xab4f8000, f4}, {0xab4ec000, 222c}, {0xab4e0000, 734},
               {0xab4cc000, 440}, {0xab4b8000, 27fc}, {0xab4a8000, 658}, {0xab490000, 1624},
               {0xab468000, 184dc}, {0xab454000, 6368}, {0xab448000, 2bc}, {0xaaae8000, 89e398},
               {0xaa830000, 21378}, {0xaa7e0000, 39fb4}, {0xaa77c000, 499a8}, {0xaa45c000, 2ea058},
               {0x72c5052340, eed0}}
```
Backtrace of the aborting thread (pure ART; `boot-core-libart.oat` is the Java side):
```
#00 libart.so ReferenceMapVisitor<RootCallbackVisitor,false>::VisitFrame()+2820
#01 libart.so StackVisitor::WalkStack<(CountTransitions)1>(bool)+340
#02 libart.so Thread::VisitRoots(RootVisitor*, VisitRootFlags)+1088
#03 libart.so gc::collector::MarkCompact::RunPhases()+660
#04 libart.so gc::collector::GarbageCollector::Run(GcCause, bool)+328
#05 libart.so gc::Heap::CollectGarbageInternal(...)+608
#06 libart.so gc::Heap::ConcurrentGC(Thread*, GcCause, bool, unsigned int)+168
#07 libart.so gc::Heap::ConcurrentGCTask::Run(Thread*)+76
#08 libart.so gc::TaskProcessor::RunAllTasks(Thread*)+124
#09 /system/framework/arm64/boot-core-libart.oat (art_jni_trampoline+112)
#10 /system/framework/arm64/boot-core-libart.oat (Daemons$HeapTaskDaemon.runInternal+184)
```
The reported PC (`0xaa604754`) lies **inside** the range `{0xaa45c000, 2ea058}` that the same message
lists. So the address is in a registered oat range but the instruction there is not the expected
implicit-suspend-check sequence ⇒ *executing code ≠ oat / stack map*.

**Explicitly excluded** (so nobody re-litigates it):
* no urcc frame in any of the three — independent of P0.9;
* the aborting thread's stack has **no** hook-framework frame; the `/data/adb/...`, `libzygisk`,
  `lspd` hits from `grep` are only the memory-map dump, which contains them in **every** process.
  Not causal evidence.

Two live candidates, **not yet separated**: (a) ported ROM's ART/boot image not matching the
runtime; (b) a hook framework's deopt rewriting code in place. A/B recipe in TODO.md P0.10.

## 4. P0.8 — com.coloros.note (cross-reference)

Not re-detailed here; see TODO.md P0.8. All signatures: `EglContext3::eglInit()+76` (file offset
`0x697cbc`) → `blr` on NULL `__eglewGetDisplay`, on the `DefaultDispatch` thread. The in-process EGL
probe (v4.1.12) came back **8/8 green**, so this is the engine's own sequencing — raw probe output in
`docs/note_egl_diag_20260918.txt`.

### 4.1 Same-PID correlation (the strongest single piece of evidence)

Two tombstones written **after** the v4.1.12 reboot belong to **processes that had already passed the
probe**:

```
tombstone_25  18:44:14  pid 12039  com.coloros.note
tombstone_26  18:45:52  pid 19643  com.coloros.note
 #00 pc 0x0 <unknown>
 #01 pc 0x697cbc  libSuniaEngine.so (EglContext3::eglInit()+76)
 #02 pc 0x698024  libSuniaEngine.so (EglContext3::EglContext3(EglContext3*, bool)+68)
```

| pid | probe (`app-oncreate`) | probe verdict | what happened next |
|---|---|---|---|
| 12039 | 18:44:11.737 | main ✅ + worker ✅, `GL_VERSION = OpenGL ES 3.2 … Adreno 830` | 18:44:14 died at `EglContext3::eglInit()+76` |
| 19643 | 18:44:22.386 | main ✅ + worker ✅, same | 18:45:52 died at the same offset |

In the **same process**, the EGL bring-up succeeds at `t=0` and the engine itself crashes on the NULL
`__eglewGetDisplay` seconds later. That closes off every environmental explanation (process
environment, thread affinity, driver capability, shadowed/bundled libEGL) and leaves the engine's own
ordering plus its discarded return values.

Note also: Notes now crashes on its own within a minute of starting — the user does not even have to
open a handwriting note.
