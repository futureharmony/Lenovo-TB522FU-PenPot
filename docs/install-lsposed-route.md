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
- 临时禁用 hook：LSPosed 管理器里关掉本模块（不动模块本身）。

## 7. 已知坑

| 现象 | 原因 | 处理 |
|---|---|---|
| 刷完无任何变化 | post-fs-data 早于 PMS 恢复 /data/app，LSPosed 读不到模块 | 已由 path-sync 解决；仍失败就手动装 APK 再核对 |
| 改作用域后无效果 | 作用域变更未重启 | 二次重启 |
| 触觉反馈消失 | 提前禁用了 inkdye 而 hook 未生效 | `action.sh enable` 回退 |
| `pm disable` 返回 0 但无效果 | ColorOS 对某些操作静默失败 | 以 action.sh 打印的实际状态为准，别信 rc |
| charge-guard 通知没弹 | ColorOS 丢弃 uid 0（shell）通知 | 通知 UI 走 Hook APK 的 `SHOW_PENCIL_CAPSULE` 广播；`cmd notification` 仅作 AOSP 兜底 |
| 重启后笔不重连 | 开机 CONNECT_PENCIL 重试次数用尽 | 看 `pen-bridge.log`；手动再吸附一次 |
