# TB522FU 联想笔桥接 · LSPosed 路线安装与验证

> 路线决策（2026-09-18）：**先走 LSPosed 路线**。静态 patch / 重打包 ROM 方案暂缓，
> 等 hook 逻辑在实机上迭代稳定后再考虑固化进底包。见 `docs/p0-recon-20260918.md` 架构节。

## 0. 前提

- 设备：Lenovo TB522FU（sun / SM8750P），已刷移植版 ColorOS。
- Root：KernelSU（`su -c` 可用）。
- LSPosed：已安装且 `lspd` 可用（判定：`/data/adb/lspd` 存在）。
- 调试通道：**用 USB**。无线 adb 不稳，掉线会误导排查。

## 1. 产物清单（本项目 releases/）

| 文件 | 用途 | 安装方式 |
|---|---|---|
| `tb522fu-pen-bridge-v0.1.0.zip` | KernelSU 模块（含 Hook 副本 + PenHidCtl + charge-guard） | KSU 管理器刷入 |
| `PenBridge-Hook-tb522fu-v4.1.3.apk` | LSPosed 模块本体（已被打进 zip 的 `hook/`，此份用于手动安装/调试） | 见第 3 节 |
| `PenHidCtl-tb522fu-1.1.0.apk` | 蓝牙 HID 控制（已打进 zip 的 `system/priv-app/aclpenhid/`） | 由模块安装 |

模块 zip 内容（12 项）：module.prop / customize.sh / post-fs-data.sh / service.sh /
action.sh / uninstall.sh / charge-guard.sh / README.md /
`system/priv-app/aclpenhid/PenHidCtl.apk` / `system/etc/permissions/privapp-permissions-com.aclaniakea.penhidctl.xml` /
`bin/lsposed-path-sync.jar` / `hook/PenBridge-Hook.apk`。

## 2. 刷入模块（KSU）

```sh
adb push releases/tb522fu-pen-bridge-v0.1.0.zip /sdcard/Download/
```

1. KernelSU 管理器 → 模块 → 从本地安装 → 选 `tb522fu-pen-bridge-v0.1.0.zip`。
2. **重启**。
3. 重启后确认：
   - `adb shell su -c 'ls /data/adb/modules/tb522fu_pen_bridge'`
   - 模块状态为「已启用」，无「安装失败」。

> 此时 **inkdye 仍保持启用**（`service.sh` 不再自动禁用），基础书写/按键无缝可用。
> 这是刻意的：避免 hook 未生效时出现触觉反馈空窗。

## 3. LSPosed 作用域

`post-fs-data.sh` 会调用 `bin/lsposed-path-sync.jar`（`com.aclaniakea.tools.LsposedPathSync`）
把模块 APK 的路径与作用域**写进 `/data/adb/lspd/config/modules_config.db`**，作用域共 8 项：

```
android
com.coloros.note
com.oplus.exsystemservice
com.oplus.healthservice
com.heytap.mydevices
com.oplus.ipemanager
com.oplus.wirelesssettings
com.oplus.screenshot
```

**仍需手动核对**（自动写入依赖 `lspd` 目录存在且 jar 执行成功）：

1. 打开 LSPosed 管理器 → 模块 → 找到「PenBridge / 联想笔桥接」。
2. 确认**已启用**，且作用域勾选上表 8 项（缺一项则对应功能静默失效）。
3. **二次重启**（作用域变更需重启生效）。
4. 若管理器里看不到本模块：说明 `post-fs-data.sh` 的 path-sync 未生效，检查
   `/data/adb/lspd/config/modules_config.db` 是否可写、jar 是否被 SELinux 拦。

## 4. 验证清单

在设备上跑 KSU 管理器的「执行」按钮（`action.sh`），它会打印状态总览。
逐项对照：

### 4.1 模块/进程层
- `hook_apk=` 有路径（`pm path com.aclaniakea.lenovopenbridge`）
- `hidctl_apk=` 有路径
- `lsposed=installed`
- `charge_guard_pid=` 非空

### 4.2 hook 是否真的加载
```sh
adb logcat -d | grep -iE "LSPosed|lenovopenbridge|colorosporttuning" | tail -40
```
预期：出现模块加载日志（无 `ClassNotFound` / `NoSuchMethod` 之类别名不匹配的报错）。

### 4.3 功能层（按 P1 验收）
- [ ] 笔 BLE 连接状态正常（设置页不灰）
- [ ] 电量回显正确（`ipe_pencil_battery_level`）
- [ ] 设置页「断开」→ 真实断开（CONNECT/DISCONNECT_PENCIL 链路）
- [ ] 笔按键：单击/双击 触发对应动作
- [ ] 磁吸吸附弹窗 / 胶囊（`SHOW_PENCIL_CAPSULE` 广播链路 → Hook APK 接收端）
- [ ] 书写触觉反馈（Haaptic）

### 4.4 充电守护（charge-guard，不依赖 LSPosed）
- [ ] 吸附 → 通知触发
- [ ] 充满 → 通知 + 兜底断电（`tx_status`）
- [ ] 电量回落 ≤95% 且有磁吸 → 恢复充电通知
- [ ] 低于 100% 吸附时 `ipe_pencil_charging_state` 被修正为 1

## 5. inkdye 切换时机（关键）

`com.inkdye.lenovopentocoloros` 与本模块 hook 功能重叠。**不要提前禁用**。

流程：

