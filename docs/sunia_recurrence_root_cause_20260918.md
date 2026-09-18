# 为什么失效 / 是系统兼容问题吗 / 如何防止后续升级复发

日期：2026-09-18｜状态：根因分析 + 系统级防复发方案

> 本文回答三个问题：
> 1. 为什么"换了 so"之后笔画不渲染（失效）？
> 2. 这是"系统兼容层"的问题吗？
> 3. 如果只修这次、不搞清根因，后续升级会不会再来一遍？
>
> 结论先行：**崩溃是系统/EGL 兼容问题；换库后不渲染是 app↔引擎 ABI 版本锁问题。两者都由一个根事件触发——HeyTap 在移植 ROM 上静默自更新便签。只打这次的补丁是"事件级"修复，后续升级极可能复发。系统级修复 = 锁版本（阻断变更）+ 模块级引擎钉版（变更后自愈）。**

---

## 1. 两次"失效"是两类完全不同的故障

必须分开看，否则会把两件事混为一谈：

| 现象 | 发生时机 | 故障类型 | 属于"系统兼容层"吗 |
|---|---|---|---|
| 打开涂鸦即 SIGSEGV（`EglContext3::eglInit+76`，`__eglewGetDisplay`=NULL） | 16.7.2 原版引擎，未做任何替换 | **引擎初始化崩溃** | ✅ 是（GLEW/EGL 初始化假设被移植 EGL 打破） |
| 画布能开、震动正常、但手写笔画不渲染 | 把 16.6.14 引擎塞进 16.7.2 app | **渲染数据 ABI 错配** | ❌ 否（app 与引擎版本锁被破坏） |

所以你问"是因为系统兼容层面的问题吗"——**要分情况答**：

- **原始崩溃（为什么我们不得不换库）**：是系统兼容层问题。见 §2。
- **换 16.6.14 后不渲染**：不是系统兼容问题，是我们把"版本锁"打破了。见 §3。

---

## 2. 原始崩溃为什么是"系统兼容层"问题

`libSuniaEngine.so` 内部**静态捆了一份 GLEW**（桌面 OpenGL 加载器），涂鸦画布初始化走：

```
makeTempEglContext()        ; eglGetDisplay + eglInitialize + eglChooseConfig
                            ; + eglCreateContext + eglMakeCurrent  (display 已 OK)
   -> glewInit()            ; ← 桌面 GL 版本探测
   -> clearTempEglContext()
   -> __eglewGetDisplay ... ; ← 经 GOT 表 dispatch，表空则 NULL → SIGSEGV
```

GLEW 的 `glewInit()` 早期退逻辑依赖一个**桌面环境才成立的假设**：
- 它用 `eglGetProcAddress("glGetString")` 去取核心 GL 函数指针来探 GL 版本；
- **在 Android/Adreno 上，核心 GL 函数不通过 `eglGetProcAddress` 暴露** → 返回 NULL → `_glewInit` 报 `GLEW_ERROR_NO_GL_VERSION` → `glewInit()` 提前 `ret`；
- 于是 `eglewInit()` 永不执行，整张 `__eglew*` EGL 分发表保持 NULL；
- `eglInit()` 里 `blr x8`（x8=`__eglewGetDisplay`=NULL）直接 SIGSEGV。

而 `eglewInit()` 内部还会**二次 `eglInitialize(dpy)`**，本机 Adreno 对已初始化的 display 二次 init 返回 ≠1（code-cave stub 实测确认），所以即便强制进 `eglewInit` 表也填不上——必须两处同时改（见 `sunia_egl_fix_plan_20260918.md` §3）。

**关键判断**：同进程内 EGL 探针 8/8 全绿（`note_egl_diag_20260918.txt`：surfaceless EGL + GLES 3.2 Adreno 830 完全可用），所以环境"能跑"。崩在**引擎二进制对 EGL 行为的硬编码假设**——这份假设在 OPPO 原厂参考驱动上成立，在 TB522FU 移植 ROM 的 Adreno 830 EGL 实现上不成立。

> 这就是典型的"系统/驱动兼容层"问题：OPPO 的 `libSuniaEngine.so` 是**环境相关（environment-specific）**构建，不是可移植二进制。它假定了一个特定的 EGL/驱动契约，移植环境没满足。

---

## 3. 为什么"换了 so"会不渲染（ABI 版本锁）

`com.coloros.note` 的 **app（Java/上层 JNI）和 `libSuniaEngine.so`（引擎）是一对版本锁定的耦合体**。它们的笔迹数据结构必须逐字节匹配：

- 16.7.2 app + 16.7.2 引擎：`g_fillUniformPenData(..., PenCommonVertex::stPenData, ...)` —— 匹配 ✅
- 16.7.2 app + 16.6.14 引擎（我们换入的）：app 按新布局 `stPenData` 喂点，旧引擎按旧布局 `PenVertex::stCustomInfo` 读 → **顶点偏移错位 → 画布无可见笔画**

震动（haptics）走独立的输入/笔桥路径（`tb522fu_pen_bridge` 模块），与渲染引擎无关，所以照常触发——这正是"震动有、笔画没有"的原因。

