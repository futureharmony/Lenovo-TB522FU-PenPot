# TODO

> **路线决策（2026-09-18）：走运行时 hook 路线。** 静态 patch / 重打包 ROM 方案暂缓，
> 待 hook 逻辑实机迭代稳定后再考虑固化进底包。
> **框架是 Vector（JingMatrix，`zygisk_vector`），不是 LSPosed。** 安装与验证步骤见
> [`docs/install-vector-route.md`](docs/install-vector-route.md)。

## P0 — 侦察（决定项目成败，先做）
- [ ] 离线：`strings kernel_abs.elf | grep -iE 'lenovo_penraw|PEN_FRAMEWORK|pen_hall|cps'` — 确认内核是否导出笔事件接口
- [ ] 设备在线：跑 `scripts/recon_sysfs.sh`，找 TB522FU 的 Hall / CPS / 笔 uevent 节点
- [ ] 若无内核节点：确认退化路径（纯 GATT 事件源，无磁吸充电管理）

## P0.5 严重已知问题（阻塞性风险，2026-09-18 实机命中一次）
- [~] **Hook 注入 system_server 会「偶发卡开机」** —— 同配置多数开机正常，偶发卡死在开机动画且**不自恢复**（Watchdog 也救不了）。
  - 根因（`debuggerd -b` 实证）：Vector 安装 hook 需 `ThreadList::SuspendAll`（持独占 mutator 锁），
    而 system_server 主线程此时正卡在 `BatteryService.onStart → IHealth.update()` 的 **binder JNI 调用**里，
    JNI 退出处要重新获取 mutator 锁 → 互等死锁。Vector 日志停在 `Loading class …UiWorkingSetPrefetch`，无后续完成行。
  - 恢复：重启即可（本次重启 35s 起来）。`debuggerd -b <pid>` 对全线程 suspend/resume **有可能短暂打破该死的锁**（实测疑似生效）。
  - 方案②（Vector 免 deopt / 懒加载配置）：**已关闭，无解**。CLI `config get` 全部候选键被拒；`modules_config.db` 的 `configs` 表无任何 hook 时序项；
    上游 `--late-inject`（#564）是 NeoZygisk 专用且**仍会 deopt**，不能规避死锁。
  - 方案①（延后/异步安装）：**已落地并验证**（v4.1.4，2026-09-18）。
    - 实现：`UiWorkingSetPrefetch case "android"` → `SystemStylusHooks.installAsync()`，在 `LenovoPenInstall` 守护线程里
      `sleep(2500ms)` 后才真正 `install()`。延迟可运行时调：`setprop persist.lenovo.penbridge.install_delay_ms <ms>`（0 关闭，上限 30000）。
      依据：注入后 system_server 最忙的 `startCoreServices`（PackageManager/BatteryService/SensorService）在前几秒，
      而真正要 hook 的 `SystemServer#startOtherServices` / `#run` 约 18s 后才进；2.5s 延迟把 deopt 挪出最密窗口。
    - 验证（连续 6 次重启，无一次卡开机）：每次均见
      `handleLoadPackage pkg=android` → `install deferred by 2500ms` → `stylus hooks installed (startOtherServices=1 run=1)` → `deferred install done`，
      随后完整钩子链（`PEN_FRAMEWORK uevent bridge started` / `NVT transition suppressor registered` / `touchscreen haptics initialized` / `pen state synced`）。
    - ⚠️ 这是**概率降低而非根治**：底层 ART/framework 竞态无法从模块侧消除。**统计样本仍偏小**，后续回归矩阵需继续多刷几次。
  - [ ] 方案③（作用域回退 app-only 的降级开关）：仍作为最后保底，暂未实现。
  - ⚠️ **可观测性前提**：框架侧 `XposedBridge.log` → Vector module log **会丢弃注入早期的消息**（实测注入时刻的日志一条都不落盘，
    第一条存活日志要 ~18s 后才出现）。因此 `HookUtils.log` 已同时镜像到 `android.util.Log`，用
    `adb logcat -s LenovoPenBridge` 读取；读启动早期日志前建议先放大缓冲：`setprop persist.logd.size 64M`（开机即生效）。
  - 相关文档：`docs/install-vector-route.md` 踩坑表；技能 `android-ksu-vector-module-triage` 的 Bootloop triage。

## 已知缺口（2026-09-18 观测）
- [x] **笔身触控条手势打通（v4.1.5）**：设备名串少了 ` 2` + 框架路径错按 `keyCode 131/132/133` 分派（该设备只上报 `KEY_UNKNOWN`(240)），
      两处已修：名称放宽为 `lenovo tab pen` + `consumer control`，分派改按 `getScanCode()`。实测扫描码
      `787987`=上滑 / `787986`=下滑 / `787969`=双击（TB522FU 实测，见 `docs/install-vector-route.md` 踩坑 #11）。
      ⚠️ 待实测确认：三种手势能否分别落到注入键 768/767/769 且下游有响应（需要人各做一次手势看日志）。
