# tb522fu-pen-port — 联想手写笔 TB522FU 彻底适配

把 coloros-pad-fixes 的 `lenovo_pen_bridge`（ACLaniakea，v4.1.3，面向 TB710FU / SM8650Q / pineapple）移植到 **Lenovo TB522FU**（`sun` / SM8750P / 骁龙 8 Elite，运行移植版 ColorOS）。

## 结构

```
module/       KernelSU/Magisk Root 模块（含 charge-guard 充电守护）
hook/         Xposed Hook 源码（com.aclaniakea.lenovopenbridge）
penhidctl/    priv-app HID 控制器源码
scripts/      设备侦察/推送辅助脚本
docs/         分析与验证记录
fix-module/   显示基线（refresh_rate_config）等的独立 fix 模块
releases/     构建产物（模块 zip / Hook APK / PenHidCtl APK）
```

## 路线与框架事实

**走运行时 hook 路线**（静态 patch / 重打包 ROM 暂缓，待逻辑稳定后固化进底包）。

框架不是 LSPosed，而是 **Vector**（JingMatrix，`zygisk_vector` v2.2，Xposed 兼容，
Android 8.1~17），守护进程 `vectord`，管理器 `app.morphe.manager`，CLI 位于
`/data/adb/modules/zygisk_vector/cli`。LSPosed 未安装也不需要。

安装与验证步骤见 **[`docs/install-vector-route.md`](docs/install-vector-route.md)**（含
vector-cli 用法、踩坑记录、救援分层）。

## 安装/回退

- **安装**：`scripts/push_to_device.sh` 推送产物 → 装 Hook APK（root `pm install`）
  → `vector-cli modules enable` + `scope set`（**1 + 7 项**；system_server 必须用伪包名
  `system`，写 `android` 会让 hook 永不进 system_server，详见
  [`docs/install-vector-route.md`](docs/install-vector-route.md) 第 3 节）
  → `ksud module install` → 重启。
- **inkdye 默认禁用**：模块开机把系统内置笔桥 `com.inkdye.lenovopentocoloros`
  （实际装在 `/system/priv-app/LenovoPenBridge/`）`disable-user`，笔能力由本模块
  （Root 服务 + Hook）接管。想回退到系统内置笔桥：在 KSU/Magisk 管理器「执行」
  按钮（`action.sh`）里开启。
  - 切换：`su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh enable|disable|toggle'`（默认 disable）
  - 用户选择记录于 `inkdye-enabled.state`（存在 = 显式**启用**内置笔桥，覆盖默认）。
- **回退**：卸载模块 → `uninstall.sh` 自动 `pm enable` 恢复 inkdye、杀守护、恢复 TX=1。
  临时仅停守护：`touch $MODDIR/disable-charge-guard`。

> ⚠️ 启动失败自保（boot guard）**只管 KSU 模块**。Vector hook 若导致卡死，
> 须用 `vector-cli modules disable com.aclaniakea.lenovopenbridge` 或安全模式。
> **排查卡死时两者必须分开停用**（实测：hook + 模块同开曾卡死，仅模块正常）。

## 启动失败自保

模块不再是"刷了就听天由命"。三层保护：

1. **超时封顶**：`post-fs-data.sh`（唯一阻塞开机的脚本）里所有 `app_process` 调用带
   `timeout 20`，卡住也不会拖死开机。
2. **失败熔断**：连续 >3 次未到达 `sys.boot_completed=1` → 自动生成 `disable`，
   下次开机跳过本模块，并自动交还 inkdye / 恢复充电。最坏代价是丢 3 次开机。
3. **一键 panic**：`su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh'`
   立刻恢复运行时状态并停用模块（`--keep` 保留模块）。

外部救援（KernelSU 自带）：开机连按**音量−三次**进安全模式；或 `ksud module disable
tb522fu_pen_bridge`；或 Recovery 里删模块目录。详见
[`docs/install-vector-route.md`](docs/install-vector-route.md) 第 6.5 节。

> 本模块**不写 display/DSI/panel 节点、不注入 initrc、不装自定义 `.ko`、不改
> boot/vbmeta**，所以安全模式始终有效，出问题不需要重刷 boot。

## 已完成的移植改动

1. `hook/.../DeviceGate.java`：SM8650Q/PINEAPPLE → SM8750P/SUN
2. `module/service.sh`：Root 侧设备门同步改；Hall 节点改为单节点 `och1909/hall3`（实测：吸附=0，离开=1）；CPS GPIO keeper 停用（充电由驱动自身经 `tx_status` 管理）
3. `module/module.prop`、`customize.sh`：模块 ID 改为 `tb522fu_pen_bridge`，避免与原模块冲突
4. inkdye 默认禁用（`service.sh` 开机 `disable-user`），可用 `action.sh enable` 开启（写入 `inkdye-enabled.state`）
5. **`SystemStylusHooks`：给 system_server 回调加 `catch (Throwable)`** —— 原代码在
   `SystemServer#startOtherServices` after-hook 里裸调 `init()`，异常逃逸即中断
   system_server 启动（表现为卡开机动画、`boot_completed` 永不为真）。这是本项目
   最关键的稳定性修复。
6. `post-fs-data.sh`：LSPosed 的 `LsposedPathSync` 默认禁用（`lspd` 配置库属于 Vector，
   schema 不同，写入有损坏风险），留 `enable-lsposed-path-sync` opt-in。
7. `customize.sh`：补 `charge-guard.sh` 的 `set_perm 0755`（漏配导致守护静默不启动），
   `service.sh` 调用点改为 `[ -f ] && sh`（不依赖执行位）。
