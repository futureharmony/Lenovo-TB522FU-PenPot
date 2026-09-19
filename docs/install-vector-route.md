# TB522FU 联想笔桥接 · 安装与验证（Vector 路线）

> **框架事实修正（2026-09-18 实测）**：本机跑的不是 LSPosed，而是
> **Vector**（JingMatrix，模块 id `zygisk_vector`，v2.2 / versionCode 3080，
> 自述 "A modern, Xposed-compatible framework for Android application hooking，
> Android 8.1 ~ 17"），守护进程 `vectord`，管理器 `app.morphe.manager`。
> LSPosed **未安装**，也不需要——Vector 提供完整 Xposed 兼容 API。
>
> 本文件取代原 `install-lsposed-route.md`。

## 0. 前提

- 设备：Lenovo TB522FU（sun / SM8750P），移植版 ColorOS（Android 16 底包）。
- Root：KernelSU，`ksud` 位于 `/data/adb/ksud`。
- 框架：`zygisk_vector` 已安装，`vectord` 常驻，`zygiskd64/32` state=1。
- 调试：**无线 adb 不稳，调试期一律用 USB**（`HA2MGBSJ`）。

## 1. 产物

| 文件 | 说明 |
|---|---|
| `releases/tb522fu-pen-bridge-v<版本>.zip` | KernelSU 模块（含 Hook 副本、PenHidCtl priv-app、charge-guard、panic、日志限流 `bin/penlog.sh`）。版本号取 `module/module.prop` 的 `version=`，zip 名由 `build.py` 拼出 |
| `releases/PenBridge-Hook-tb522fu-v*.apk` | Xposed 模块本体（`com.futureharmony.lenovopenbridge`）；**取最新版别手写版本号** |
| `releases/PenHidCtl-tb522fu-*.apk` | HID 控制器（以 priv-app 形式随模块装载） |

> 产物版本一律用 `python3 scripts/pen_release.py hook-apk|module-zip|scope|adb` 解析。
> 手写版本号踩过两次：`deploy_vector.sh` 曾默认 `v4.1.13`、`push_to_device.sh` 曾硬编码 `v4.1.3`
> —— 两者都是**改名前**的旧包名 APK，推过去装 = 装了个永远不会被 `enable` 的包（见第 6 节 #13）。

## 2. 部署（全部可脚本化）

**推荐**：一条命令搞定（含装前校验 + 装后回读断言）：
```sh
scripts/deploy_vector.sh                 # [SERIAL] [--no-module] [--no-scope] [--no-reboot]
```

手工分步：
```sh
# 1) 装/更新 Hook APK —— 注意：adb install 会被 ColorOS 拦（Failure [-99]），必须走 root pm
APK=$(python3 scripts/pen_release.py hook-apk)
adb push "$APK" /data/local/tmp/PenBridge-Hook.apk
adb shell su -c 'pm install -r -d /data/local/tmp/PenBridge-Hook.apk'

# 2) 用 vector-cli 启用模块 + 配置作用域（1 + 7 项，全部 user 0）
#    ⚠️ system_server 用伪包名 `system`，不要写成 `android`（见第 3 节）
#    ⚠️ 作用域**从 scope.list 派生**，不要手敲——`scope set` 是整表覆盖，漏一项就静默删一项
CLI=/data/adb/modules/zygisk_vector/cli
SCOPE=$(python3 scripts/pen_release.py scope)
adb shell su -c "$CLI modules enable com.futureharmony.lenovopenbridge"
adb shell su -c "$CLI scope set com.futureharmony.lenovopenbridge $SCOPE"

# 2b) 回读断言：缺 system/0 就说明白忙（脚本 version 会自动做，手敲请务必自查）
adb shell su -c "$CLI scope ls com.futureharmony.lenovopenbridge"   # 应含 system 0

# 3) 刷 Root 模块
ZIP=$(python3 scripts/pen_release.py module-zip)
adb push "$ZIP" /data/local/tmp/pen-bridge.zip
adb shell su -c 'ksud module install /data/local/tmp/pen-bridge.zip'

# 4) 重启
adb shell su -c reboot
```

### vector-cli 速查