- [ ] `LenovoConsumerGestureReader` 依赖 native 库 `libpeninput.so`（`System.load(nativeLibraryDir+"/libpeninput.so")` 提供 `nativeGrab`=EVIOCGRAB），
      移植版 Hook APK **未打包**该 .so（v4.1.3 / v4.1.4 / v4.1.5 均无 `lib/`），运行期报
      `native pen input load failed: UnsatisfiedLinkError … libpeninput.so not found` →「consumer gesture reader」这条路（Lenovo Tab Pen Pro
      触控条原始事件直读）当前不可用。**但触控条已由框架路径（`PhoneWindowManager` 拦截 + `getScanCode` 分派）覆盖，功能不受影响**；
      且此路 `EVIOCGRAB` 会独占设备，与框架路径**只能二选一**。名称串已同步修好，仅作将来备选。
      若要启用：从原始 TB710FU 移植源取预编译 .so 放进 `hook/source/resources/lib/arm64-v8a/`，或自写 ~20 行 JNI（一个 `ioctl` 封装）。

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
- [x] 补受控实验：确认 `online` 语义 —— **随"笔是否在线圈上"变化**（吸附=1，离开=0，见 `docs/charge_log_20260918.csv` 11:34 边沿），但 **不反映是否在送电**（`cps_wls_en:0` 时仍为 1）。因此它**不能**作为「正在充电」判据；唯一判据是 `tx_status` 的 `cps_wls_en`（2026-09-18）
  - `capacity` 同理：吸附=笔的 SOC，离开=0 —— 是驱动通过带内通信解析出的笔电量，可用作交叉校验
  - `charge_type`：吸附=Trickle、离开=Unknown，是驱动按吸附状态给的档位名，同样不代表"正在充电"
- [x] 实机复核「充满后软件侧是否主动断电」（2026-09-18，电量 100 / 笔在磁吸上）：**有，但当前是我们守护干的，不是驱动**
  - charge-guard.log 两次「driver left TX on at full (100); forced off」分别在吸附后 **t+3s / t+6s**，即我们的守护比驱动先动手；**因此"驱动自己会不会关"至今没有被观察到**（被我们抢先了）
  - 通知层：14:56:59 触发「手写笔已充满，已停止充电」（ColorOS 丢 shell 通知，UI 走 Hook 广播）
  - ⚠️ 守护抢先动手的风险：守护用 **BLE 侧电量**（可能滞后）当判据，驱动用 **带内 `charging_soc`**（直读）。若两者不一致（BLE 仍读 100、笔实际需要补电），守护会**挡住合法补电** → 表现为"笔吸上去不充电"
- [x] **反向工程 CPS 驱动本体**（`/vendor_dlkm/lib/modules/cps_wls_charger.ko`，2026-09-18）——确认充满截止/补充充电是**原厂驱动自带**：读 `hall_status`、带内 ASK/FSK 解析 `pen_type/mac/charging_soc/charging_status/charging_cmd`、`charging_cmd → close tx`、`error int flag → close tx`、`cps_handle_rechg_work`（补充充电）、设备树无任何策略参数。详见 `docs/cps-charger-driver-analysis.md`
- [ ] **待补的决定性实验**：临时 `touch disable-charge-guard` 后让笔重新吸附，确认**驱动是否会在笔保持吸附时自行把 `cps_wls_en` 关掉**以及耗时。若会 → 守护的强制写 0 应当移除（只保留通知 + `ipe_pencil_charging_state` 回写）；若不会 → 守护必须保留并说明原厂逻辑缺了什么
- [x] 实机验证充满通知闭环：守护触发 ✅、兜底断电 ✅；确认 ColorOS 丢弃 shell 通知 → UI 展示移入 P1 Hook APK（SHOW_PENCIL_CAPSULE 广播链路已预留）
- [ ] 实机验证恢复充电通知（电量回落到 <=95）
- [ ] 实机验证 ipe_pencil_charging_state 在低于 100% 吸附时被修正为 1
- [ ] P1 Hook APK 中实现 SHOW_PENCIL_CAPSULE 接收端（弹胶囊/发通知）
- [x] inkdye **默认禁用**（2026-09-18 用户拍板）：`service.sh` 开机 `disable-user` 内置笔桥 `com.inkdye.lenovopentocoloros`，由本模块接管；用户可用 `action.sh enable` 覆盖（写入 `inkdye-enabled.state`）。卸载/panic/boot-guard 会无条件恢复
- [x] **inkdye 落地时机修正**（2026-09-18）：原实现放在 `exec` 之前、service.sh 很早期执行，此时 **PackageManager 尚未就绪**，`pm disable-user` **静默失败**（日志说禁了、`dumpsys` 仍 `enabled=0`）。
  已改为 `exec` 之后的后台函数 `apply_inkdye_state()`：**最多 40×3s 重试 + `pm list packages -d --user 0` 复核状态**，确认后打印。实机验证 `enabled=3`（DISABLED_USER）✅