8. **修复 hook 不进 system_server（2026-09-18）**：模块作用域原写 `android`，但 Vector
   的作用域**匹配**用的是伪包名 `system`（框架**回调**仍以 `packageName="android"` 回调，
   故 `case "android"` 无误）。改为 `system` 后 system_server 恢复加载，触觉/笔键/磁吸/
   输入门控全部生效。`scope.list` + `arrays.xml` 已同步；`handleLoadPackage` 早退路径
   补 `skip <pkg> (gate=…)` 诊断日志，便于日后定位。

## TB522FU 新增功能：充电守护（charge-guard.sh v2）
- **磁吸通知**：沿用 monitor_hall_capsule（已映射到 `och1909/hall3`）。胶囊电量优先取新鲜样本，
  取不到则退化到最后一次真实采样，**不再等待新鲜油表采样**（否则吸附后要空等 10~21 秒）。
- **充电状态真值**：`service.sh` 的 `read_cps_charging()` 与守护同源，都读 `11-0041/tx_status`
  的 `cps_wls_en`；CPS 节点按 I2C 地址 `*-0041` 运行时解析，兼容 pineapple / sun 两块板。
- **充电状态修正**：实测 IPeManager `ipe_pencil_charging_state` 恒 0，由守护按真实 吸附+TX 状态回写。
- **充满通知**：吸附 + 电量>=100 + TX 关闭 → 通知「已充满，已停止充电」。
  ⚠️ ColorOS 丢弃 uid 0（shell）通知 → UI 走 Hook APK 的 `SHOW_PENCIL_CAPSULE` 广播，`cmd notification` 仅作 AOSP 兜底。
- **充满断电**：由 **原厂 cps-wls-charger 驱动自带**（驱动读霍尔 + 与笔的带内通信，
  收到笔的 `charging_cmd` 就 `close tx`，另有 `cps_handle_rechg_work` 做补充充电）；
  守护仅在驱动异常时兜底写 `0`。⚠️ **"驱动是否会自己关"尚未直接观测到**，两次实测
  都是守护在吸附后 t+3s/t+6s 抢先动手 —— 待补决定性实验，详见
  [`docs/cps-charger-driver-analysis.md`](docs/cps-charger-driver-analysis.md)。
  - ⚠️ TX 写入为**瞬时**：`echo 1` 后约 30s 被驱动按自身策略改回；写入只认裸数字 `0`/`1`。
- 开关：`touch $MODDIR/disable-charge-guard` 临时停用；卸载时自动恢复 TX=1。

> **来源结论（2026-09-18，只读侦察）**：内核（`6.6.82TB522FU`，2024-12-01）与整个
> `/vendor_dlkm`（308 个模块，含 `dhall_och1909`/`lenovo_sys_temp`/`lenovo_thermal_control`）
> 都是**原厂 Lenovo 构建**；ColorOS 只提供 system/product/odm 与 4 个 `oplus_network_*`
> 模块，**没有介入 CPS8601**。充电策略硬编码在驱动内部，设备树无任何策略参数。
> 详见 [`docs/cps-charger-driver-analysis.md`](docs/cps-charger-driver-analysis.md)。

## 当前状态（2026-09-18 实测）

已实机部署并验证：**boot_completed=1、Vector 注入 system_server 成功（`system` 作用域
修复后）、charge-guard 随开机启动、PenHidCtl 以 priv-app 装载、bootfail 计数正常归零**。

Hook 已确认在 system_server 内工作（开机日志）：`system_server stylus hooks installed`
→ `PEN_FRAMEWORK uevent bridge started` → `NVT transition suppressor registered` →
`touchscreen haptics initialized` → `real HID connected: pen input gate restored` →
`ColorOS pen state synced: connected=true battery=100 charging=1`。

> 遗留（非阻塞）：① Hook 读取的联想 hall 节点在 TB522FU 不存在（`Lenovo pen hall nodes are
> not readable`），吸附判定改由 CPS/uevent 路径承担；② `libpeninput.so` 未打进 APK
> （构建脚本标注为可选），`native pen input load failed` 后模块优雅降级，`global stylus
> input monitor` 仍正常注册。

硬件节点侦察已完成：TB710FU 的 `pen1_hall/pen2_hall`、CPS I2C/GPIO 在 TB522FU 无对应物，
已改用 `och1909/hall3`（磁吸，注意节点内容前缀是驱动 bug 的 `hall13`）+
CPS8601 `11-0041/tx_status`（充电）。详见 `docs/p0-recon-20260918.md`。

磁吸胶囊延迟已修复并回归（2026-09-18）：原为**吸附后 10~21 秒**才弹、偶发不弹，
根因是胶囊广播硬卡 `lenovo_pen_hardware_battery_valid==1`，而该标志在吸附边沿被主动清 0，
本机又无内核侧新鲜电量源（`CPS_UEVENT` 写死为 pineapple 的 `i2c-2/2-0041`，`penraw/uevent`
无 `LEVEL`），只能空等厂家 BLE 首帧样本。修复后 4 次吸附延迟为 **1s / 0s / 0s / 1s**。
详见 `TODO.md` → P2.6。

待办见 `TODO.md`，下一步是接入真实手写笔做功能验收（吸附弹窗/按键/触觉/充满闭环）。

上游与背景分析：
- 上游仓库：`../coloros-pad-fixes`（仅读，不改动）
- 方案对比：`../pen-bridge-comparison.md`
- 难度评估：`../pen-bridge-porting-difficulty.md`
