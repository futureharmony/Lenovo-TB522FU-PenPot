# libSuniaEngine.so 笔迹不渲染 / 涂鸦崩溃：新旧 so 差异与修复方案

日期：2026-09-18（设备离线，纯静态二进制分析）
涉及文件：`com.coloros.note` 的 `libSuniaEngine.so`
- 新（崩溃版，当前 rom 自更新得到）：16.7.2，md5 `42fb5fd53e04368ca42d255579075d96`，14658800 B
- 旧（被换入、不崩但不渲染）：16.6.14，md5 `1b0481baf9badda6734d94ef836ebac1`，14696888 B

## 0. 现象

- 16.7.2 引擎：打开涂鸦画布即 SIGSEGV（`EglContext3::eglInit()+76`，`__eglewGetDisplay` 为 NULL dispatch）。
- 16.6.14 引擎换入后：不崩、画布能开、笔**震动（haptics）正常**，但**手写笔画不渲染到画板**。
- 结论：两种引擎都有问题，根因不同，需分别解释。

## 1. 新旧 so 差异（重点）

### 1.1 渲染后端一致
双方都是 **GLES3 渲染**（`GLES3Render::GPUTexture`、`RedrawTool::getTriangleProgram`/`getPathPointProgram`、`g_fillUniformPenData` 等），所以"不渲染"不是 Vulkan/Skia 后端切换问题。

### 1.2 `EglContext3::eglInit` 流程结构一致
16.7.2（@0x697c70）与 16.6.14（@0x682b24）的 `eglInit` 逐指令同构：
```
makeTempEglContext() -> glewInit() -> clearTempEglContext()
-> 经 GOT 表 dispatch __eglewGetDisplay / __eglewInitialize ...
```
差别仅 GOT 偏移与全局变量地址（构建布局差异）。**两者都依赖 glew 填 EGL 表**，不是 16.6.14 走了不同初始化路径。

### 1.3 关键 ABI 差异（解释"16.6.14 不渲染"）
16.7.2 引擎新增了笔迹数据结构的重载：
- 16.7.2：`g_fillUniformPenData(..., PenCommonVertex::stPenData, ...)`（CurveImpl / DrawCurve 两版）
- 16.6.14：`g_fillUniformPenData(..., PenVertex::stCustomInfo, ...)`（同两版）

即 **16.7.2 app 与 16.7.2 引擎的笔迹数据结构是匹配的**；把 16.6.14 引擎塞进 16.7.2 app，app 按新布局（`stPenData`）喂笔画点，旧引擎按旧布局（`stCustomInfo`）读取 → 顶点偏移错位 → 画布上无可见笔画。而**震动/haptics 走独立且稳定的输入路径**，所以照常触发。

> 动态符号总差异主要是 OpenSSL/boringssl 版本噪声（ENGINE_*/DTLS* 等），与渲染无关，已排除。

## 2. 16.7.2 崩溃根因（精确）

`makeTempEglContext`（16.7.2 @0x69878c）已正确完成：
```
eglGetDisplay(0) -> s_display
eglInitialize(dpy)        ; 成功（失败会 throw）
eglChooseConfig(...)      ; 成功
eglCreateContext(...)     ; s_context
eglMakeCurrent(dpy,NULL,NULL,ctx)   ; 临时 GL 上下文已 current
```
随后 `glewInit`（@0x828bcc）：
```
bl _glewInit            ; GL 版本探测
cbz w0, eglewInit       ; w0==0 成功才进 eglewInit
ret                     ; w0!=0（探测失败）早退 —— eglewInit 不执行
```
`glewInit` 的 GL 探测用 `eglGetProcAddress("glGetString")` 取桌面 GL 函数指针；**在 Android/Adreno 上核心 GL 函数不通过 eglGetProcAddress 暴露 → 返回 NULL → GLEW_ERROR_NO_GL_VERSION → glewInit 早退**。于是 `eglewInit` 永不执行，整张 `__eglew*` EGL 分发表保持 NULL，`eglInit` 里 `blr x8`（x8=`__eglewGetDisplay`=NULL）直接 SIGSEGV。

**为什么 16.6.14 不崩**：其 `makeTempEglContext`（@0x683644，比 16.7.2 多 256B）的上下文建立方式使 glew 的 GL 探测在该构建下成功（或 eglewInit 路径不同），故表被正常填好。但如前所述，它与 16.7.2 app 接口不匹配 → 笔画不渲染。