```sh
cli status                 # 框架 + 模块数
cli modules ls             # 列出模块及 enabled/disabled（⚠️ enabled ≠ 已被注入）
cli modules enable|disable <pkg>
cli scope ls <pkg>         # 查看作用域
cli scope set <pkg> a/0 b/0 ...   # 覆盖作用域（整表覆盖！）
cli scope add <pkg> a/0 b/0 ...   # 只追加（**修 scope 用这个，别用 set**）
cli scope rm  <pkg> a/0 ...       # 只删指定项
cli --json scope ls <pkg>  # 结构化输出，脚本解析用（避免解析表格）
cli log cat                # 导出框架日志（含 VectorLegacyBridge 加载记录）
cli db backup|restore|reset
```

## 3. 作用域（1 + 7 项）

**唯一真源**：`hook/source/resources/META-INF/xposed/scope.list`
（构建时打进 APK，`res/values/arrays.xml` 为同一份的镜像）。
运行时表由它派生：`python3 scripts/pen_release.py scope`。
作用域决定注入哪些进程：

```
system                        # system_server：笔按键、刷新率投票、uevent 桥、触觉、输入门控
com.oplus.ipemanager          # 笔卡片/状态核心（最大一块，2818 行 hook）
com.heytap.mydevices          # 「我的设备」卡片
com.coloros.note              # 笔记工具
com.oplus.screenshot          # 截图/圈选
com.oplus.exsystemservice     # 广播/Binder 目标
com.oplus.wirelesssettings    # 无线设置（充电相关 UI）
com.coloros.translate         # 圈选翻译（区域端点修正，见 TODO.md P0.13）
```

缺哪一项，对应功能静默失效（不报错）。

> ⚠️ **致命坑（2026-09-18 定位）：system_server 的作用域名必须是 `system`，不是 `android`。**
> 框架回调用 `packageName="android"` 回调模块（代码里 `case "android"` 是对的），
> 但**作用域匹配用的是伪包名 `system`**。写成 `android` 时：模块能进所有应用进程，
> 唯独**永远不进 system_server**——触觉/笔键/磁吸/输入门控全部静默失效，且日志里
> 只有应用的 hook 日志、没有 `system_server stylus hooks installed`，极易误判为"框架没工作"。
> 排查口诀：`grep 'stylus hooks installed'` 为 0 ⟺ system_server 没注入 ⟺ 作用域名写错。
> 参考：另一个 hook 系统服务的模块 `io.github.artifical0.fcmfix.coloros` 用的也是 `system`。
>
> 🔁 **这个坑会因「改包名」复发**（2026-09-19 实机命中第二次）：改名 = Vector 里多一个全新模块身份，
> 作用域从空开始。所以别依赖"上次已经改对了"——`build.py` 第 4 步与 `deploy_vector.sh` 现在每次都会
> 回读校验，缺就补、多就删。**改包名后必须重跑部署脚本，不能只 install APK。**

## 4. 验证清单

### 4.1 框架层
```sh
adb shell su -c '/data/adb/modules/zygisk_vector/cli log cat' | grep VectorLegacyBridge
```
预期：出现 `Loading legacy module com.futureharmony.lenovopenbridge` +
`Loading class com.aclaniakea.colorosporttuning.UiWorkingSetPrefetch`。

**关键判据（system_server 是否真的注入）**——查 KernelSU 的 logcat 存档最省事：
```sh
adb shell su -c "grep 'system_server stylus hooks installed' /data/adb/ksu/log/logcat.log"
```
有这一行 = 成功；没有 = 作用域写成了 `android`（见第 3 节）。

### 4.2 模块层
- `charge-guard: RUNNING`（进程存在）
- `bootfail` 为空（开机成功后被 service.sh 清零）
- `pm path com.aclaniakea.penhidctl` → `/system/priv-app/aclpenhid/PenHidCtl.apk`

### 4.3 功能层
- 笔吸附后：磁吸弹窗/胶囊、电量回显、设置页不灰
- 笔按键单击/双击、书写触觉反馈
- 设置页「断开」→ 真实断开（CONNECT/DISCONNECT_PENCIL）
- 充电守护：吸附通知、充满通知 + 兜底断电、恢复充电通知

### 4.4 inkdye 切换时机（关键）
`com.inkdye.lenovopentocoloros`（实际装在 `/system/priv-app/LenovoPenBridge/`）
与本模块 hook 功能重叠。**确认 hook 生效后**再手动关：
```sh
adb shell su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh disable'
```
回退：`action.sh enable`。

## 5. 启动失败与救援

