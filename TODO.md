# TODO

> **路线决策（2026-09-18）：先走 LSPosed 路线。** 静态 patch / 重打包 ROM 方案暂缓，
> 待 hook 逻辑实机迭代稳定后再考虑固化进底包。安装与验证步骤见
> [`docs/install-lsposed-route.md`](docs/install-lsposed-route.md)。

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
- [x] 实机验证充满通知闭环：守护触发 ✅、兜底断电 ✅；确认 ColorOS 丢弃 shell 通知 → UI 展示移入 P1 Hook APK（SHOW_PENCIL_CAPSULE 广播链路已预留）
- [ ] 实机验证恢复充电通知（电量回落到 <=95）
- [ ] 实机验证 ipe_pencil_charging_state 在低于 100% 吸附时被修正为 1
- [ ] P1 Hook APK 中实现 SHOW_PENCIL_CAPSULE 接收端（弹胶囊/发通知）
- [x] inkdye 禁用**改为手动**：`service.sh` 开机不再自动禁用，仅尊重 `inkdye-disabled.state` 选择；切换走 `action.sh disable|enable|toggle`（消除 hook 未生效时的触觉空窗）

## P1 构建（2026-09-18 完成）
- [x] Hook APK：`releases/PenBridge-Hook-tb522fu-v4.1.3.apk`（DeviceGate=SM8750P/sun，213KB，Xposed API 已正确从 dex 剔除）
- [x] PenHidCtl：`releases/PenHidCtl-tb522fu-1.1.0.apk`（17KB）
- [x] 模块包：`releases/tb522fu-pen-bridge-v0.1.0.zip`（含 charge-guard.sh、service.sh、PenHidCtl priv-app、lsposed-path-sync、hook 副本）
- [x] 构建脚本本地化：build_hook_source/build_penhid（alias/out env 化、libpeninput.so 可选）、build_root（repo 布局适配、去 CPS GPIO、加 charge-guard）
- [x] 本机工具链：/tmp/android-sdk（build-tools android-15 + platform-35），自签 keys/tb522fu.jks（不入库）
- [x] 安装/验证流程文档化：`docs/install-lsposed-route.md`；产物推送脚本 `scripts/push_to_device.sh`（含 md5 校验）
- [ ] 刷入模块 zip + 重启（`scripts/push_to_device.sh` → KSU 刷 `tb522fu-pen-bridge-v0.1.0.zip`）
- [ ] LSPosed 勾选作用域（android/ipemanager/mydevices/note/exsystemservice/healthservice/wirelesssettings/screenshot）→ 二次重启
- [ ] 验证 hook 加载（logcat LSPosed + charge-guard 与 hook 联动）
- [ ] hook 生效确认后，手动 `action.sh disable` 关 inkdye（勿提前禁用，避免触觉空窗）

## P1.5 启动失败自保（2026-09-18 实现）
- [x] `post-fs-data.sh`：`app_process` 调用加 `timeout 20`（无 timeout 时回退后台+轮询），消除唯一会阻塞开机的无界调用
- [x] `customize.sh`：同类调用加超时，避免安装器被挂住；修正过时的 ui_print（不再声称"首次开机禁用 inkdye"）
- [x] boot guard：`post-fs-data.sh` 记账 → `service.sh` 确认 `boot_completed=1` 才清零 → 连续 >3 次失败自动生成 `disable`（计数文件 `/data/adb/tb522fu_pen_bridge.bootfail`）
- [x] `module/panic.sh`：一键恢复（enable inkdye / 杀进程 / tx=1 / 清标记 / 默认停用模块），支持 `--keep`
- [x] `uninstall.sh`：清理 boot-guard 计数与 `disable`，避免重装后立刻熔断
- [x] `docs/install-lsposed-route.md` 增「启动失败与救援」分层说明（模块自保 + KSU 安全模式 + ksud + Recovery）
- [ ] 实机验证：正常开机计数归零（`cat /data/adb/tb522fu_pen_bridge.bootfail` 应为空/0）
- [ ] 实机验证：人为制造 4 次失败开机 → 确认自动熔断 + inkdye 自动恢复
