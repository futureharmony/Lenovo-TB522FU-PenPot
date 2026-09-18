# 让底层适配 ColorOS EGL 契约：系统级兼容 shim 设计

日期：2026-09-18｜状态：路线 1 已实现 **并已在 Mac 上完成 NDK 编译 + APK 重打**（见文末 §8），待设备验证

> §8 编译与产物（2026-09-18 22:5x，NDK r30）：
> - `libeglshim.so` 已编译（arm64-v8a，strip 后 140432 B，Dobby 静态链接自包含，
>   sha256 `5d03ef4d…`），落 `hook/source/resources/lib/arm64-v8a/`。
> - 加固 1：shim 自身先 `dlopen("libEGL.so")` 再 hook——`handleLoadPackage` 时
>   app 尚未运行、libEGL 可能未驻留，否则 `dlsym(RTLD_DEFAULT)` 静默拿 NULL、
>   hook 不生效。这是原实现唯一的真隐患，已修。
> - 加固 2：hook 安装幂等（`g_installed` 守卫）。
> - 构建链修复：Dobby 钉在 `67d155b`（HEAD 有循环包含 + log.h 函数体内 include
>   两个编译炸弹），`DOBBY_GENERATE_SHARED=OFF` 保证 Dobby 静态打入单 .so；
>   详见 `hook/source/jni/README.md` "Build gotchas"。
> - APK 已重打并签名：`releases/PenBridge-Hook-tb522fu-v4.1.13.apk`（含 shim，
>   377454 B；`libpeninput.so` 本机不存在故未嵌入，与此前行为一致）。
> - 剩余：设备重连后跑 `egl_contract_probe` 坐实假设 A/B → 装 APK →
>   `logcat -s EglShim` 确认 `contract: loaded` + hooked → 验收手写渲染。

---

## 9. 设备验证结果（2026-09-18 23:00–23:02，全部通过，含一处根因修正）

- **`egl_contract_probe`（root shell 进程）实测：A_OK=1 B_OK=1**。
  `eglGetProcAddress("glGetString")` 返回非空（0x7c6b578d20），二次
  `eglInitialize` 幂等返回 EGL_TRUE。
  **这修正了本文 §1 的推断**：本机驱动并不违反契约 A/B。真正的失败点更可能是
  GLEW 拿 `glGetString(GL_VERSION)` 得到 **GLES 版本串**（"OpenGL ES 3.2 …"），
  桌面版 GLEW 解析不出 `%d.%d` → `GLEW_ERROR_NO_GL_VERSION` → 早退——即
  崩溃根源是"桌面 GLEW 不认 ES 上下文"，与函数指针是否为 NULL 无关。
  字节补丁（强制进 `eglewInit` + 忽略二次 init）恰好绕过整条版本探测，
  所以有效；shim 的两个回退在本机属于"备用保险"（透传为主，行为正确）。
- shim 实机生效证据（logcat -s EglShim，pid 内加载）：
  `contract: loading EGL contract shim (Dobby)` → `libEGL handle …` →
  `hooked eglGetProcAddress @ 0x7b01a77a70` → `hooked eglInitialize @ 0x7b01a772b0`。
- 端到端：装 v4.1.13 APK → 引擎换已修 16.7.2（`db61d1ff…`，16.6.14 备份在
  `/data/local/tmp/libSuniaEngine.16.6.14.bak.so`）→ `QuickPaintActivity`
  正常 Resume、进程存活、无新增 tombstone → **注入笔画后截图确认笔迹渲染**。
- 结论：**"升级不重适配"链路验收通过**。后续 HeyTap 更新只需保证 shim APK 与
  模块在位；引擎随 APK 同步升级且 EGL 契约由 shim+字节补丁兜底，
  `guard_note_engine.sh` 继续防未知契约。

> 用户提问：能否从 ColorOS 底层出发，让我们的底层完全适配 ColorOS 的底层，
> 让 libSuniaEngine.so 作为上层应用"无论怎么更新都不崩"？
>
> 结论先行：**可以，且这是比"打字节补丁/锁版本"更根本的方向。** 但正确做法不是
> 把整套 ColorOS 底层搬过来，而是补一层**很薄的 EGL/GL 契约 shim**，让 GLEW 的初始化
> 假设成立。最优落点是**复用现有 Vector hook（scope=com.coloros.note）做符号级 EGL
> 拦截**——既"底层适配"又不波及系统其他进程，且符号级 → 不绑版本 → 后续升级不崩。