### 第 0 层：模块自保
1. **超时封顶**：`post-fs-data.sh`（唯一阻塞开机的脚本）里 `app_process` 调用带 `timeout 20`。
2. **失败熔断**：连续 >3 次未达 `sys.boot_completed=1` → 自动生成 `disable`，
   下次跳过本模块并交还 inkdye、恢复 `tx_status=1`。计数 `/data/adb/tb522fu_pen_bridge.bootfail`。
   （只有确认 `boot_completed=1` 才清零，避免"能到 late_start 但随后崩"被误判。）
3. **一键 panic**：`su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh'`（`--keep` 保留模块）。

### 第 1 层：KernelSU 安全模式
开机首个画面后**连按「音量−」三次**（按-抬，非长按）→ 所有模块停用。
⚠️ 音量键监听在 `on_post_fs_data` 前注销，须抢在开机动画前按完。

### 第 2 层：ksud
```sh
adb shell su -c 'ksud module list'
adb shell su -c 'ksud module disable tb522fu_pen_bridge'
adb shell su -c 'ksud module uninstall tb522fu_pen_bridge'
```

### 第 3 层：Recovery
挂 `/data` → `rm -rf /data/adb/modules/tb522fu_pen_bridge` → 重启。

> ⚠️ 上述救援**只覆盖 KSU 模块**。Vector hook 不在 boot guard 管辖内，
> 若 hook 导致卡死，须用 `vector-cli modules disable com.futureharmony.lenovopenbridge`
> 或安全模式处理。**排查卡死时两者要分开停用**。

