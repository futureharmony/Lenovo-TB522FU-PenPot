# 涂鸦崩溃："之前好、现在崩" 变化取证（2026-09-18 修正版）

## 0. TL;DR

- 涂鸦/便签崩溃**不是我们改出来的**，也**不是暂停 inkdye 导致的**，更**不是移植环境 EGL 不可用**。
- 真因在 `com.coloros.note` 的 `libSuniaEngine.so` 自身初始化顺序（`EglContext3::eglInit()` → `glewInit()` 早退，整张 `__eglew*` 表 NULL → SIGSEGV）。
- 既然**同进程内 EGL 探针 8/8 通过**，环境完全能跑 surfaceless EGL+GL ES 3.2，**"突然不行"只能是引擎二进制（note app / libSuniaEngine.so）发生了变化**——最可能是 note 自更新到 16.7.2，或移植 ROM 整体 rebase。
- 设备当前离线，§3 的命令用于**一击定位到底是哪个变化**。

## 1. 已排除的"变化"候选（都有实证）

| 候选 | 结论 | 证据 |
|---|---|---|
| ① 我们的 hook 模块（Vector 注入手势分派） | ❌ 无关 | A/B：移出 hook 作用域 + 原版 so，崩溃签名逐字节一致 |
| ② 暂停 ROM 自带笔桥 `com.inkdye.lenovopentocoloros` | ❌ 无关 | A/B：`pm enable` 重新启用 inkdye 后照样崩；note 的 `dumpsys`/二进制里搜不到 `inkdye` 字样；inkdye 仅暴露 `PenStateProvider` ContentProvider，不提供 GPU/EGL/HAL |
| ③ 我们的模块改了 GPU/hwui/kgsl/EGL 系统属性 | ❌ 无关 | 全量脚本扫描 `tb522fu_pen_bridge`：`service.sh`/`post-fs-data.sh`/`action.sh`/`customize.sh` 零 `setprop` 碰图形栈，只做 privapp xml + 3 个 apk + charge-guard + inkdye 禁用 + boot-guard |
| ④ 移植环境 EGL/GL 不可用 | ❌ **推翻前轮错误结论** | 同进程内 `NoteEglDiag` 探针 8/8 绿（见 §2） |

## 2. 环境没问题：同进程探针铁证

`docs/note_egl_diag_20260918.txt` 在 `com.coloros.note` 进程内（pid 12039/19643…）实测：

```
eglGetDisplay(DEFAULT)      -> ok
eglInitialize               -> true v1.5
eglChooseConfig             -> true n=4
eglCreateContext(ES3)       -> ok
eglMakeCurrent(surfaceless) -> true
glGetString(GL_VERSION)     -> OpenGL ES 3.2 V@0800.72 ... Adreno (TM) 830
RESULT: EGL OK on this thread -- a failure elsewhere is not environmental
```

`docs/tombstones_20260918.md §4.1` 同-PID 关联：同一进程探针通过后**数秒**即在 `EglContext3::eglInit()+76` 崩。→ 环境能跑，崩在引擎自己的 `__eglewGetDisplay` 未初始化（NULL dispatch）。

## 3. "变化"候选 —— 设备取证结果（2026-09-18 20:1x，设备已重连）

**✅ 已确认：候选 A 命中 —— `com.coloros.note` 在 2026-09-12 11:06:20 经 HeyTap 应用商店自动更新到 16.7.2。**

实测数据：

| 项 | 值 | 含义 |
|---|---|---|
| `codePath` | `/data/app/~~ACDCL2WthORi3h...==/com.coloros.note-...` | 可更新应用（非 /system 固化） |
| `versionName` / `versionCode` | 16.7.2 / 160070002 | 当前崩溃版本 |
| `firstInstallTime` | 1970-01-02 14:36:22 | ROM 预装占位（构建于 2026-07-02） |
| `lastUpdateTime` | **2026-09-12 11:06:20** | **后装更新时间 = 变化点** |
| `installerPackageName` | **com.heytap.market** | HeyTap 应用商店静默自更新 |
| ROM `ro.build.date` | Thu Jul  2 22:33:35 CST 2026 | ROM 本身 7-2 构建，近期未变 |
| `libSuniaEngine.so` 当前 | 14658800 B（md5 `42fb5fd5…`） | 崩溃版引擎库 |
| `libSuniaEngine.so` stock 备份 | **14696888 B**（`/my_stock/del-app/OppoNote2/OppoNote2.apk` 内） | **尺寸不同 → 更新确替换了引擎库** |
| `pm path` 基版 | 仅 `/data/app` 一条，**无 /system 基版** | `pm uninstall` 会删光，不能靠卸载回退 |
| `com.heytap.market` 状态 | `enabled=0`（已禁用） | 当前不会再自更新，但 09-12 的伤害已造成 |

结论：**"之前好、现在崩"的变化 = 2026-09-12 HeyTap Market 把便签从 ROM 预装版推成 16.7.2**；新版 `libSuniaEngine.so`（14658800 B）的 `EglContext3::eglInit()` 在本移植 Adreno 830 环境下崩。环境本身没问题（同进程探针 8/8 绿），是引擎二进制在该版本引入的回归。

> 候选 B（ROM rebase）/ C（OTA）**排除**：ROM 构建日期 7-2 未变，`pm path` 仅 /data 一条、无系统基版，变化精确落在 09-12 的 note 更新而非 ROM。

