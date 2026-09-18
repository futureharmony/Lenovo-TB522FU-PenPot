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
| `releases/tb522fu-pen-bridge-v0.1.0.zip` | KernelSU 模块（含 Hook 副本、PenHidCtl priv-app、charge-guard、panic） |
| `releases/PenBridge-Hook-tb522fu-v4.1.3.apk` | Xposed 模块本体（com.aclaniakea.lenovopenbridge） |
| `releases/PenHidCtl-tb522fu-1.1.0.apk` | HID 控制器（以 priv-app 形式随模块装载） |

## 2. 部署（全部可脚本化）

```sh
# 1) 装/更新 Hook APK —— 注意：adb install 会被 ColorOS 拦（Failure [-99]），必须走 root pm
adb push releases/PenBridge-Hook-tb522fu-v4.1.3.apk /data/local/tmp/PenBridge-Hook.apk
adb shell su -c 'pm install -r -d /data/local/tmp/PenBridge-Hook.apk'

# 2) 用 vector-cli 启用模块 + 配置作用域（8 项，全部 user 0）
CLI=/data/adb/modules/zygisk_vector/cli
adb shell su -c "$CLI modules enable com.aclaniakea.lenovopenbridge"
adb shell su -c "$CLI scope set com.aclaniakea.lenovopenbridge \
  android/0 com.coloros.note/0 com.oplus.exsystemservice/0 com.oplus.healthservice/0 \
  com.heytap.mydevices/0 com.oplus.ipemanager/0 com.oplus.wirelesssettings/0 com.oplus.screenshot/0"

# 3) 刷 Root 模块
adb push releases/tb522fu-pen-bridge-v0.1.0.zip /data/local/tmp/pen-bridge.zip
adb shell su -c 'ksud module install /data/local/tmp/pen-bridge.zip'

# 4) 重启
adb shell su -c reboot
```

### vector-cli 速查

```sh
cli status                 # 框架 + 模块数
cli modules ls             # 列出模块及 enabled/disabled
cli modules enable|disable <pkg>
cli scope ls <pkg>         # 查看作用域
cli scope set <pkg> a/0 b/0 ...   # 覆盖作用域
cli log cat                # 导出框架日志（含 VectorLegacyBridge 加载记录）
cli db backup|restore|reset
```

## 3. 作用域（8 项）

来自 `hook/source/resources/res/values/arrays.xml`，作用域决定注入哪些进程：

```
android                       # system_server：笔按键、刷新率投票、uevent 桥
com.oplus.ipemanager          # 笔卡片/状态核心（最大一块，2818 行 hook）
com.heytap.mydevices          # 「我的设备」卡片
com.coloros.note              # 笔记工具
com.oplus.screenshot          # 截图/圈选
com.oplus.exsystemservice     # 广播/Binder 目标
com.oplus.healthservice       # 广播/Binder 目标
com.oplus.wirelesssettings    # 无线设置（充电相关 UI）
```

缺哪一项，对应功能静默失效（不报错）。

## 4. 验证清单

### 4.1 框架层
```sh
adb shell su -c '/data/adb/modules/zygisk_vector/cli log cat' | grep VectorLegacyBridge
```
预期：出现 `Loading legacy module com.aclaniakea.lenovopenbridge` +
`Loading class com.aclaniakea.colorosporttuning.UiWorkingSetPrefetch`，
并对 **uid 1000（system_server）** 也出现（证明注入 system_server 成功）。

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
> 若 hook 导致卡死，须用 `vector-cli modules disable com.aclaniakea.lenovopenbridge`
> 或安全模式处理。**排查卡死时两者要分开停用**。

## 6. 实战踩坑记录（2026-09-18）

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | **开机卡死**：system_server 起来后卡在 PMS 扫描，`boot_completed` 永不为真，开机动画无限循环 | `SystemStylusHooks` 中 `SystemServer#startOtherServices`/`#run` 的 after-hook **无 try/catch**，`init()` 抛出的异常逃逸进 system_server 启动序列 | 给回调加 `catch (Throwable)`；PhoneWindowManager 回调同样加保护。修复后 hook + 模块同开可正常启动 |
| 2 | `adb install` 失败 `Failure [-99]` | ColorOS 安装器限制 | 改 `su -c 'pm install -r -d ...'` |
| 3 | 换签名后无法覆盖安装 | 密钥库口令丢失（旧会话生成未记录） | 重新生成 `keys/tb522fu.jks`（alias `tb522fu`），口令落 `keys/tb522fu.pass`，构建脚本默认读它；先 `pm uninstall` 再装 |
| 4 | charge-guard 静默不启动 | `customize.sh` 的 `set_perm` 漏了 `charge-guard.sh` → 装成 0644 → `[ -x ]` 判定失败 | 补 `set_perm`，并把调用改为 `[ -f ] && sh`（不依赖执行位） |
| 5 | 担心 path-sync 损坏框架库 | `/data/adb/lspd/config/modules_config.db` **是 Vector 的活库**（API 102），`LsposedPathSync` 按 LSPosed schema 写有风险 | 默认禁用，`touch $MODDIR/enable-lsposed-path-sync` 可重新开启 |

## 7. 构建环境

```sh
# hook APK
python3 hook/tools/build_hook_source.py     # 默认读 keys/tb522fu.jks + keys/tb522fu.pass
# PenHidCtl
python3 penhidctl/tools/build_penhid.py
# 模块 zip
python3 module/tools/build_root.py module releases/tb522fu-pen-bridge-v0.1.0.zip
```

- 工具链：`/tmp/android-sdk`（build-tools android-15 + platform-35）。
- 签名：`keys/tb522fu.jks`（gitignored），口令 `keys/tb522fu.pass`。**别再丢**——
  丢了就得重新生成并卸载重装所有 APK。