## 6. 实战踩坑记录（2026-09-18 / 09-19）

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | **开机卡死**：system_server 起来后卡在 PMS 扫描，`boot_completed` 永不为真，开机动画无限循环 | `SystemStylusHooks` 中 `SystemServer#startOtherServices`/`#run` 的 after-hook **无 try/catch**，`init()` 抛出的异常逃逸进 system_server 启动序列 | 给回调加 `catch (Throwable)`；PhoneWindowManager 回调同样加保护。修复后 hook + 模块同开可正常启动 |
| 2 | `adb install` 失败 `Failure [-99]` | ColorOS 安装器限制 | 改 `su -c 'pm install -r -d ...'` |
| 3 | 换签名后无法覆盖安装 | 密钥库口令丢失（旧会话生成未记录） | 重新生成 `keys/tb522fu.jks`（alias `tb522fu`），口令落 `keys/tb522fu.pass`，构建脚本默认读它；先 `pm uninstall` 再装 |
| 4 | charge-guard 静默不启动 | `customize.sh` 的 `set_perm` 漏了 `charge-guard.sh` → 装成 0644 → `[ -x ]` 判定失败 | 补 `set_perm`，并把调用改为 `[ -f ] && sh`（不依赖执行位） |
| 5 | 担心 path-sync 损坏框架库 | `/data/adb/lspd/config/modules_config.db` **是 Vector 的活库**（API 102），`LsposedPathSync` 按 LSPosed schema 写有风险 | 默认禁用，`touch $MODDIR/enable-lsposed-path-sync` 可重新开启 |
| 6 | **hook 看似没工作**：应用进程有 hook 日志，但触觉/笔键/磁吸等 system_server 侧功能全无效，`grep 'stylus hooks installed'` 为 0 | 作用域名写成 `android`。框架**回调**给的 `packageName` 是 `android`（所以 `case "android"` 是对的），但作用域**匹配**用的是伪包名 `system` → 模块永不进 system_server | 作用域改为 `system`（`scope.list` + `arrays.xml` + 运行时 `cli scope`），重启后 `system_server stylus hooks installed` 出现，全部 hook 生效 |
| 7 | 定位"hook 到底有没有加载"很费劲 | ① `HookUtils.log` 原本只走框架日志（`XposedBridge.log` → Vector module log），而该 sink **会丢弃注入早期的消息**（实测注入时刻日志一条不落盘，第一条存活日志要 ~18s 后才出现）；② 系统 logcat 默认环形缓冲在启动洪流下会把早期行冲掉 | 已让 `HookUtils.log` **同时镜像到 `android.util.Log`**，用 `adb logcat -s LenovoPenBridge` 读；读启动早期日志前先放大缓冲 `su -c 'setprop persist.logd.size 64M'`（开机即生效，会持久化）。另有 KernelSU 存档 `/data/adb/ksu/log/logcat.log` 可跨开机保留 |
| 8 | **偶发卡开机**：同配置多数开机正常，偶发卡死在开机动画且**不自恢复**（`boot_completed` 空、`bootanim=running`），Watchdog 也不救 | Vector 安装 hook 要 `ThreadList::SuspendAll`（持**独占 mutator 锁**），而 system_server 主线程此时正卡在 `BatteryService.onStart → IHealth.update()` 的 **binder JNI 调用**中，`artJniMethodEnd` 需重新获取 mutator 锁 → **互等死锁** | 诊断用 `su -c 'debuggerd -b <system_server_pid>'`：主线程停在 `artJniMethodEnd → ConditionVariable::WaitHoldingLocks`，另有一线程在 `com.v7878.vmtools` 里 `ThreadList::SuspendAll`。**降险已落地（v4.1.4）**：`SystemStylusHooks.installAsync()` 在守护线程里延迟 2500ms 再 `install()`，把 deopt 挪出启动最密的 `startCoreServices` 窗口（详见 TODO.md P0.5）。Vector 侧无免 deopt 配置（已证伪）。⚠️ 概率降低非根治，需继续多刷回归 |
| 9 | charge-guard **整轮不启动**：`ps` 无进程、本轮日志无 started 行、pidfile mtime 停在上一轮，而 service.sh 明明活着并已越过启动点 | 单实例守卫只做 `kill -0 "$old"`，而 pidfile 在 `/data` **跨重启保留**、PID 号被内核复用 → 旧 PID 命中无关进程 → 守护**静默 exit 0** | 改为 **boot_id + `/proc/<pid>/cmdline` 双校验**，pidfile 记 `pid bootid`；service.sh 补启动日志 + 后台 liveness 复核。旧 pidfile（单字段）仍兼容 |
| 10 | inkdye **"日志说禁了其实没禁"**：日志有 `inkdye disabled by default`，但 `dumpsys` 仍 `enabled=0`、`pm list packages -d` 里没有 | 落地动作写在 `exec >>"$LOGFILE"` **之前**的 service.sh 早期段，此时 **PackageManager 尚未就绪**，`pm disable-user` 静默失败（logcat 有 `Missing permission state for package …`） | 移到 `exec` 之后的后台 `apply_inkdye_state()`：**重试（40×3s）+ 用 `pm list packages -d --user 0` 复核状态**后再打印成功 |
| 11 | **笔身触控条手势完全不通**（上滑/下滑/双击无反应），日志 0 条 `touch strip` | v4.1.5 的两处判断**都**错。① **设备名**：真正把键事件送进 `PhoneWindowManager` 的节点是 **`Lenovo Tab Pen Pro 2 Mouse`**（`/dev/input/event8`，uhid `0005:17EF:622E.0001`）；兄弟节点 `…Consumer Control`（`event9`）只上报 `EV_MSC/MSC_SCAN + EV_KEY KEY_UNKNOWN`，**从不进按键队列**，不能拿来匹配。② **分派字段**：`getScanCode()` 恒为 **240**（=内核原始 keycode），手势信息在 **`getKeyCode()`**（`131/132/133`）。`dumpsys input` 对 Mouse 节点打印**空** `KeyLayoutFile` 是个假象——它的 `131/132/133` 恰恰来自 `Vendor_17ef_Product_622e.kl` 的 `key usage` 行（`key usage <hid-usage> <key>` 语法 Android **确实支持**，`Generic.kl` 里用了 25 处） | v4.1.8：闸门 `isPen(device) && name.contains("lenovo tab pen")`，分派改 `switch (getKeyCode())`（`stripGestureToNativeKey()`）：`131→767 / 132→768 / 133→769`。**实机验证通过**：4 个手势分别打出 `code=131/132/133 scan=240` → 注入 767/768/769，且注入事件可见（`dev=Virtual code=768 act=0/1`） |
| 12 | 代码已按 #11 修好、却**依旧 0 条 `touch strip` 日志**，且**所有 app 侧 hook 一切正常**（极易误判成代码 bug） | 用 `vector-cli scope set` 重设作用域时它**整表覆盖**，而 `scripts/deploy_vector.sh` 里的 SCOPE 首项写的是 `android/0`、**漏了 `system/0`**（与 #6 的结论相反）→ 模块**完全不再注入 system_server**。判据：`vector-cli log cat` 里**找不到** system_server 的 `Loading legacy module` 行；`adb logcat -s LenovoPenBridge` 无 `handleLoadPackage pkg=android`、无 `interceptKeyBeforeQueueing hooks`。**日志缓冲区不是元凶**（`/proc/uptime` 仅 ~100s 时 main/system 缓冲仍保有开机首秒的行） | 脚本 SCOPE 首项改回 `system/0`（与本节第 3 节、`scope.list`、`arrays.xml` 对齐）并加注释；重启后 `system_server stylus hooks installed (startOtherServices=1 run=1)` 立即恢复。⚠️ 教训：**部署脚本与文档必须同源**，否则 `scope set` 的静默覆盖会把已修好的能力删掉 |
| 13 | **改包名后全部自定义键失效**（长按/捏握/书写扩展/系统快捷，功能码 101–113 全灭），设置页里的 `ipe_pencil_wb_click_*` 键值**仍在**；广播探针 `RUN_ACTION cod=999` 连 `debug action refused` 都没有 | **改包名 = Vector 里换了一个全新模块身份**，作用域从空开始，重设时又写成 `android` → 与 #12 同一个坑第二次踩（#6 的结论被改名清零）。`vector-cli modules ls` 显示 `enabled` **不代表已被注入** | ① 现场：`scope add <pkg> system/0` + 重启（别用整表 `set`）；② **根治**：作用域改为从 `scope.list` 单一真源派生，`build.py` 第 4 步 + `deploy_vector.sh` 每次**回读校验** `system/0`，缺则用 `scope add` 补齐、多则 `scope rm`；`push_to_device.sh`/`deploy_vector.sh` 不再硬编码 APK 版本（曾分别是 `v4.1.3`/`v4.1.13`，**都是旧包名**）。详见 TODO.md P1.5 复发一节 |
| 14 | 推送脚本首次真跑就挂：`scripts/push_to_device.sh: line 39: SERIAL_ARG[@]: unbound variable`，**单台设备不传序列号时 100% 复现**（此前只有手写 `adb` 调用，这条路径从没被跑过所以没暴露） | macOS 自带 **bash 3.2**（`/bin/bash` 与 `env bash` 都是），在 `set -u` 下展开**空数组** `${A[@]}` 会直接 abort。`deploy_vector.sh` 的 `A=("$ADB")` 恒有元素故幸免，`push_to_device.sh` 的 `SERIAL_ARG=()` 是空数组 → 必挂 | 全部改写成 `${SERIAL_ARG[@]+"${SERIAL_ARG[@]}"}` 并在定义处加注释禁止"简化"回去。⚠️ 同类隐患搜法：`grep -nE '\$\{[A-Za-z_]+\[@\]\}' scripts/*.sh`，凡赋值可能为空的数组都要用这个形式 |