---

## 1. 崩溃本质（决定了 shim 该补什么）

`glewInit()` 依赖两个在 Android/Adreno（本移植）上不成立的假设：

- **假设 A**：`eglGetProcAddress("glGetString")` 返回有效的**核心 GL**函数指针。
  EGL 规范只保证 `eglGetProcAddress` 返回**扩展**函数；核心函数（glGetString 等）
  允许返回 NULL。本移植 Adreno 驱动对核心函数返回 NULL → GLEW 探测失败 → 早退。
- **假设 B**：`eglewInit()` 内**二次** `eglInitialize(dpy)` 返回成功（==1）。
  本机对已初始化的 display 二次 init 返回 !=1 → `__eglew*` 表填不上。

ColorOS 原厂底层满足这两个假设（其 `libEGL` 对核心 GL 函数从 `eglGetProcAddress`
返回真实指针，且 `eglInitialize` 幂等），所以原厂不崩、移植崩。

---

## 2. "让底层适配" = 补这两点契约，不是搬整套底层

- **不需要** port vendor HAL / gralloc / hwcomposer / 整套 ColorOS 图形栈。
- **只需**让 EGL 暴露 GLEW 期望的契约：
  1. `eglGetProcAddress` 对核心 GL 函数返回真实指针（从真实 `libGLESv2.so` `dlsym`）；
  2. `eglInitialize` 对重复调用幂等返回 `EGL_TRUE`。
- 这是"最小兼容面"，风险远低于全栈移植，也回答了"为什么只改 so 不够"——
  根在底层契约，补底层才治本。

---

## 3. 三种实现路线（按风险/健壮性排序）

### 路线 1（已实现）：复用 Vector hook 做 app 内 EGL 拦截（符号级、scope 限定）

> 实现前的关键核查：现有 `PenBridge-Hook`（`hook/source/`）是一套**纯 Java 的
> LSPosed 模块**（通过 Vector(zygisk_vector) 注入 `com.coloros.note`），它只做
> Java 方法级 hook（`HookUtils.hookAll`），**本身没有 native inline hook 能力**，
> 而 `eglGetProcAddress`/`eglInitialize` 是纯 native 符号。因此路线 1 的落地方式
> 是**给这个模块新增一个 native 组件**，由 Java hook 在 note 进程内
> `System.loadLibrary` 拉起，再用 Dobby 在进程内做符号级 inline hook。

已落地的文件：
- `hook/source/jni/egl_contract_shim.c` — Dobby inline hook：
  - `my_eglGetProcAddress(name)`：先走真函数；返回 NULL 且 `name` 是核心 GL 函数
    时，回退 `dlsym(libGLESv2.so, name)` 返回真实指针（恢复假设 A）；
  - `my_eglInitialize(dpy,…)`：真函数成功即 `EGL_TRUE`；失败但 `dpy` 有效且能
    `eglQueryString(EGL_VERSION)` 即从已初始化视角返回 `EGL_TRUE`（恢复假设 B，幂等）。
  - `JNI_OnLoad` 里一次性安装，早于引擎 `glewInit()`。
- `hook/source/jni/CMakeLists.txt` + `build_egl_shim.sh` — 用 Android NDK + vendor
  的 Dobby 编出 `libeglshim.so`（arm64-v8a）。
- `hook/source/sources/.../EglContractShim.java` — `ensureLoaded()`，在
  `UiWorkingSetPrefetch` 的 `com.coloros.note` 分支最早调用，失败静默兜底。
- `hook/tools/build_hook_source.py` 已改：自动把 `libeglshim.so` 嵌进 APK
  （沿用既有的 `libpeninput.so` 嵌入路径）；缺 `.so` 时仅告警、不阻断。
- `hook/source/jni/README.md` — 机制 + 构建 + 边界说明。

优点：
- **符号级（按名字，不绑字节偏移）** → 便签升到 16.8/17.x 仍生效，**不崩于本类**；
- **scope=com.coloros.note** → 零系统面影响，不碰 surfaceflinger/系统 UI/游戏；
- 随模块装/卸，可回退，不污染 /system；
- 用进程内 Dobby inline hook，**不依赖** whale/substrate，单 `.so` 自包含。