1. 先按第 3 节确认 LSPosed 作用域生效、第 4.2 节确认 hook 已加载。
2. 确认第 4.3 节功能（尤其触觉反馈、按键）由 hook 承接后，
   用 KSU「执行」按钮切换：
   ```sh
   adb shell su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh disable'
   ```
   或 `toggle` 在 enable/disable 间来回。状态记录在 `inkdye-disabled.state`。
3. 若 hook 异常需回退：`action.sh enable` 立即交还系统内置笔桥。

## 6. 回退 / 卸载

- 停充电守护：`touch /data/adb/modules/tb522fu_pen_bridge/disable-charge-guard`（重启生效）。
- 完整卸载：KSU 管理器卸载模块 → `uninstall.sh` 会：
  - `pm enable` 恢复 inkdye
  - 杀掉 charge-guard 进程
  - `echo 1 > tx_status` 恢复充电
  - 清掉 boot-guard 计数与 `disable` 标记（避免重装后立刻熔断）
- 临时禁用 hook：LSPosed 管理器里关掉本模块（不动模块本身）。

## 6.5 启动失败与救援（分层）

模块自身有**两级自保**，KernelSU 还提供**三层外部救援**。按严重程度从轻到重：

### 第 0 层：模块自保（本项目实现）

**A. 超时上限（防死机）**
`post-fs-data.sh` 是全模块唯一会阻塞开机的脚本，其中 `app_process` 调用已用
`timeout 20` 包住（无 `timeout` 时回退到后台+轮询）。任何一次卡住都不会把
post-fs-data 拖成死机。`customize.sh` 里的同类调用也加了超时。

**B. 启动失败熔断（boot guard）**
- `post-fs-data.sh` 每次开机第一步就在 `/data/adb/tb522fu_pen_bridge.bootfail` 记账 +1。
- `service.sh` 只在**确认 `sys.boot_completed=1`** 之后才把计数清零（不是一到
  late_start 就清，避免"能跑到 late_start 但随后崩掉"的坏镜像被误判为成功）。
- 连续 **>3 次**未启动完成 → 自动生成 `disable` 文件：
  - KernelSU 下次开机直接跳过本模块；
  - 同时 `pm enable` 交还 inkdye、恢复 `tx_status=1`、清掉进程 pidfile。
- 结果：最坏情况是**丢 3 次开机**，然后系统自己恢复，不需要任何人工介入。

**C. 一键 panic**
还能拿到 root shell 时：
```sh
su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh'          # 恢复状态 + 停用模块
su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh --keep'   # 只恢复状态，保留模块
```
它会立刻 `pm enable` inkdye、杀掉守护/服务进程、恢复 `tx_status=1`、清一次性标记，
清掉失败计数，并（默认）标记模块停用。

### 第 1 层：KernelSU 安全模式（内核级，最可靠）

**开机首个画面出现后，连按「音量−」三次以上**（按下-抬起，不是长按）。
进入后**所有模块被停用**，在 KSU 管理器里可卸载本模块。

> ⚠️ 音量键监听在内核模块初始化时注册、在 `on_post_fs_data` 阶段注销，
> 所以要**抢在开机动画前**按完。开机快或按得慢会错过。
> 另外：安全模式只停用模块，不能挽救"initrc 里写了坏代码"的情况——本模块没有 initrc，不受影响。

### 第 2 层：ksud 命令行

能通过 adb 拿到 root shell 时，不依赖模块目录：
```sh
adb shell su -c 'ksud module list'
adb shell su -c 'ksud module disable tb522fu_pen_bridge'
adb shell su -c 'ksud module uninstall tb522fu_pen_bridge'
adb reboot
```

### 第 3 层：Recovery（系统完全起不来，adb 也连不上）

进 TWRP/第三方 Recovery → 挂载 `/data` → 直接删模块目录：
```sh
rm -rf /data/adb/modules/tb522fu_pen_bridge
```
删完重启，KernelSU 不会加载任何已删除的模块。

### ⚠️ 本模块不会做的事

- **不写 display/DSI/panel 节点**（黑屏根因已隔离到 fix 模块，本模块的
  post-fs-data 明确不碰）。
- **不注入 initrc / 不装自定义内核模块**（无 `.ko`）→ 安全模式对本模块有效。
- **不改 vbmeta / boot 分区** → 本模块的问题不会导致需要重刷 boot。

## 7. 已知坑

| 现象 | 原因 | 处理 |
|---|---|---|
| 刷完无任何变化 | post-fs-data 早于 PMS 恢复 /data/app，LSPosed 读不到模块 | 已由 path-sync 解决；仍失败就手动装 APK 再核对 |
| 改作用域后无效果 | 作用域变更未重启 | 二次重启 |
| 触觉反馈消失 | 提前禁用了 inkdye 而 hook 未生效 | `action.sh enable` 回退 |
| `pm disable` 返回 0 但无效果 | ColorOS 对某些操作静默失败 | 以 action.sh 打印的实际状态为准，别信 rc |
| charge-guard 通知没弹 | ColorOS 丢弃 uid 0（shell）通知 | 通知 UI 走 Hook APK 的 `SHOW_PENCIL_CAPSULE` 广播；`cmd notification` 仅作 AOSP 兜底 |
| 重启后笔不重连 | 开机 CONNECT_PENCIL 重试次数用尽 | 看 `pen-bridge.log`；手动再吸附一次 |