**为什么不能简单"强制 eglewInit"**：`eglewInit`（@0x81d73c）内部会**二次调用 `eglInitialize(dpy)`**（先 `eglGetProcAddress("eglInitialize")` 再 `blr`）。本机 Adreno 对已初始化 display 的二次 `eglInitialize` 返回 !=1（此前 code-cave stub 实测 `eglewInit(dpy)` 返回 `EGLEW_ERROR_NO_EGL_VERSION` 已证实），故即便强制 glewInit 走到 eglewInit，表仍填不上。必须两点同时改。

## 3. 修复方案（推荐：修 16.7.2 引擎，保留匹配接口）

对 **16.7.2 原版引擎**打两点补丁，产出"已修 16.7.2"引擎：保留与 16.7.2 app 匹配的笔迹接口，同时消除 EGL 表未填导致的崩溃。

| # | 位置 | 指令 | 原字节 | 新字节 | 作用 |
|---|------|------|--------|--------|------|
| 1 | file 0x824bd8 / va 0x828bd8（`glewInit` 的 `cbz`） | `cbz w0,0x828be4` → `b 0x828be4` | `60 00 00 34` | `03 00 00 14` | glewInit 不再因 GL 探测失败早退，强制进入 `eglewInit` |
| 2 | file 0x8197b0 / va 0x81d7b0（`eglewInit` 的 `b.ne`） | `b.ne 0x81d7f4` → `b 0x81d7b4` | `21 02 00 54` | `01 00 00 14` | 忽略冗余二次 `eglInitialize` 的 !=1，继续把整张 `__eglew*` EGL 表填满 |

补丁后：`glewInit` 始终调用 `eglewInit`；`eglewInit` 填表时不再因二次 `eglInitialize` 报错返回。引擎用 GLES（libGLESv2）直接渲染，桌面 GL 表 `__glew*` 空不影响。

脚本：`scripts/fix_sunia_egl.py`（输入 16.7.2 原版，输出已修版，含原始字节校验）。本地验证：
- 输入 md5 = `42fb5fd53e04368ca42d255579075d96` ✓
- 两处字节改对 ✓
- 反汇编 `glewInit` 确认 `cbz` 已变 `b 0x828be4`（不再早退）✓
- 输出 md5 = `db61d1ffdd8c25062920d0a25c697af3`（已落 `/tmp/libSuniaEngine.16.7.2.fixed.so`）

## 4. 上线步骤（待设备重连）

1. 推送已修引擎并替换当前 16.6.14 版：
   ```bash
   adb push /tmp/libSuniaEngine.16.7.2.fixed.so /data/local/tmp/
   LIB=$(adb shell pm path com.coloros.note | sed 's/package://;s#base.apk#lib/arm64/libSuniaEngine.so#')
   adb shell "su -c 'cp /data/local/tmp/libSuniaEngine.16.7.2.fixed.so \$LIB; chown system:system \$LIB; chcon u:object_r:apk_data_file:s0 \$LIB; restorecon \$LIB'"
   ```
2. 重启便签、打开涂鸦画布，确认：
   - 无新 tombstone（`/data/tombstones` 无新增）；
   - `QuickPaintActivity` 前台 Resume；
   - **手写笔画可见渲染**（核心验收点，区别于 16.6.14）。
3. 防复发（系统级，必做）：本补丁是**事件级**（偏移绑定 16.7.2 构建），后续升级会复发。
   见 `docs/sunia_recurrence_root_cause_20260918.md`：① HeyTap Market 锁版/关便签自动更新（根因阻断）；
   ② 模块 `guard_note_engine.sh` 开机钉版（变更后自愈，已接入 `post-fs-data.sh` 并随模块分发
   `engine/libSuniaEngine.16.7.2.fixed.so`）。

## 5. 风险与回退

- 若填表后某 `__eglew*` 函数在本机 eglGetProcAddress 取不到（极端情况），可能换处崩；先抓 tombstone 定位具体 cell。
- 万一渲染仍异常，回退：`cp /data/local/tmp/libSuniaEngine_16.7.2_orig_newpath.bak $LIB`（16.7.2 原版，会崩但可诊断）或重新换 16.6.14（不渲染但可用）。
- 桌面 GL 表 `__glew*` 为空：当前判断不影响（GLES 直连），若后续发现依赖桌面 GL 的路径，需再补 `_glewInit` 的 `glGetString` 取指方式（指向 libGLESv2 的导出符号），本次未做。