**旁证（系统性移植缺口，常量、非变化元凶，与 note 崩溃是独立家族）**
`docs/tombstones_20260918.md §2`：`/odm/lib64/liboplus_gpu_utils.so`、`/odm/lib64/libuahcore.so`、`/vendor/lib64/libpowerhal.so` 在本移植上 MISSING → `urcc-service` 反复崩（P0.9）。note 进程 maps 未加载这些库，非本次元凶。

## 4. 修复路线（变化已定位，恢复"之前好用"状态）

**推荐（最干净、零二进制 patch）：回退便签到 09-12 之前的版本**
- 设备上存在 OEM 旧版备份 `/my_stock/del-app/OppoNote2/OppoNote2.apk`（包名同 `com.coloros.note`，其内 `libSuniaEngine.so` 14696888 B = 旧版引擎）。
- 原地降级（保留数据）：
  ```bash
  adb shell "su -c 'pm install -r -d /my_stock/del-app/OppoNote2/OppoNote2.apk'"
  ```
  （`-r` 保留数据，`-d` 允许降级；ColorOS 若拦 `-99` 则先 `pm uninstall` 旧更新再装，本机无 /system 基版故需谨慎。）
- **阻断复发**：HeyTap Market 现已 `enabled=0`（不会自更新）；若日后启用，需在市场里把便签设"不自动更新 / 锁定当前版"。

**备选（不回退、改新版引擎二进制）：**
- 最小补丁：改 `glewInit` 早退的 `cbz w0,ret`，强制 `__glewInit()`+`eglewInit(dpy)` 两张表都填（原 logic 见 `scripts/patch_sunia_glew.py` 思路，比 code-cave stub 更贴原逻辑、坑更少）；
- 或 ART 层 hook `EglContext3::eglInit`，在 `glewInit()` 返回后补 `eglewInit(dpy)`（Xposed/Vector，免改 so、OTA 后可重打）。

**待用户拍板**：先按"推荐"降级验证能否恢复便签/涂鸦，还是坚持留在 16.7.2 走"备选"补丁。

## 5. 时间线（一句话）

ROM 预装便签（~2026-07-02 构建）→ **2026-09-12 11:06 HeyTap Market 自更新到 16.7.2（替换 libSuniaEngine.so 14658800 B）** → 新版引擎在本移植 EglContext3 初始化崩 → 用户"之前好现在不行"。环境 EGL 正常（同进程探针 8/8 绿），与我们的模块 / inkdye / GPU 属性改动均无关。

## 6. 修复落地（2026-09-18 实测通过）—— 方案 B：换引擎库，不降级 apk

用户选择保留 16.7.2（最新版 apk），只换回能跑的旧引擎库。实测**涂鸦崩溃已消除**。

**操作步骤（已执行）：**
1. 从 OEM 备份提取旧引擎库（Mac 侧 python，设备 toybox unzip 抽该 296MB apk 失败）：
   `python3 -c "import zipfile; z=zipfile.ZipFile('/tmp/OppoNote2_stock_old.apk'); open('/tmp/libSuniaEngine_16.6.14.so','wb').write(z.read('lib/arm64-v8a/libSuniaEngine.so'))"`
   → 14696888 B，md5 `1b0481baf9badda6734d94ef836ebac1`。
2. 用户手动把便签重装回 16.7.2 最新版（新 path `~~NPanMARyUqqslSo22_eDUw==/com.coloros.note-tn0bB7OUE6tkZ9fR5qFpGA==`）。
3. 替换该 path 下 `lib/arm64/libSuniaEngine.so`：
   `cp /tmp/libSuniaEngine_16.6.14.so $LIB && chmod 755 $LIB && chown system:system $LIB && chcon u:object_r:apk_data_file:s0 $LIB && restorecon $LIB`
   → md5 变 `1b0481ba`，owner/context 保持。
4. `am force-stop` + 启动 `QuickPaintActivity` 实测。

**实测结果：** 无新 tombstone；`topResumedActivity = com.coloros.note/com.nearme.note.paint.QuickPaintActivity`（前台 Resume）；`pidof` 稳定存活；日志显示笔输入通道已建立（`[Gesture Monitor] LenovoPenGlobalHaptic`、`PointerEventDispatcher0`）；lib 未被系统重新解压覆盖（md5 仍为 1b0481ba）。**涂鸦崩溃修复确认。**

**风险与维护：**
- 这是"16.7.2.apk + 16.6.14 引擎"混合体；涂鸦所需 EGL 初始化已修复，但 16.7.2 Java 层若调用 16.6.14 引擎缺失的新 JNI 符号，其他子功能可能异常（目前未观察到）。
- **防复发（关键）**：HeyTap Market 若再次自更新便签到 ≥16.7.2，会把引擎库覆盖回崩溃版。务必在市场里把便签设"不自动更新 / 锁定当前版"，或保持 HeyTap Market `enabled=0`。
- 回退（恢复崩溃版，不推荐）：`cp /data/local/tmp/libSuniaEngine_16.7.2_orig_newpath.bak $LIB` 即可。
- 备份：16.7.2 原版引擎 `/data/local/tmp/libSuniaEngine_16.7.2_orig_newpath.bak`；旧版引擎 `/data/local/tmp/libSuniaEngine_16.6.14.so`；Mac 侧 `/tmp/note_16.7.2_base.apk`、`/tmp/OppoNote2_stock_old.apk`、测试密钥 `/tmp/penkey.jks`（密码 penpen123）。
