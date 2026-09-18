# TODO

> **路线决策（2026-09-18）：走运行时 hook 路线。** 静态 patch / 重打包 ROM 方案暂缓，
> 待 hook 逻辑实机迭代稳定后再考虑固化进底包。
> **框架是 Vector（JingMatrix，`zygisk_vector`），不是 LSPosed。** 安装与验证步骤见
> [`docs/install-vector-route.md`](docs/install-vector-route.md)。

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
- [x] 补受控实验：笔到 100% 后保持不动，确认 online 是否自行归零 —— **实测 online 恒为 1，不随充满/断电变化**，因此它**不能**作为「正在充电」判据；唯一判据是 `tx_status` 的 `cps_wls_en`（2026-09-18）
  - 注意 `cps_wls_tx` 的 `charge_type=Trickle` 也只是驱动默认档位名，同样不代表在充电
- [x] 实机复核「充满后软件侧是否主动断电」（2026-09-18，电量 100 / 笔在磁吸上）：**是，且两层都有动作**
  - 驱动层：充满后**不总是**自己关 —— charge-guard.log 抓到两次「driver left TX on at full (100); forced off」（14:56:59 首次吸附、14:57:29 重新吸附各一次）
  - 守护层：30s 轮询兜底写 0，两次都成功；当前连续采样 15s 稳定 `cps_wls_en:0`
  - 通知层：14:56:59 触发「手写笔已充满，已停止充电」（ColorOS 丢 shell 通知，UI 走 Hook 广播）
  - ⚠️ 已知窗口：驱动可能为补电重新打开 TX（14:57:29 即如此），守护最多 30s（`POLL_SEC`）后才再关；要收紧就调小 `POLL_SEC`，代价是轮询更频繁
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
- [x] 安装/验证流程文档化：`docs/install-vector-route.md`；产物推送脚本 `scripts/push_to_device.sh`（含 md5 校验）
- [x] 刷入模块 zip + 重启（`ksud module install`；ColorOS 拦截 `adb install`，Hook APK 走 root `pm install`）
- [x] Vector 作用域配置（`vector-cli scope set` 8 项，全 user 0）——**框架是 Vector，不是 LSPosed**
- [x] 验证 hook 加载（`vector-cli log cat` 显示 uid 1000/system_server 也加载了本模块）
- [x] 验证 charge-guard 随开机启动、bootfail 计数正常归零
- [ ] hook 生效确认后，手动 `action.sh disable` 关 inkdye（勿提前禁用，避免触觉空窗）
- [ ] 接入真实手写笔做功能验收（吸附弹窗 / 按键 / 触觉 / 充满闭环）

## P1.4 卡死根因修复（2026-09-18 实机定位）
- [x] 现象：hook + 模块同开 → 卡开机动画，system_server 停在 PMS 扫描，`boot_completed` 永不为真
- [x] 根因：`SystemStylusHooks` 中 `SystemServer#startOtherServices`/`#run` 的 after-hook **无 try/catch**，`init()` 异常逃逸进 system_server 启动序列
- [x] 修复：回调全部包 `catch (Throwable)`；`PhoneWindowManager` 回调同样加保护
- [x] 二分验证：仅 KSU 模块 → 正常；修复后 hook + 模块同开 → 正常（连续 2 次重启验证，<1min 到 boot_completed）
- [x] 其余踩坑入档：`charge-guard.sh` 权限位漏配、密钥库口令丢失（重建 + `keys/tb522fu.pass`）、`lspd` 库归属 Vector

## P1.5 启动失败自保（2026-09-18 实现）
- [x] `post-fs-data.sh`：`app_process` 调用加 `timeout 20`（无 timeout 时回退后台+轮询），消除唯一会阻塞开机的无界调用
- [x] `customize.sh`：同类调用加超时，避免安装器被挂住；修正过时的 ui_print（不再声称"首次开机禁用 inkdye"）
- [x] boot guard：`post-fs-data.sh` 记账 → `service.sh` 确认 `boot_completed=1` 才清零 → 连续 >3 次失败自动生成 `disable`（计数文件 `/data/adb/tb522fu_pen_bridge.bootfail`）
- [x] `module/panic.sh`：一键恢复（enable inkdye / 杀进程 / tx=1 / 清标记 / 默认停用模块），支持 `--keep`
- [x] `uninstall.sh`：清理 boot-guard 计数与 `disable`，避免重装后立刻熔断
- [x] `docs/install-vector-route.md` 增「启动失败与救援」分层说明（模块自保 + KSU 安全模式 + ksud + Recovery）
- [ ] 实机验证：正常开机计数归零（`cat /data/adb/tb522fu_pen_bridge.bootfail` 应为空/0）— ✅ 2026-09-18 已验证为空
- [ ] 实机验证：人为制造 4 次失败开机 → 确认自动熔断 + inkdye 自动恢复

## P2.6 磁吸胶囊延迟修复（2026-09-18 实机定位 + 验证）
- [x] 现象：磁吸吸附后胶囊要等很久才弹（实测 10~21 秒），有时干脆不弹
- [x] 证据：`pen-bridge.log` 四次吸附全部一致 —— dock 边沿 `trusted=false`（`lenovo_pen_hardware_battery_valid=0`），随后成串 `magnetic capsule delayed: fresh battery sample unavailable`，第 14:45 那次等满 40 次才成功，另两次分别在 20/29 次后 **abandoned**
- [x] 三处成因：
  1. `monitor_hall_capsule` 在吸附边沿**主动**把 `lenovo_pen_hardware_battery_valid` 清 0（避免用上一轮缓存）
  2. `request_pen_capsule()` 硬卡 `valid==1` 才广播
  3. 本机内核侧**没有任何**新鲜电量源：`CPS_UEVENT` 写死为 pineapple 的 `i2c-2/2-0041`（本机不存在），`lenovo_penraw/uevent` 只有 `MAJOR/MINOR/DEVNAME` 无 `LEVEL` → 只能等厂家 BLE 栈首帧 GATT 样本
- [x] 修复 A（核心）：`request_pen_capsule()` 改用 `read_hardware_battery()`——新鲜优先、退化到最后一次真实采样；`valid!=1` 时只记一条日志，不再卡门
- [x] 修复 B：`resolve_cps_nodes()` 按 I2C 地址 `*-0041` 运行时解析 CPS 节点（`/sys/bus/i2c/devices/`），取代写死路径；新增 `read_cps_charging()` 从 `tx_status.cps_wls_en` 取充电真值（语义与 charge-guard.sh 一致）
- [x] 修复 C：`request_pen_capsule_when_ready` 加 worker 标记文件去重 —— 此前吸附边沿 hall 抖动会并存 2~3 个重试循环，日志出现成对重复行
- [x] 回归验证（真实笔，重启后）：4 次吸附延迟分别为 **1s / 0s / 0s / 1s**；其中 1s 那次正是 `trusted=false`（原 21 秒路径）；`delayed` 与 `abandoned` 零出现；成对重复行消失
- [x] 附带收益：`charging` 不再恒 0，改由 `cps_wls_en` 驱动（吸附时 `charging=1`）；`CPS uevent requested` 首次出现，证明路径解析生效
- [ ] 观察项：笔接近满电时驱动会脉冲 `cps_wls_en`（1↔0），`charging` 因此可能轻微抖动（`monitor_charging_cache` 已按变化才发布，实际约每 10s 最多一次）
