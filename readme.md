# tb522fu-pen-port — 联想手写笔 TB522FU 彻底适配

把 coloros-pad-fixes 的 `lenovo_pen_bridge`（ACLaniakea，v4.1.3，面向 TB710FU / SM8650Q / pineapple）移植到 **Lenovo TB522FU**（`sun` / SM8750P / 骁龙 8 Elite，运行移植版 ColorOS）。

## 结构

```
module/       KernelSU/Magisk Root 模块（含 charge-guard 充电守护）
hook/         LSPosed Hook 源码（com.aclaniakea.lenovopenbridge）
penhidctl/    priv-app HID 控制器源码
scripts/      设备侦察/推送辅助脚本
docs/         分析与验证记录
fix-module/   显示基线（refresh_rate_config）等的独立 fix 模块
releases/     构建产物（模块 zip / Hook APK / PenHidCtl APK）
```

## 路线决策

**先走 LSPosed 路线**（2026-09-18）。静态 patch / 重打包 ROM 方案暂缓，待 hook 逻辑实机稳定后再固化进底包。
完整安装与验证步骤见 **[`docs/install-lsposed-route.md`](docs/install-lsposed-route.md)**。

## 安装/回退

- **安装**：`scripts/push_to_device.sh` 推送产物 → KSU 管理器刷 `releases/tb522fu-pen-bridge-v0.1.0.zip` → 重启。
- **inkdye 切换为手动**：模块**不再开机自动禁用** `com.inkdye.lenovopentocoloros`。
  先用 KSU「执行」按钮（`action.sh`）确认 hook 已生效，再手动 `action.sh disable`；
  避免 hook 未生效时出现触觉反馈空窗。
  - 切换：`su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh disable|enable|toggle'`
  - 状态记录于 `inkdye-disabled.state`。
- **回退**：卸载模块 → `uninstall.sh` 自动 `pm enable` 恢复 inkdye、杀守护、恢复 TX=1。
  临时仅停守护：`touch $MODDIR/disable-charge-guard`。

## 已完成的移植改动

1. `hook/.../DeviceGate.java`：SM8650Q/PINEAPPLE → SM8750P/SUN
2. `module/service.sh`：Root 侧设备门同步改；Hall 节点改为单节点 `och1909/hall3`（实测：吸附=0，离开=1）；CPS GPIO keeper 停用（充电由驱动自身经 `tx_status` 管理）
3. `module/module.prop`、`customize.sh`：模块 ID 改为 `tb522fu_pen_bridge`，避免与原模块冲突
4. inkdye 禁用改为手动（`action.sh`），`service.sh` 不再自动禁用

## TB522FU 新增功能：充电守护（charge-guard.sh v2）
- **磁吸通知**：沿用 monitor_hall_capsule（已映射到 `och1909/hall3`）。
- **充电状态修正**：实测 IPeManager `ipe_pencil_charging_state` 恒 0，由守护按真实 吸附+TX 状态回写。
- **充满通知**：吸附 + 电量>=100 + TX 关闭 → 通知「已充满，已停止充电」。
  ⚠️ ColorOS 丢弃 uid 0（shell）通知 → UI 走 Hook APK 的 `SHOW_PENCIL_CAPSULE` 广播，`cmd notification` 仅作 AOSP 兜底。
- **充满断电**：实测由 cps-wls-charger 驱动自带（笔满自发 `cps_wls_en:0`）；守护仅在驱动异常时兜底写 `0`。
  - ⚠️ TX 写入为**瞬时**：`echo 1` 后约 30s 被驱动按自身策略改回；写入只认裸数字 `0`/`1`。
- 开关：`touch $MODDIR/disable-charge-guard` 临时停用；卸载时自动恢复 TX=1。

## 待验证 / 进行中

见 `TODO.md`。硬件节点侦察已完成：TB710FU 的 `pen1_hall/pen2_hall`、CPS I2C/GPIO 在 TB522FU 无对应物，
已改用 `och1909/hall3`（磁吸）+ CPS8601 `11-0041/tx_status`（充电）。详见 `docs/p0-recon-20260918.md`。
当前卡点：实机刷入 + LSPosed 作用域配置（见 `docs/install-lsposed-route.md`）。

上游与背景分析：
- 上游仓库：`../coloros-pad-fixes`（仅读，不改动）
- 方案对比：`../pen-bridge-comparison.md`
- 难度评估：`../pen-bridge-porting-difficulty.md`