前提 / 注意：
- 需在**有 NDK 的机器**上编译 `libeglshim.so`（porting Mac 未装 NDK，设备离线也测不了）；
  编译产物落到 `hook/source/resources/lib/arm64-v8a/`，release 流程自动带上。
- 若未来某版把 GLEW 初始化提前到 app 启动（静态），把 `ensureLoaded()` 移到
  `handleLoadPackage` 最前一行即可；当前引擎是开手写笔记时才 init，安全。

**这正是用户设想的"底层适配 → 上层 so 不崩"的干净实现。**

### 路线 2：系统级 libEGL 包装（module overlay `/system/lib64/libEGL.so`）
写 wrapper `libEGL.so`，内部 `dlopen` 真实驱动并转发所有 EGL 调用，仅对上述两点放松。

优点：覆盖所有用到 GLEW/该契约的 OPPO app，不止便签。
缺点：**全系统面**（每个走 EGL 的进程都经它）→ 一处 bug 可能 bootloop 或全屏渲染
异常；需处理 linker namespace 可见性；回退需重刷。**风险高，仅当要覆盖多 app 时考虑。**

### 路线 3（现状）：每版本字节补丁
事件级、偏移绑定 → 必复发。仅作最后兜底，不应作为主方案。

---

## 4. "无论怎么更新都不崩"成立吗

- **对本崩溃类（GLEW/EGL 契约）**：成立。shim 修的是契约层，与 so 版本无关。
- **不对所有未来问题**：OPPO 后续版本可能依赖别的系统契约（新 EGL 扩展、
  新 gralloc 版本、`android.hardware.graphics` 版本、native_window 行为等）。
  shim 不保证覆盖未知契约。
- 因此**保留版本钉版守卫作兜底**：ABI 漂移 / 未知契约时显式告警而非静默坏
  （见 `sunia_recurrence_root_cause_20260918.md` 的 `guard_note_engine.sh`）。

---

## 5. 设备验证（机制已有强证据，shim 行为待真机跑）

机制侧已有强证据，不需再"猜"：
- `note_egl_diag` 同进程探针显示：本机便签进程内 `eglGetDisplay`+`eglInitialize`+
  surfaceless `eglMakeCurrent`+`glGetString(GL_VERSION)` **全部成功**
  （"EGL OK on this thread"），说明硬件/驱动本身能跑 EGL，失败是进程内契约问题。
- `egl_contract_probe.c` 是真机原生探针，可直接验证：
  1. `eglGetProcAddress("glGetString")` 返回值（预期 NULL → 印证假设 A）；
  2. 已 `eglInitialize` 后再次 `eglInitialize` 的返回值（预期 !=1 → 印证假设 B）；
  3. `dlsym(libGLESv2.so,"glGetString")` 是否非空（shim 的回退来源，预期非空）。

shim 落地后真机要确认的两点：
- `libeglshim.so` 被 `EglContractShim` 成功 `loadLibrary`（logcat 见
  `EglShim: contract: loaded` / `hooked eglGetProcAddress`）；
- 打开手写笔记不再 SIGSEGV、笔画正常渲染（验证契约修复生效）。

---

## 6. 建议落地顺序（当前进度）

1. ✅ 路线 1 代码已接入（native shim + Java 加载器 + 构建嵌入）；
2. ⏳ 在有 NDK 的机器上 `bash hook/source/jni/build_egl_shim.sh`
   → 生成 `libeglshim.so`；
3. ⏳ 设备重连 → 先跑 `egl_contract_probe` 坐实假设 A/B；
4. ⏳ 重打并部署 `PenBridge-Hook` APK（含 shim）→ 验收手写渲染；
5. ⏳ 不再需要 per-build 字节补丁（16.7.2 的 `fix_sunia_egl.py` 退居兜底）；
   保留 `guard_note_engine.sh` 应对 ABI 漂移 / 未知契约；
6. HeyTap 锁版仍建议保持（减少无谓更新 + 防未知契约）。

---

## 7. 结论

用户方向**正确且可行**：用一层薄 EGL 契约 shim 让移植底层"像 ColorOS 一样"满足
GLEW，崩溃类即版本无关。最优实现是**复用现有 Vector hook 做 app 内符号级拦截**
（安全、可更新、可回退），而非系统级 `libEGL` 覆盖或全栈移植。
