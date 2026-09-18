# TODO

## P0 — 侦察（决定项目成败，先做）
- [ ] 离线：`strings kernel_abs.elf | grep -iE 'lenovo_penraw|PEN_FRAMEWORK|pen_hall|cps'` — 确认内核是否导出笔事件接口
- [ ] 设备在线：跑 `scripts/recon_sysfs.sh`，找 TB522FU 的 Hall / CPS / 笔 uevent 节点
- [ ] 若无内核节点：确认退化路径（纯 GATT 事件源，无磁吸充电管理）

## P1 — 最小闭环（连接 + 电量）
- [ ] 构建 Hook APK（`hook/tools/build_hook_source.py`）并按 scope.list 勾选 LSPosed 作用域
- [ ] 构建 Root 模块 zip（`module/tools/build_root.py`）刷入
- [ ] 确认 inkdye 被禁用且基础书写（NVTCapacitivePen HID）不受影响
- [ ] 验证：BLE 连接 / 电量 / 设置页断开（CONNECT_PENCIL / DISCONNECT_PENCIL）

## P2 — 磁吸链路（最大不确定项）
- [ ] 找到磁吸检测替代源（Hall 节点 / 蓝牙 RSSI / 其他），重写 `PEN1_HALL/PEN2_HALL` 及 `CPS_*` 路径
- [ ] CPS 充电上电（gpiochip0 线号需按 TB522FU dts 重新确认，**勿直接沿用 10/108**）
- [ ] 验证：吸附弹窗 / 磁吸充电 / 吸附时 NVT 触控板禁用

## P3 — 打磨
- [ ] Pen Pro 2 GATT 特性表比对（btmon 抓包 vs `OemGattProtocolHooks` UUID 清单）
- [ ] 书写振动（OplusTouchNodeManager node38 在本机的可用性）
- [ ] 刷新率投票（笔落屏 120Hz），沿用 fix-module 的显示基线做法，勿动 refresh_rate_config
- [ ] 回归矩阵：重启 / 蓝牙重启 / 反复吸附断开 / 首笔延迟

## 工程项
- [ ] Hook 包名是否改名（当前沿用 com.aclaniakea.lenovopenbridge，改包名需同步 build 脚本/签名/scope）
- [ ] 本机 LSPosed 版本与 lspd 路径同步机制验证（post-fs-data 的 lsposed-path-sync）
- [ ] 无线 adb 不稳，调试期改 USB

## P2.5 充电守护（新增，2026-09-18 实现）
- [x] `module/charge-guard.sh` v2：磁吸观察 + 充满通知 + 兜底断电 + IPeManager 状态修正（已实机起进程验证启动）
- [x] `service.sh` 挂载、`uninstall.sh` 恢复 TX=1
- [x] 充电全周期采样（charge_log.csv 33 样本）：充满判据=online 1→0 边沿 + 涓流保持期分析；ipe_chg 恒 0 实锤 → 判据禁用 ipe_chg，见 docs/p0-recon-20260918.md 充电全周期采样分析
- [ ] 实机验证充满通知（吸附 + 电量 100）闭环
- [ ] 补受控实验：笔到 100% 后保持不动，确认 online 是否自行归零（区分"充满自动断电"vs"取笔"）
- [ ] 实机验证恢复充电通知（电量回落到 <=95）
- [ ] 验证 ipe_pencil_charging_state 在低于 100% 吸附时被修正为 1