- [x] **charge-guard 单实例守卫修正**（2026-09-18）：pidfile 在 `/data` 跨重启保留，旧实现只 `kill -0 <old>` → 撞上被复用的 PID 时守护**静默 exit 0**（守护整轮缺席）。
  已改为 boot_id + `/proc/<pid>/cmdline` 双校验，pidfile 记 `pid bootid`；`service.sh` 补启动日志 + 后台 liveness 复核（失败补启一次）。实机验证单实例、日志正常 ✅

## P1 构建（2026-09-18 完成）
- [x] Hook APK：`releases/PenBridge-Hook-tb522fu-v4.1.3.apk`（DeviceGate=SM8750P/sun，213KB，Xposed API 已正确从 dex 剔除）
- [x] PenHidCtl：`releases/PenHidCtl-tb522fu-1.1.0.apk`（17KB）
- [x] 模块包：`releases/tb522fu-pen-bridge-v0.1.0.zip`（含 charge-guard.sh、service.sh、PenHidCtl priv-app、lsposed-path-sync、hook 副本）
- [x] 构建脚本本地化：build_hook_source/build_penhid（alias/out env 化、libpeninput.so 可选）、build_root（repo 布局适配、去 CPS GPIO、加 charge-guard）
- [x] 本机工具链：/tmp/android-sdk（build-tools android-15 + platform-35），自签 keys/tb522fu.jks（不入库）
- [x] 安装/验证流程文档化：`docs/install-vector-route.md`；产物推送脚本 `scripts/push_to_device.sh`（含 md5 校验）
- [x] 刷入模块 zip + 重启（`ksud module install`；ColorOS 拦截 `adb install`，Hook APK 走 root `pm install`）
- [x] Vector 作用域配置（`vector-cli scope set`，**1 + 7 项**全 user 0）——**框架是 Vector，不是 LSPosed**。⚠️ system_server 用伪包名 `system`（曾误写 `android`，见 P1.5）
- [x] 验证 hook 加载（`vector-cli log cat` 显示 uid 1000/`system` 也加载了本模块）
- [x] 验证 charge-guard 随开机启动、bootfail 计数正常归零
- [ ] 确认 inkdye 被默认禁用后基础书写（NVTCapacitivePen HID）与触觉反馈不受影响（Hook 未生效期间触觉可能空窗，必要时 `action.sh enable` 临时回退）
- [ ] 接入真实手写笔做功能验收（吸附弹窗 / 按键 / 触觉 / 充满闭环）

## P1.5 hook 不进 system_server 修复（2026-09-18 实机定位）
- [x] 现象：应用进程有 hook 日志（`WirelessSettings hooks installed` 等），但触觉/笔键/磁吸/输入门控全无效；`grep 'stylus hooks installed'` 恒为 0
- [x] 定位手段：KernelSU logcat 存档 `/data/adb/ksu/log/logcat.log`（跨开机保留，普通 `logcat -d` 已轮转）+ Vector `verbose_*.log` 的 `VectorConfigCache`/`VectorLegacyBridge` 行 + 对 `handleLoadPackage` 插桩
- [x] 根因：模块作用域写成 `android`。Vector 作用域**匹配**用伪包名 `system`，但框架**回调**仍以 `packageName="android"` 回调（故代码 `case "android"` 正确）
- [x] 修复：`scope.list` + `arrays.xml` 的 `android` → `system`；运行时 `vector-cli scope set ... system/0`；移除多余 `android/0`
- [x] 验证：重启后 `system_server stylus hooks installed` 出现，随后 uevent 桥/触觉/输入门控/状态同步全部生效；boot_completed 25s
- [x] 增强：`handleLoadPackage` 早退路径补 `skip <pkg> (gate=…)` 诊断日志（此前静默，是误判"框架没工作"的主因）

## P1.4 卡死根因修复（2026-09-18 实机定位）
- [x] 现象：hook + 模块同开 → 卡开机动画，system_server 停在 PMS 扫描，`boot_completed` 永不为真
- [x] 根因：`SystemStylusHooks` 中 `SystemServer#startOtherServices`/`#run` 的 after-hook **无 try/catch**，`init()` 异常逃逸进 system_server 启动序列
- [x] 修复：回调全部包 `catch (Throwable)`；`PhoneWindowManager` 回调同样加保护
- [x] 二分验证：仅 KSU 模块 → 正常。⚠️ 注意：当时的"hook + 模块同开 → 正常"结论**无效**——彼时 hook 因作用域写成 `android` 压根没进 system_server（见 P1.5）。修复作用域后重新验证：hook 真正在 system_server 运行 + 模块同开 → `boot_completed` 25s，正常
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