## 7. 构建环境

```sh
# 统一入口（推荐）：编译 APK → 打包模块 zip → 生成 md5 → 设备在线时做作用域自检
python3 build.py [--push] [--skip-hook] [--no-device] [--no-scope-fix] [--require-device]
# --push 只是转调 scripts/push_to_device.sh（推送路径/校验和的单一真源），推到
# /sdcard/Download/tb522fu-pen-bridge/ 并附带每个产物的 .md5.txt sidecar。

# 分步
python3 hook/tools/build_hook_source.py     # Hook APK（版本单点常量 APK_VERSION，可用 ACL_VERSION 覆盖）
python3 penhidctl/tools/build_penhid.py     # PenHidCtl priv-app
python3 module/tools/build_root.py module releases/tb522fu-pen-bridge-v<版本>.zip   # 内嵌最新 APK
# 版本号来自 module/module.prop 的 version=，build.py 会自动拼出 zip 名（不要手写死版本）
```

相关环境变量（都有默认值）：`ANDROID_SDK`（默认 `/tmp/android-sdk`）、`XPOSED_STUBS`
（默认 `/tmp/acdb/stubs`）、`ACL_KS`/`ACL_KS_PASS`、`ACL_OUT`、`ACL_VERSION`。⚠️ `/tmp` 会被系统清理，
工具链（`/tmp/android-sdk`、`/tmp/acdb/stubs`）重建一次才可用。

- 工具链：`/tmp/android-sdk`（build-tools android-15 + platform-35）。
- 签名：`keys/tb522fu.jks`（gitignored），口令 `keys/tb522fu.pass`。**别再丢**——
  丢了就得重新生成并卸载重装所有 APK。