**结论**：把任意"版本 N 的引擎"塞进"版本 M 的 app"都是不安全的，因为 `.so` 的导出符号签名 + 内部 C++ 结构体布局（隐式 ABI）是 app 契约的一部分。这是**打包/版本纪律**问题，与系统兼容无关。

---

## 4. 根事件：HeyTap 在移植 ROM 上静默自更新

两次失效的**唯一共同上游**是：

> **2026-09-12 11:06 HeyTap Market 把 `com.coloros.note` 从 ROM 预装版自更新到 16.7.2**，替换了 `libSuniaEngine.so`（14696888B → 14658800B）。

证据（`doodle_change_forensics_20260918.md` §3）：
- `installerPackageName = com.heytap.market`；`lastUpdateTime = 2026-09-12 11:06:20`；
- ROM 自身 `ro.build.date = Jul 2` 未变，`pm path` 仅 `/data/app` 一条（无 /system 基版）；
- 与我们的 hook 模块 / 暂停的 inkdye / GPU 属性改动**全部无关**（A/B 实证）。

所以"之前好、现在崩"= 移植 ROM 上跑了一个**假定原厂 EGL 契约**的新版引擎，触发了 §2 的 GLEW 回归。

---

## 5. 为什么"只修这次"不够 —— 后续升级必复发

我们当前的补丁（`fix_sunia_egl.py` 对 16.7.2 原版打两点指令）是**事件级**的，有两个硬伤：

1. **偏移绑定到 16.7.2 构建**：补丁点在 `file 0x824bd8 / 0x8197b0`，脚本已用 `EXPECTED_MD5=42fb5fd5…` 锁版本。如果 HeyTap 再推 16.7.3 / 16.8.x，代码布局一变，偏移就错位 → 打不上或打错位置 → 崩。
2. **没阻断变更本身**：只要便签还能自更新，新引擎随时覆盖回崩溃版（或引入新 ABI，又触发 §3 的错配）。

因此：**只要根事件（HeyTap 自更新）不被阻断，且补丁不随版本刷新，下一次升级几乎必然再来一遍同类问题。** 你的担心是对的。

---

## 6. 系统级防复发方案（推荐组合 A + B）

### A. 阻断变更（根因杠杆，最高优先级）
让便签在移植 ROM 上**不再自更新**。二选一/组合：

```bash
# 1) 长期禁用 HeyTap Market 后台自更新（已 enabled=0，保持其禁用）
pm disable-user com.heytap.market        # 或在市场内把便签设"不自动更新/锁定当前版"

# 2) 仅针对便签锁定：用 adb 关闭其自动更新（需市场支持）
#    市场 → 我的 → 设置 → 自动更新 → 关闭；或便签应用详情 → 取消"自动更新"
```

这是**治本**：环境契约不匹配的引擎根本不会进来。

### B. 模块级引擎钉版（防御纵深，变更后自愈）
让 KSU 模块在每次开机把引擎"钉"回已知良好构建，即使被更新覆盖也能在重启后自愈：

- 模块内置 `engine/libSuniaEngine.16.7.2.fixed.so`（已修、md5 `db61d1ff…`）；
- `post-fs-data.sh` 调 `module/guard_note_engine.sh`（随模块分发，含已修引擎 `module/engine/libSuniaEngine.16.7.2.fixed.so`）：
  - app == 16.7.2 且引擎非已修版 → 覆盖为已修版（同时修复当前"16.6.14 不渲染"状态）；
  - app == 16.7.2 且引擎是 16.6.14 → 覆盖为已修版（恢复笔画渲染）；
  - app != 16.7.2（未来升级）→ **不盲目打补丁（偏移未知）**，写 WARN 标记 + 尝试禁用 HeyTap 自更新，留人工介入。
- 详见 `scripts/guard_note_engine.sh`（含 md5 校验、安全回退、日志）。

> B 不能替代 A：若未来 app 升级改了 ABI，钉 16.7.2 引擎会回到"不渲染"（如 §3）。但 B 能**确保不崩溃**，且**显式告警**而非静默坏掉，给人工留窗口。

### 不推荐
- 只打本次补丁、不做 A/B：事件级，必复发。
- 长期停留在"16.7.2 app + 16.6.14 引擎"：能用但不渲染笔画，且每次更新被覆盖。

---

## 7. 当前设备状态与下一步

- 设备现态：16.7.2 app + 16.6.14 引擎（不崩但**不渲染**），离线。
- 待设备重连（按优先级）：
  1. 推 `libSuniaEngine.16.7.2.fixed.so`、替换为已修 16.7.2 引擎 → 验证**笔画可见渲染**（最终态）；
  2. 装 `guard_note_engine.sh` 进模块 `post-fs-data` → 开机自愈；
  3. 保持/确认 HeyTap Market 不自动更新便签 → 根因阻断。
- 验收：打开涂鸦无 tombstone + `QuickPaintActivity` Resume + 手写笔画可见。

---

## 8. 一句话总结

> 崩是**系统兼容层**（GLEW 初始化假设被移植 EGL 打破）；换库不渲染是**版本锁**（app/引擎 ABI 错配）。根事件是 HeyTap 在移植 ROM 上自更新。只修这次会复发——系统级做法是"**锁版本阻断变更** + **模块钉版自愈**"，二者结合才能扛住后续升级。
