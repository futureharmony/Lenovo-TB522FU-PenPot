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
- [x] **笔身触控条手势打通（v4.1.8，实机验证）**：v4.1.5 的两处判断**都**是错的 ——
      ①**设备名**：真正把键事件送进 `PhoneWindowManager` 的节点是 **`Lenovo Tab Pen Pro 2 Mouse`**（`event8`，uhid `0005:17EF:622E.0001`）；
      兄弟节点 `…Consumer Control`（`event9`）只上报 `EV_MSC/MSC_SCAN + EV_KEY KEY_UNKNOWN` 且**从不进按键队列**，不能拿来匹配。
      `dumpsys input` 对 Mouse 节点打印空 `KeyLayoutFile`，但它的 `code=131/132/133` 恰恰来自
      `Vendor_17ef_Product_622e.kl` 的 `key usage` 行（`key usage <hid-usage> <key>` 语法 Android 确实支持，`Generic.kl` 里用了 25 处）。
      ②**分派字段**：`getScanCode()` 恒为 **240**（=KEY_UNKNOWN，即内核原始 keycode，不是 HID usage），手势信息在 **`getKeyCode()`**。
      v4.1.5 的 `switch (getScanCode())` 因此永远走 default，且 `contains("consumer control")` 对 Mouse 节点不成立 → 触控条全死。
      真机实测（用户做 4 个手势）：`code=131/132/133 scan=240`。v4.1.8 的 `stripGestureToNativeKey()` 把它们注入成 OEM「原生笔」键 `767/768/769`。
      ⚠️ **`767/768/769` 在 TB522FU 上是确认的空操作（no-op）**：注入事件确实进了管线（日志可见 `dev=Virtual code=768 act=0/1`），
      但整条日志里**没有任何组件消费它们**，用户实测「完全没反应」。→ v4.1.10 弃用注入，改走移植版**自身**的 ColorOS 手势管线。
      ⚠️ **还有一个更隐蔽的前置坑（已修）**：`vector-cli scope set` 会**整表覆盖** scope，而仓库脚本的 scope 列表漏了 `system/0`，
      于是 module **完全不再注入 system_server**（`handleLoadPackage pkg=android` 消失、`interceptKeyBeforeQueueing hooks` 永不安装），
      触控条与整条笔键路径全死，而**所有 app 侧 hook 仍正常**——极容易误判成代码 bug。判据：`vector-cli log cat` 里没有 system_server
      的 `Loading legacy module` 行。已修 `scripts/deploy_vector.sh`（`system/0` 必须保留；`deploy_vector.sh` 新增注释说明）。

- [x] **动作层打通（v4.1.10，实机验证「有反应」）**：action 层从 767/768/769 换成移植版自己的 `click()`/`longAction()` 后仍无反应，
      根因是 **`click()` 广播发错了包**。反编译 `com.oplus.healthservice` + `dumpsys package` 全量枚举接收器后坐实：
      **只有 `com.oplus.healthservice`**（`com.oplus.globalcollect.collect.receiver.StylusPressReceiver`）声明了
      `PENCIL_SINGLE_CLICK` / `PENCIL_DOUBLE_CLICK` / `STYLUS_BUTTON_STATE_CHANGED`；`com.coloros.note` 与 `com.oplus.screenshot`
      **一个都没声明**——旧目标列表因此把每个单击/双击动作静默丢弃（笔身按键不走此函数，所以一直「正常」）。
      修法：`click()` 目标列表改为 `{com.oplus.healthservice, com.coloros.note, com.oplus.screenshot}`（无接收器=无害 no-op，保留后两者做兼容）。
      另：`StylusPressReceiver` **完全忽略 `action` extra**——单击→`onStylusSingleDoubleClick(true)`、双击→`(false)`。
      OEM 行为（`StylusPressManager.onStylusSingleDoubleClick`）：**屏幕解锁**时 `startRouletteService` 弹**笔功能转盘**（`CallRouletteService`）；
      **锁屏/儿童模式/折叠提示**时 `stopRouletteService`（按设计什么都不做）——首次测试无反应正是因为当时**锁屏**（`isScreenLocked=true`）。
      长按（`longAction`→`button("down")`→`STYLUS_BUTTON_STATE_CHANGED`）→ **收藏/圈选浮窗**（`StylusTouchInterceptWindow`），已手动广播验证能弹窗。
      实机结果：4 个手势现在**都能弹出转盘**（用户确认「所有都是弹出转盘」）。
      另修：`dispatchStripGesture` 的 131 桶改用 `tap()`（320ms 双击判别），因为真机上**双击常被固件拆成两次 131**，
      逐次 `click(false)` 会让 OEM 把转盘**开一下又关一下**（净无反应）；`tap()` 归并成一次 `click(true)`。132（F2/双击）路径也实测可达。
      ⏳ **待决策**：目前「滑动/单击/双击」全部落到同一个转盘，未做区分（框架层天然分不开上滑/下滑）。
      若要四手势各自独立 → 见下一条 `.kl` overlay 方案。
      ✅ **已解决（v4.1.14，2026-09-18 23:19 实机验证）**：`.kl` overlay + `stripGesture()` 六手势 +
      `swipeAction()` 上下滑动作层全部落地。见下一条。
- [x] **手势原始判别位已拿到（供将来细分 6 手势）**：笔的 HID notify `00002a4d-…` 报文字节可区分手势 ——
      `02 00 08 00 00`=上滑、`02 00 04 00 00`=下滑、`02 02 00 00 00`=双击、`02 00 00 02 00`=长按（与日志时间戳一一对应）。
      但 ROM 的 `Vendor_17ef_Product_622e.kl` 把「上滑|下滑|单击」都并成 **F1(131)**、「双击」→F2(132)、「长按|挤捏」→F3(133)，
      框架层只能分 3 组。要 6 手势各自独立：加一个**模块级 `.kl` overlay**（`module/system/usr/keylayout/Vendor_17ef_Product_622e.kl`）
      把 6 个 `0x0c06xx` usage 映成 6 个不同键，再把 `stripGesture()`（v4.1.10 命名，原 `stripGestureToNativeKey()`）扩成 6 项即可
      （KernelSU magic mount 在 post-fs-data 生效，早于 system_server 读 keylayout）。
      ⚠️ 磁盘上现有那份 `.kl` **属于 ROM**（无模块覆盖，md5 `eb89c57180b3309badb03aaf9d2630bf`）；覆盖它前先确认 `dumpsys input` 里
      该节点的 `KeyLayoutFile` 指向新文件。
      ✅ **已落地（v4.1.14，2026-09-18 实机验证）**：
      - `module/system/usr/keylayout/Vendor_17ef_Product_622e.kl`：6 usage → F1..F6（131 单击 / 132 双击 / 133 长按 / 134 挤捏 / 135 上滑 / 136 下滑）；
      - `SystemStylusHooks.stripGesture()` 扩到 6 项；新增 `swipeAction()`：读 `ipe_pencil_slide_up`/`ipe_pencil_slide_down`
        （默认 **上滑=5 随心圈 / 下滑=3 调色盘**；值 5 = 复用 OEM 长按圈选会话 `longAction()`，即
        `STYLUS_BUTTON_STATE_CHANGED down` → `StylusTouchInterceptService` 圈选浮窗，已真机广播验证链路通）；
      - ⚠️ 热替换 `.kl` 的坑：bind mount 的临时文件必须 `chcon u:object_r:system_file:s0`，否则 InputReader
        读 `shell_data_file` 被拒 → 回退 Generic.kl → keyCode=0（抓 `dmesg | grep avc` 定位）；
        且换 `.kl` / 换 APK 后需**重启**（system_server 的 hook 代码是开机注入的，热装 APK 不生效）。
      - 实机（合成事件 + 真笔 GATT 双重验证）：135→`slide-up action`→圈选 armed；136→`slide-down action=3`→色盘广播；132→双击。
- [ ] `LenovoConsumerGestureReader` 依赖 native 库 `libpeninput.so`（`System.load(nativeLibraryDir+"/libpeninput.so")` 提供 `nativeGrab`=EVIOCGRAB），
      移植版 Hook APK **未打包**该 .so（v4.1.3 / v4.1.4 / v4.1.5 均无 `lib/`），运行期报
      `native pen input load failed: UnsatisfiedLinkError … libpeninput.so not found` →「consumer gesture reader」这条路（Lenovo Tab Pen Pro
      触控条原始事件直读）当前不可用。**但触控条已由框架路径（`PhoneWindowManager` 拦截 + `getKeyCode` 分派）覆盖，功能不受影响**；
      且此路 `EVIOCGRAB` 会独占设备，与框架路径**只能二选一**（真机已确认 `native pen input load failed: … libpeninput.so not found`
      只是这一路不可用，不影响框架路径）。名称串在 v4.1.8 里按真机事实改成了 `lenovo tab pen`，此路仅作将来备选。
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

## P0.7 手势功能对齐设备管理配置（v4.1.11，2026-09-18）

**背景**：v4.1.10 为了「先让它有反应」把 `click()` 的广播目标改成永远包含 `com.oplus.healthservice`，
结果 **4 个手势全部弹转盘**（用户实测「所有都是弹出转盘」），**丢掉了设备管理里配置的原有功能**。
用户明确要求恢复：`呼出调色盘` / `橡皮擦切换` 等。

**根因**：`com.oplus.healthservice` 的 `StylusPressReceiver` **完全忽略 `action` extra**，收到
`PENCIL_SINGLE_CLICK`/`PENCIL_DOUBLE_CLICK` 一律 `onStylusSingleDoubleClick()` → 解锁时弹
`CallRouletteService`（转盘）。所以「发到 healthservice」== 「无条件弹转盘」。

**本机实测的配置值**（`settings list global | grep ipe_pencil`）：
| 键 | 值 | 含义 |
|---|---|---|
| `ipe_pencil_single_click` | **3** | 呼出调色盘（show_color） |
| `ipe_pencil_double_click` | **1** | 橡皮擦切换（switch_eraser） |
| `ipe_pencil_long_click` | 0 | 无 |

枚举（反编译 `com.oplus.ipemanager` 的 `IPESettingManager` + `setting/fragment/p.java`）：
`1=橡皮擦切换 2=切回上一支笔 3=呼出调色盘`（**app 内**由 doodle 引擎
`MODE_TOGGLE_ERASER/MODE_TOGGLE_LAST_PEN/MODE_PICK_COLOR` 处理）、`4=打开转盘`（→ healthservice）、`0=无`。

**修复（v4.1.11 / versionCode 410011 / md5 `02ecc6bd093a84a76390b471a8f0692c`）**：
`SystemStylusHooks.click()` 恢复**按配置值条件路由**——
`i == 4` → 只发 `com.oplus.healthservice`（转盘）；
`i ∈ {1,2,3}` → 发 `{com.coloros.note, com.oplus.screenshot}`（app 内切笔模式，正是设备管理配置的功能）。
⚠️ 这条条件是**承重**的，源码里已写长注释，勿再改成「都发 healthservice」。

**待验证**：重启后用户做单击/双击，确认分别是「调色盘」和「橡皮擦」。
（`dumpsys package` 看不到 `com.coloros.note` 的 manifest 接收器 —— 该类接收器是 doodle 引擎
在画布激活时**动态注册**的，所以清单里没有属正常；`NoteToolkitHooks` 正是挂在
`com.oplusos.vfxsdk.doodleengine.toolkit.Toolkit` 的 `receiverSingleClick/receiverDoubleClick` 上。）

## P0.11 手写笔手势设置页注入扩展功能（v4.2.8，2026-09-19 完成并实机闭环）

**需求**：保留原厂 5 项（关闭 / 当前工具与橡皮擦切换 / 最近工具切换 / 显示颜色盘 / 手写笔轮盘），
在原厂列表下追加「扩展功能」分组，可选自定义动作（截图 / 通知栏 / 返回 / 主屏 / 最近任务 / 手电筒），
**全局单选**（原厂行与扩展行互斥），并追加一行「恢复原厂选项」。

**宿主**：`com.oplus.ipemanager` 的 `btadsorb.setting.activity.PencilGestureSettingActivity`
（`click_type` = `single_click` / `double_click` / `long_click_v2`，分别对应下滑 / 双击 / 上滑），
行控件是 `com.coui.appcompat.preference.COUIMarkPreference`。

**落库键（自建桥接键，不污染 OEM 键）**：`ipe_pencil_wb_click_<click_type>`，
取值 `101..106` = 扩展动作，`-1` = 未选扩展（走原厂键）。消费端在 `SystemStylusHooks`。

**踩过的 4 个坑（都是承重教训，勿回退）**：
1. `hookAll` 只匹配 `getDeclaredMethods()`。混淆后的 `PencilGestureSettingActivity` **没有** override
   `onCreate`/`onResume` → 钩子匹配 0 个方法、**静默失效**。必须挂框架基类 `android.app.Activity`
   再在回调里按类名过滤。
2. `androidx.preference.Preference` **没有** `setChecked`（在 `CheckBoxPreference`/`TwoStatePreference`
   层，dex 反编译 `COUIMarkPreference extends CheckBoxPreference` 证实）。在基类解析 → 第一次调用即
   `NoSuchMethodException` → 整段同步退出，表现为「扩展行可多选、原厂行取消不掉」。
   必须 `setRowChecked()` 沿**行自身**类层级解析，并调 `notifyChanged()` 触发重绘（否则只改数据不重画）。
3. `CheckBoxPreference.onClick` 是 **toggle**（`!isChecked()`）且在监听器**之后**执行 → 点已选中的行会
   「键仍选中、UI 却被取消」。必须在监听器里 `postDelayed(120ms)` 再断言同步一次。
4. OEM 行的 **key 与可见标签是错位的**（如 `item_color_picker` 实际对应「当前工具与橡皮擦切换」）。
   「恢复原厂选项」**必须按可见标题匹配**（关闭0/橡皮1/最近2/色盘3/轮盘4/随心圈5），不能按 key 猜。
   另：本机 `Settings.Global.putString(key, null)` 会写成字面量 `"null"`，删除语义改用写 `-1`。

**导航通路（自动化验证用）**：`ipemanager` 是插件化应用，PMS 里查不到那些 Activity，`am start` 解析不了。
可行路径：`su -c am start -a com.oplus.mydevices.ACTION_DEVICE_CARD_HOME_ACTIVITY`（设备中心）
→ 点手写笔卡片 → 下滑触控条。
**验证方法**：hook 内嵌 `gesture marks sync ... state=[key=checked]` 日志读模型状态 + `screencap` 目检；
`uiautomator` 的 `checked` 属性对 COUIMark 自绘控件**不可靠**，不能作为判据。

**状态**：选中 / 互斥 / 重击保持 / 恢复原厂勾回 / 落键 101 与 -1 全部实机通过。
**遗留**：物理下滑 → 截图动作的端到端（消费端已实现，待用户笔势实测确认）。

## P0.12 书写扩展功能包 + 手势×功能自由组合（v4.3.0–v4.3.9，2026-09-19；UI 链路已实机闭环，物理笔动作待测）

**需求**（用户 2026-09-19）：新增「撤销重做 / 手写浮窗便签 / 圈选翻译 / 圈选 OCR / 翻页」，
并让 **按键（手势槽）和功能项自由组合**。

**动作注册表**（`IpeManagerHooks.GESTURE_CUSTOM_CODES`，101..113 **连续**，label 索引 =
code-101，别破坏）：
| 码 | 功能 | 执行 |
|---|---|---|
| 107/108 | 撤销/重做 | system_server 注入 Ctrl+Z / Ctrl+Y（`injectCombo`：CTRL down → key down/up → CTRL up） |
| 109/110 | 翻页上/下 | 注入 PAGE_UP / PAGE_DOWN |
| 111 | 手写便签 | `HandwrittenNoteOverlay`：system_server 直接 addView 的 TYPE_APPLICATION_OVERLAY 全屏手写层（工具条：关闭/撤销/橡皮/清空/4 色/保存），保存走 MediaStore → Pictures/PenBridge |
| 112/113 | 圈选识别/翻译 | `LassoSelectOverlay`：透明覆盖层框选 → `android.window.ScreenCapture`（反射，DisplayCaptureArgs.Builder.setSourceCrop）裁剪 → 保存 PNG + 剪贴板（图片）；翻译再弹分享/`ACTION_TRANSLATE`。**OCR 引擎未接**（`tryRecognize` 留 null），DeepThinker 探测未完成 |

**自由组合的两层含义**：
1. 原 3 个 OEM 手势页（下滑/双击/上滑）继续单选绑定 13 个功能中的任意一个（已有能力）。
2. **新开长按/捏握两个槽**（v4.3.5–v4.3.9 重构，用户要求：样式与原厂一致、不用自定义窗口）：
   弹窗面板「长按」「捏握」为独立行（样式复制上滑卡片：背景/内边距/字号/chevron，
   tag=`lenovo_panel_extra`，值 TextView tag=`lenovo_extra_value_<type>`）；点击
   **直接启动系统原生 `PencilGestureSettingActivity`（`click_type=long_press/squeeze`）**，
   页面内自动注入「书写扩展/系统快捷」双分类，单选样式与原厂一致——不再用自建 AlertDialog。
   OEM 内部其实支持这两个槽位（页面标题 OEM 自设，如「轻捏笔身」）。
   写入 `ipe_pencil_wb_click_long_press` / `_squeeze`。消费端 `SystemStylusHooks.extraGestureAction`：
   `>=100` 走 runCustomAction；`1..4` 复用 click() 的承重路由（4→healthservice，1..3→note/screenshot）；
   `0`=关闭；`-1`/`5`=随心圈 collect。

**v4.3.1 实机验证 + 修复（2026-09-19）**：
- 注入 UI 全量呈现：书写扩展 7 项（107-113）+ 系统快捷 6 项（101-106）+ 恢复原厂选项；单选正常
  （`gesture marks sync single_click selected=113`，原厂行全部 false）。
- **修复 veto 范围过宽（v4.3.1）**：原实现拦截页面上所有非 `item_wb_*` 行的 `setChecked(true)`，
  把「手写笔轮盘设置 → 显示工具名称」（`item_wheel_show_tool_name`，重开页面时框架合法恢复 ON）
  也拦成了 OFF。改为只 veto `GESTURE_STOCK_ROW_KEYS` 内的原厂单选行。
- **重开页面持久化验证**：退出→重进，顶部原厂行保持未选、桥接选择保留，无闪烁无 stale 勾选。
- **恢复原厂选项实机验证**：点击后 bridge=-1、全部扩展行 false、原厂「手写笔轮盘」重新选中。

**v4.3.2–v4.3.4 修复（2026-09-19）**：
- **PencilSettingActivity 摘要联动（v4.3.2/3）**：手写笔主页面（非弹窗）每行的 assignment TextView
  也用 `com.oplus.ipemanager:id/assignment` 渲染原厂值，桥接选择后不刷新。修复：面板 assignment
  重写 hook 同时覆盖 `PencilSettingActivity`（onResume 需额外 hook 具体类——**hookAll 只匹配
  declared methods，该 activity 重写了 onResume，基类钩子拦不到**，与 P0.11 同坑）；主页面
  pass 关闭 addExtraPanelRows（弹窗专用），`schedulePanelPass(root, ctx, allowExtraRows)`。
- **桥接键重启丢失（v4.3.4，根因钉死）**：ipemanager app 进程写的 `Settings.Global` 键在本 ROM
  **重启后被 provider 丢弃**（app 写入会话内可读回、重启后变 -1；root `settings put` 写入则保留）。
  修复：system_server 侧注册 `registerBridgeSettingsWriter`（广播
  `com.aclaniakea.lenovopenbridge.WRITE_GESTURE_KEY`，校验 key 前缀 `ipe_pencil_wb_click_`），
  app 侧全部 3 处写入（行监听 / 原厂行点击清除 / 长按捏握对话框）改走
  `persistGestureKey()`：本地 putInt（会话内即时读回）+ 广播镜像（system uid 写入持久）。
  实机验证：广播 → `bridge settings persisted ipe_pencil_wb_click_single_click=113` → 落库。

**注意**：
- 设置页注入为**双分类**：「书写扩展」(107-113) + 「系统快捷」(101-106)，reset 行在系统组末尾。
- 同一文件多个 Edit **并行调用会互相覆盖**（丢更新），必须顺序编辑 —— 本次又踩一次（3 个 Edit 并行，
  2 个静默丢失），已全部改为逐个提交。
- `HandwrittenNoteOverlay.toggle` 复用同一手势做开关（开着时再触发即关闭），防止笔被"锁"在便签层。
- **弹窗面板行卡片在 RecyclerView 内，禁止对其父容器 addView**（v4.3.5 闪退根因：
  `ViewHolder.shouldIgnore()` NPE）。v4.3.6+ 方案：把上滑卡片改为 VERTICAL LinearLayout，
  原内容包成第一个 sub-row，长按/捏握作为同款 sub-row 追加（sub-row 带卡片背景+间隙 margin，
  视觉上仍是独立行）。幂等：`findViewWithTag("lenovo_panel_extra")` 命中时只刷新值标签。
- `readGesturePageType` 白名单必须含 `long_press`/`squeeze`（v4.3.8 修），否则长按/捏握页注入静默失败；
  另有 fallback：从 activity intent 的 `click_type` 读（`gesturePageTypes` map）。

**v4.3.5–v4.3.9 实机验证 + 修复（2026-09-19 下午）**：
- 用户报障：弹窗点上滑触控条打开的是「原始样式的安卓切换」（自建 AlertDialog），且长按/捏握行文字
  与上滑行重叠。根因：v4.3.4 的行注入把行 addView 进了 RecyclerView → 闪退 + 位置错乱。
- v4.3.5：RecyclerView addView → `ViewHolder.shouldIgnore()` NPE 闪退（点击笔设置即崩）。
- v4.3.6/7：改为卡片纵向堆叠方案（上滑卡片 VERTICAL 化），弹窗渲染正常、无重叠、无闪退；
  长按/捏握点击改为启动原生 `PencilGestureSettingActivity`（弃用自建 AlertDialog）。
- v4.3.8：长按页注入静默失败 → `readGesturePageType` 白名单加 `long_press`/`squeeze`
  + intent fallback。实机：长按页出现书写扩展/系统快捷双分类，选「撤销」→
  `ipe_pencil_wb_click_long_press=107` 经 system_server 落库，**重启后保留**。
- v4.3.9：弹窗摘要不刷新 → 幂等早退分支改为刷新 `lenovo_extra_value_<type>` 标签的 TextView。
  实机：长按→撤销、捏握→通知栏 联动正确。
- 13:33 的 3 次 FATAL 是 `com.oplus.gesture` 系统应用开机时序问题（凭据加密存储未解锁），
  与本模块无关。

**待办**：
- [ ] **物理笔动作验证**（笔已重连，弹窗摘要联动正常）：实际触发各手势 → 撤销/重做/翻页/便签/圈选
- [ ] 长按/捏握物理手势触发 → extraGestureAction 路由复测（bridge=107 已验证重启存续）
- [ ] 捏握行摘要显示「通知栏」为历史遗留值（旧对话框写入 102），如需可重置
- [ ] Ctrl+Z 在便签 doodle 引擎是否生效待测（不生效则改 hook note 应用内部 undo）
- [ ] DeepThinker / ROM OCR 服务探测 → 接入 `LassoSelectOverlay.tryRecognize`
- [ ] 手写便签的压感宽度、防误触（palm rejection）体验调优
- [ ] PencilSettingActivity 摘要联动需笔连接状态下复验（未连接时页面隐藏手势行）

## P0.8 便签手写笔记闪退（native，2026-09-18 完成根因定位，**非本模块引起**）

**现象**：`com.coloros.note` 打开手写笔记后进程崩溃；`/data/tombstones/tombstone_15..23` 共 **9 个**
签名**完全一致**（确定性，非竞态）。

**崩溃点（已逐字节还原）**：
```
pc = 0x0  ← blr 到一个 NULL 函数指针；x0 = 0
#01 EglContext3::eglInit()+76            (libSuniaEngine.so，BuildId 1fa3bc13…)
#02 EglContext3::EglContext3(EglContext3*, bool)+68
#03 ToolFactory::ToolFactory()+156
#05 GroupFactoryManager::createFactory()  #06 CanvasManager::CanvasThreadHandlerFunc
#07 ThreadHandler::ThreadFunc             线程名 DefaultDispatch
```
偏移换算：`.text` 的 paddr/vaddr 差 `0x4000` → `pc 0x697cbc` 对应文件偏移 `0x693cbc`。

**完整调用链（r2 反汇编 + 重定位解析得到）**：
```c
void EglContext3::eglInit() {                     // @0x697c70
    EglInit::makeTempEglContext();                // 建临时 EGL 上下文
    glewExperimental = GL_TRUE;
    glewInit();                                   // @0x828bcc (48B)
    EglInit::clearTempEglContext();
    dpy = __eglewGetDisplay(EGL_DEFAULT_DISPLAY); // @0x697cbc  blr → NULL → 崩
}
```
- 崩溃指令 `0x697cbc: blr x8`，`x8 = [[GOT 0xc2a3a8]]`；该 GOT 槽的重定位符号 = **`__eglewGetDisplay`**。
- `glewInit()` 实现：
  ```c
  r = glewContextInit();                 // 取 eglGetProcAddress("glGetString") 后调 glGetString(GL_VERSION)
  if (r != 0) return r;                  // ← 无 current 上下文 → 返回 GLEW_ERROR_NO_GL_VERSION
  dpy = eglGetProcAddress("eglGetCurrentDisplay")();
  return eglewInit(dpy);                 // 只有走到这里才填 __eglew* 全套指针
  ```
- `eglewInit(dpy)` 开头（@0x81d73c）：`eglInitialize/eglQueryString` 经 `eglGetProcAddress` 取到后，
  **`if (eglInitialize(dpy,&maj,&min) != 1) return;`** —— 若 `dpy` 是 `EGL_NO_DISPLAY`（= 本线程无 current 上下文）
  则直接返回，**`__eglewGetDisplay` 保持 NULL**。
- `EglInit::makeTempEglContext()`（@0x69878c）里 `eglCreateContext` 与
  **`eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, ctx)` 的返回值都被丢弃**；
  库内只有 `eglGetDisplay/eglInitialize/eglChooseConfig` 三条失败日志字符串，**没有 createContext/makeCurrent 的**。
  ⇒ **只要这两步任一失败，引擎静默地没有 current 上下文，紧接着就空指针崩溃。**

**排除项（都实测过）**：
- ❌ 本模块的 Java hook：把 `com.coloros.note` 从 Vector scope 移除（免重启生效）后**仍崩**。
- ❌ 本模块禁用 `com.inkdye.lenovopentocoloros`：`pm enable` 后**仍崩**。
- ❌ 本模块写属性：全仓无 `resetprop`；`grep Settings.*put*` 只写笔相关键。
- ❌ 应用更新：`com.coloros.note` 16.7.2（`lastUpdateTime=2026-09-12`）与本机 `lastUpdateTime=1970` 的
  ROM 自带版 `/my_stock/del-app/OppoNote2/OppoNote2.apk`，**`glewInit` 与 `eglInit` 崩溃点代码完全相同**，
  两版 `makeTempEglContext` 也都是「不检查 createContext/makeCurrent」→ **回滚应用不能修**。
- ❌ `ro.hardware.chipname`（日志里 `Access denied finding property "ro.hardware.chipname"`）：
  该串只出现在 `/system/lib64/libonnxruntime.so`、`/vendor/lib64/libtensorflowlite_c.so`、
  `/odm/lib64/libancbase_rt_fusion.so`（手写**识别模型**加载器），属**无害回退的红鲱鱼**，与 EGL 无关。
- ❌ 可更新 GPU 驱动：`ro.gfx.driver.1=com.qualcomm.qti.gpudrivers.sun.api35` 是 `vendor/app` 系统应用
  （从未更新），且 `dumpsys package com.coloros.note` 无 `gpuDriverPaths` → 便签**没有** opt-in。

**设备侧实测（本仓库外的临时探针，`app_process` + `android.opengl.EGL14`）**：复刻
`makeTempEglContext` 的完整序列（`eglGetDisplay → eglInitialize → eglChooseConfig(与引擎同一组 attribs) →
eglCreateContext(ES3.0) → eglMakeCurrent(surfaceless) → glGetString`）**在本机全部成功**：
```
1 eglGetDisplay      -> ok        err=0x3000
2 eglInitialize      -> true      EGL 1.5 Android META-EGL
3 eglChooseConfig    -> true n=1  (RGBA8888 / ES2|ES3 / CONFORMANT=ES3 / PBUFFER|WINDOW)
4 eglCreateContext   -> ok
5 eglMakeCurrent(no surface) -> true
6 glGetString(GL_VERSION) -> OpenGL ES 3.2 V@0800.72, Adreno 830
```
驱动能力也齐（`dumpsys SurfaceFlinger`）：**`EGL_KHR_surfaceless_context` 与 `GL_OES_surfaceless_context` 均在**。
⇒ **设备本身完全支持这条路径**，失败只发生在便签进程内部 ⇒ 需**进程内**探针才能定位最后一步。

**下一步（二选一，需用户决定）**：
1. **进程内 EGL 探针（推荐，改动小）**：在 `NoteToolkitHooks` 加一个一次性 `EglDiag`，用
   `persist.lenovo.penbridge.egl_diag=1` 开关，在 `com.coloros.note` 里跑同一组 EGL14 调用并把
   每一步结果写 `/data/local/tmp/note_egl_diag.txt`。若进程内**成功** ⇒ 是 OPPO 应用/引擎自身问题
   （外部不可修，只能换便签或打 .so 补丁）；若进程内**失败** ⇒ 是进程环境问题，接着做第 2 步。
   → **[已实现，v4.1.12]** 见下方「进程内 EGL 探针 v4.1.12」。
2. **框架 A/B**：`touch /data/adb/modules/{zygisk_vector,rezygisk,hma_oss_zygisk}/disable` 后重启，
   再试手写笔记。用于排除「某个 Zygisk/Xposed 框架注入便签进程破坏 native GL 初始化」。
   ⚠️ 会同时停掉笔桥（重启后记得删掉这些 `disable` 文件再重启一次）。

**可能的修法（若确认是引擎自身）**：给 `/data/app/.../com.coloros.note-.../lib/arm64/libSuniaEngine.so`
打补丁（root 可直接覆写该文件，签名校验只针对 APK 安装）——把 `eglInit` 里的
`blr x8`（`0x697cbc`）换成直接 `bl <PLT eglGetDisplay @0xb90c20>`，并让 `glewInit`
无条件走 `eglewInit`（`0x828bd8` 的 `cbz w0` 前插 `mov w0, wzr`）。**风险高、易被应用更新覆盖**，非首选。

### 进程内 EGL 探针 v4.1.12（2026-09-18 实现）

新增 `hook/source/sources/.../NoteEglDiag.java`（一次性、带开关、默认关闭），由
`NoteToolkitHooks` 在两处相位调用：`Application.onCreate` / `MyApplication.onCreate`（相位
`app-oncreate:*`）与 `Toolkit.onAttachedToWindow` / `onResume$paint_intermediate_release`
（相位 `toolkit:*`）。

- **开关**：`settings put global lenovo_penbridge_egl_diag 1`（也接受 `persist.lenovo.penbridge.egl_diag=1`）。
  仅当包名 == `com.coloros.note` 时才生效，避免扰动 `com.oplus.screenshot`。每相位每进程只跑一次。
- **跑什么**：先记录「探针前已 current 的 EGL」状态，再分别在**调用线程**与**另起 worker 线程**上复刻
  `eglGetDisplay → eglInitialize → eglQueryString → eglChooseConfig(PBUFFER_BIT/ES2) →
  eglCreateContext(ES3) → eglMakeCurrent(双 EGL_NO_SURFACE) → glGetString(GL_VERSION)`，逐步骤记录
  `eglGetError()`。分两线程是刻意的——**崩溃发生在非主线程**（`DefaultDispatch`），只跑主线程答不了问题。
- **附带环境取证**：`/proc/self/maps` 里 libEGL/libGLESv2/libglew/libSuniaEngine/libadreno 等映射行、
  `nativeLibraryDir` 列表、dataDir、ABI、fingerprint。**若发现便签自带 `libEGL.so` 遮蔽系统库即为铁证**。
- **落盘**：主 `/data/local/tmp/note_egl_diag.txt`（需 root 预先 `touch` 并 `chmod 666`；SELinux
  `Enforcing` 下 app 写 `shell_data_file` 很可能被拒），镜像 `files/note_egl_diag.txt`
  （`su -c cat /data/data/com.coloros.note/files/note_egl_diag.txt`，一定可写）。
- **判读**：进程内成功 ⇒ OPPO 引擎自身问题（外部不可修）；失败 ⇒ 进程环境问题，转下一步 ①/②。
- 本次探针**不与 EGL 生命周期冲突**：不调用 `eglTerminate`，只销毁自己建的 context。

### 进程内探针**实测结论（2026-09-18，决定性）**：进程内 EGL **完全正常** ⇒ **OPPO 引擎自身问题**

v4.1.12 部署 + 重启 + `cmd activity start-activity -n com.coloros.note/com.nearme.note.main.MainActivity`
起进程后（注：`am start -n` 对该包一律报 "Activity class does not exist"，但
`cmd activity start-activity -n` 与 `monkey -p` 均可正常拉起），探针落盘
`/data/data/com.coloros.note/files/note_egl_diag.txt`（24 KB）。
`/data/local/tmp/...` 那条路**实测 EACCES**（SELinux Enforcing 下 app 写 `shell_data_file` 被拒），
镜像路径按设计生效。

**4 次进程启动 × 2 条线程（主线程 + 另起 worker）= 8/8 全部成功**，逐项一致：
```
eglGetDisplay(DEFAULT)      -> ok        err=EGL_SUCCESS
eglInitialize               -> true v1.5  1.5 Android META-EGL
EGL_KHR_surfaceless_context -> true
eglChooseConfig             -> true n=4
eglCreateContext(ES3)       -> ok
eglMakeCurrent(surfaceless) -> true      err=EGL_SUCCESS
glGetString(GL_VERSION)     -> OpenGL ES 3.2 V@0800.72 (GIT@3967b80d3b, Ica4ce9bea6, 1770914544) (Date:02/12/26)
glGetString(GL_VENDOR)      -> Qualcomm
glGetString(GL_RENDERER)    -> Adreno (TM) 830
glGetError                  -> 0x0
```
覆盖的进程：`com.coloros.note`（主进程，2 次）与 `com.coloros.note:tbl_privileged_process0`
（2 次）—— **两个进程都是 8/8 成功**。

**环境取证（排除掉所有「环境」解释）**：
- `nativeLibraryDir` 里只有 `libsuniabase.so` + `libSuniaEngine.so` 是与图形相关的（共 31 个条目），
  **没有任何自带的 `libEGL.so` / `libGLESv2.so` / GLEW** ⇒ 不存在「便签自带 libEGL 遮蔽系统库」。
- `/proc/self/maps` 里 EGL/GLES 全部来自系统：`/system/lib64/libEGL.so`、
  `/system/lib64/libGLESv2.so`、`/system_ext/lib64/libvulkanextimpl.so`、
  `/vendor/lib64/libadreno_utils.so`、`/system/lib64/libegl_flags.so`、`libui.so`、`libvulkan.so`。
- 探针**运行前**该线程就是干净的 `EGL_NO_DISPLAY / EGL_NO_CONTEXT / EGL_NO_SURFACE`
  （没有「卡住的 current context」「别的 display」这类脏状态）。
- 主线程与后台线程**结果完全相同** ⇒ 不是线程亲和性 / 非主线程限制问题。

⇒ 按既定判读标准：**进程内成功 ⇒ 是 OPPO 应用/引擎自身问题**。
与 P0.8 前面的逐字节分析（`glewInit()` 在有 current context 之前被调用 ⇒ `glewContextInit()` 因
`glGetString(GL_VERSION)` 拿不到版本而返回 `GLEW_ERROR_NO_GL_VERSION` ⇒ `__eglewGetDisplay` 保持 NULL；
而 `makeTempEglContext()` 又把 `eglCreateContext`/`eglMakeCurrent` 的返回值丢掉 ⇒ 失败无声）
**互相印证**：**同一进程、同一线程、同一组调用我们能跑通，引擎自己跑不通 ⇒ 是引擎调用时序/错误处理的问题。**

**因此外部可做的只剩两条**（与前面判断一致）：
1. 换应用（不用 `com.coloros.note` 手写）；
2. 给 `libSuniaEngine.so` 打补丁（见上文「可能的修法」），**风险高、且会被应用更新覆盖**。

**开关现状**：`settings put global lenovo_penbridge_egl_diag 0` —— 已关闭（默认关，避免每次便签进程
启动都写 24 KB）。要再采集（例如抓 `toolkit:*` 相位）：`settings put global lenovo_penbridge_egl_diag 1`，
复现后 `su -c cat /data/data/com.coloros.note/files/note_egl_diag.txt`。
⚠️ 注意 `toolkit:onAttachedToWindow` / `toolkit:onResume$paint_intermediate_release` 两个相位在本次
**没有触发**（只开了便签列表页，没进手写画布），所以「画布阶段是否也正常」仍未被直接观测；
但既然同进程同时刻连 worker 线程都能成功，进一步采集的边际价值很低。

### 决定性加强证据：**同 PID 先通过探针、随后就崩在引擎自己那行**（2026-09-18 18:44–18:45）

原以为「只开了列表页所以引擎没跑到崩点」，实际不然 —— **便签启动后几十秒内自己就崩了**，
而且崩的正是那几个刚跑完探针的进程。`/data/tombstones` 里新出现了两条（重启后写入）：

```
tombstone_25  18:44:14  pid 12039  >>> com.coloros.note <<<
tombstone_26  18:45:52  pid 19643  >>> com.coloros.note <<<
 #00 pc 0x0 <unknown>
 #01 pc 0x697cbc  libSuniaEngine.so (EglContext3::eglInit()+76)     <-- 与之前 9 条**同一偏移**
 #02 pc 0x698024  libSuniaEngine.so (EglContext3::EglContext3(EglContext3*, bool)+68)
```

对照探针日志里的同一个 PID：

| pid | 探针（相位 `app-oncreate`） | 探针结果 | 之后的结局 |
|---|---|---|---|
| **12039** | 18:44:11.737 | 主线程 ✅ + worker ✅ → `GL_VERSION = OpenGL ES 3.2 ... Adreno 830` | 18:44:14 崩在 `EglContext3::eglInit()+76` |
| **19643** | 18:44:22.386 | 主线程 ✅ + worker ✅ → 同上 | 18:45:52 崩在同一偏移 |
| 17286 / 20775 | 18:44:12 / 18:44:23 | 同样 ✅ | （`:tbl_privileged_process0`，未见 tombstone） |

**这是本案最硬的一条证据**：在**同一个进程**里，探针能在 `t=0` 与 `t=+11s` 把
`eglGetDisplay→eglInitialize→eglChooseConfig→eglCreateContext(ES3)→eglMakeCurrent(surfaceless)→
glGetString(GL_VERSION)` 全部跑通（主线程与后台线程都行），**而引擎自己在几十秒后崩在
`blr __eglewGetDisplay`（NULL）**。⇒ 排除「进程环境」「线程亲和性」「驱动能力」「库被遮蔽」
「便签自带 libEGL」等一切环境解释，**只能是 `libSuniaEngine.so` 自己的调用时序 + 丢弃返回值的错误处理**。

**副作用观察**：便签现在**自己就会崩**（不需要用户手写、进列表页几十秒即崩），
所以在这台机器上 `com.coloros.note` 目前基本不可用 —— 与用户的主观感受一致。


## P0.9 `vendor.oplus.hardware.urcc-service` 持续崩溃（2026-09-18 完成根因定位，移植缺件）

**现象**：`/odm/bin/hw/vendor.oplus.hardware.urcc-service` 反复崩、反复被 init 拉起。
今日 `/data/tombstones` 共 **32 条**：urcc **24 条**、`com.coloros.note` 8 条（见 P0.8）、
`system_server` 3 条（见 P0.10）。urcc 的 24 条**签名完全一致**。

**签名**（tombstone_00 为代表）：
```
pid: 1868, tid: 1868, name: UrccMainThread  >>> /odm/bin/hw/vendor.oplus.hardware.urcc-service <<<
signal 11 (SIGSEGV), code 1 (SEGV_MAPERR), fault addr 0x0
Cause: null pointer dereference
 #00 pc 0x0 <unknown>                       <-- 调了个空函数指针
 #01 pc 0x58bec /odm/lib64/liburcccore.so (urccResStateRequest+396)
 #02 pc 0x512c  .../urcc-service (Urcc::urccResStateRequest(aidl ...)+96)
 #03 pc 0xd570  /odm/lib64/vendor.oplus.hardware.urcc-V1-ndk.so (IUrcc::onTransact+5144)
 #04 ... libbinder_ndk.so / libbinder.so     <-- 普通 Binder 调用路径
```
崩溃线程既可能是 `UrccMainThread`，也可能是 `binder:<pid>_N` —— 即**任何**走到该 AIDL 调用的
线程都会崩，不是单线程问题。

**逐指令定位**（`liburcccore.so`，md5 `1c160f3dad18a60109b20ca3698449f2`）：
```
0x58bd8  adrp x9, 0x93000
0x58bdc  ldr  x9, [x9, 0x4d8]   ; x9 = UrccCtlServer::uah_lib   (重定位符号 _ZN13UrccCtlServer7uah_libE)
0x58be0  ldr  w20, [x8]         ; 请求 id
0x58be4  ldr  x8, [x9, 0xc0]    ; x8 = uah_lib->[0xc0]   <-- 无 null 检查！
0x58be8  mov  w0, w20
0x58bec  blr  x8                ; <-- pc=0，SIGSEGV
```
槽位映射（由 `UrccCtlServer::loadUahCoreLib()` @0x4d804 的 dlsym 序列解出）：
- `+0x88` = `dlopen("/odm/lib64/libuahcore.so")` 的句柄
- `+0x90` = `uah_init`、`+0xa0` = `uah_request`、`+0xa8` = `uah_get_feature_status`、
  `+0xb0` = `uah_allow`、`+0xb8` = …、**`+0xc0` = `uah_get_mode_status`**、`+0xc8` = `uah_get_feature_status`
- 同一函数里 `+0xc8` 的调用点**有** `cbz x9, <bail>` 保护（0x58b5c），而 `+0xc0` 的调用点**没有**
  —— 上游自己的 null 检查不一致，只有「UAH 库缺失」这种设备才会暴露。

**根因**：**`/odm/lib64/libuahcore.so` 在这台设备上根本不存在**。
```
/odm/lib64/libuahcore.so             MISSING   <-- 铁证
/odm/lib64/liboplus_gpu_utils.so     MISSING   (loadUahCoreLib 也 dlopen 这个)
/vendor/lib64/libpowerhal.so         MISSING   (同上)
/odm/lib64/liburcccore.so            PRESENT
/odm/bin/hw/vendor.oplus.hardware.urcc-service  PRESENT
$ find /odm /vendor /system -iname '*uah*'   ->  只有 liboplus-uah-client.so（不是 core）
```
`dlopen` 失败 ⇒ 句柄 NULL ⇒ 之后所有 `dlsym(NULL, "uah_*")` 全部返回 NULL ⇒ `uah_lib` 表**整表为空**
⇒ 第一次 `UrccResStateRequest` 就撞 `+0xc0`（`uah_get_mode_status`）⇒ 崩。init 拉起后再被调用再崩
⇒ **崩溃循环**。

**为什么这台设备会缺件**：`vendor.oplus.hardware.urcc-service` 是 **OPPO 的性能/调度总控**
（读它的 rc 就明白：它去 `chmod`/写 `/proc/perfmgr/boost_ctrl/eas_ctrl/*`、`/proc/oplus_scheduler/...`、
`/proc/game_opt/...`、`/proc/ufsplus_ctrl/*`、`/sys/devices/platform/soc/soc:oplus-omrg/*`、`oplus_cpu_boost`、
`ufshc` devfreq、`/dev/cpuctl/*` …）。实测这些 **OPPO 专属内核节点全部 MISSING**：
```
/proc/perfmgr/boost_ctrl/eas_ctrl      MISSING
/proc/oplus_scheduler                  MISSING
/proc/game_opt                         MISSING
/proc/ufsplus_ctrl                     MISSING
/sys/devices/platform/soc/soc:oplus-omrg  MISSING
/sys/class/devfreq/kgsl-busmon         PRESENT   (高通自有，与 OPPO 无关)
uname: Linux 6.6.82TB522FU ... aarch64     ro.board.platform=sun  ro.soc.model=SM8750P
```
即：**移植时把 OPPO 的 odm 服务/库搬了过来，但 Lenovo/高通侧的内核接口和 `libuahcore.so` 没跟过来。**
该 HAL 在本机**理论上不可能工作**（它要控的调度节点一个都不在）。它在 `/odm/etc/vintf/manifest/`
里有声明（`vendor.oplus.hardware.urcc-service.xml`），所以框架侧 UAH 会去 bind 它：
`UAH-UahAdaptHelper: getAidlService uah urcc service = vendor.oplus.hardware.urcc.IUrcc$Stub$Proxy@…`
⇒ 调用 ⇒ 崩 ⇒ 循环。

**影响评估**：24 次/约 2 小时，崩溃-重启的耗电/日志噪音是实打实的；功能上因为该 HAL 本来就不可能工作，
**丢掉它不会比现在更差**（UAH 拿不到服务会走降级分支）。**但它不是 note 闪退的原因，也不是 system_server
崩的原因**（签名/调用栈完全无关）。

**可选处置（按推荐度）**：
1. **只做可观测性 + 忽略**：`setprop persist.vendor.oplus.uah.debugenable 1` 前先确认；urcc 崩一次就
   重启一次，代价可接受。**零风险**，先这样跑。
2. **让 init 别再拉起它**：用 KernelSU 模块 overlay `/odm/etc/init/vendor.oplus.hardware.urcc-service.rc`
   把 service 改成 `disabled`（并删掉那些注定失败的 `chmod` 段）。副作用：vintf 里仍声明该 AIDL HAL，
   `init`/`vintf` 可能报 "HAL not found"；需实测框架是否因此降级异常。
3. **补齐缺件**：从**同版本 OPD2409 (ColorOS 16) 的 odm 镜像**里取 `libuahcore.so` +
   `liboplus_gpu_utils.so` + `libpowerhal.so` 放进 `/odm/lib64/`。⚠️ **ABI 必须完全匹配**，
   且这些库还会去碰上面那些不存在的内核节点 —— **不保证能治好，可能只是把 NULL 崩换成别的崩**。
4. **不建议**：给 `liburcccore.so` 打补丁（把 0x58be4 的空槽调用绕开）。治不了根，且该库属于 odm，
   升级即失效。除非 1/2 都被证明不可接受。

**验证已做**：`getenforce=Enforcing`（与 SELinux 无关）；无 dmesg 噪声（init 的报错走 logcat 且已 rollover）。

## P0.10 `system_server` 偶发崩溃（ART 内部 abort，2026-09-18 定位到失败模式，未定因）

今日 3 条 `system_server` tombstone（05 / 12 / 29），**签名一致**：

```
pid: 2271, tid: 3446, name: HeapTaskDaemon   >>> system_server <<<      (05 是 Thread-2, 29 是 HeapTaskDaemon)
signal 11 / 6,  fault addr 0x9 / --------
Abort message: 'Failed to recognize implicit suspend check at 0xaa5e46b4;
                thread state = Runnable; mutator lock shared held = false;
                code ranges = {{0x66800000, 2000000}, {0x62800000, 2000000}, ...
                               {0xaa45c000, 2ea058}, {0x72c5052340, eed0}}'
backtrace:
 #00 libart.so (art::ReferenceMapVisitor<art::RootCallbackVisitor,false>::VisitFrame()+2820)
 #01 libart.so (StackVisitor::WalkStack<...>(bool)+340)
 #02 libart.so (art::Thread::VisitRoots(art::RootVisitor*, art::VisitRootFlags)+1088)
 #03 libart.so (art::gc::collector::MarkCompact::RunPhases()+660)
 #04 libart.so (art::gc::collector::GarbageCollector::Run(...)+328)
 #05 libart.so (art::gc::Heap::CollectGarbageInternal(...)+608)
 #06 libart.so (art::gc::Heap::ConcurrentGC(...)+168)
 #07 libart.so (art::gc::Heap::ConcurrentGCTask::Run(art::Thread*)+76)
 #08 libart.so (art::gc::TaskProcessor::RunAllTasks(art::Thread*)+124)
 #09 /system/framework/arm64/boot-core-libart.oat (art_jni_trampoline+112)
 #10 boot-core-libart.oat (java.lang.Daemons$HeapTaskDaemon.runInternal+184)
```
三条的 abort 文本逐字相同、只有 PC 与 `code ranges` 基址不同（05: `0xa9982548`，12: `0xaa5e46b4`，
29: `0xaa604754`）。**失败模式**：ART 的 **并发 MarkCompact（压缩式）GC** 在
`Thread::VisitRoots → StackVisitor::WalkStack → ReferenceMapVisitor::VisitFrame` 遍历**某个线程**的栈时，
发现该线程 PC 处的指令**不是**它期望的「隐式 suspend check」序列（且该线程还是 `Runnable`、
未持 mutator lock 共享锁），于是 `LOG(FATAL)` 主动 abort。

**注意**：被 abort 的线程（`HeapTaskDaemon`）栈是纯 ART + boot-core-libart.oat —— 出问题的**不是**
崩溃线程本身，而是**被它遍历到的那条线程**。abort 里给出的 PC（如 `0xaa604754`）**落在它自己列出的
code range `{0xaa45c000, 2ea058}` 内**，也就是说「地址在已注册的 oat 代码段里，但那段代码不是
stack map 期望的内容」⇒ 指向 **执行中的代码与 oat/stack map 不一致**。

**两个候选（尚未区分，需 A/B）**：
- **(a) ROM 的 ART/boot image 与运行时不匹配**：这是移植 ROM 最典型的一类问题。三条 tombstone 的
  帧 #09/#10 落在 `/system/framework/arm64/boot-core-libart.oat`，而 abort 的 code range
  `{0xaa45c000, 2ea058}`（约 3MB）大小也吻合 boot image 量级。`/system/framework/arm64/` 下还有
  OPPO 特有的 `boot-QPerformance.*` / `boot-UxPerformance.*`（带 `.fsv_meta` 完整性元数据），
  以及 OPPO 私有 `dalvik.vm.enable_pr_dexopt=true` / `dalvik.vm.appimageformat=lz4` /
  `dalvik.vm.isa.arm64.variant=oryon`。**若 boot.art/oat 与 `libart.so` 不同源，就会出现「代码段对、
  内容不对」**。
- **(b) hook 框架 deopt 打补丁**：Vector 安装 hook 时会对目标方法做 `ThreadList::SuspendAll` + deopt
  （见 P0.5），deopt 会把 oat 里方法入口改成 trampoline / 就地补丁。若补丁与并发压缩 GC 交叠，
  就会让「代码段里 PC 处不是预期的隐式 suspend check」。**本模块确实注入 system_server**
  （这正是笔桥需要的）。

**排除项**：三条 tombstone 里**都没有** urcc 相关帧（P0.9 是独立问题）；崩溃线程栈**没有任何** hook
框架帧（`/data/adb/...`、libzygisk、lspd 只出现在内存映射 dump 里，属正常注入痕迹，**不是**因果证据）。
`grep -lE "/data/adb/(modules|lspatch)|libzygisk|zygiskd|lspd|rezygisk|libhm"` 命中 05/12/15/16/29 ——
这只是 Zygisk 在每个进程都注入，**不能**当罪证。

**下一步（区分 a/b，按代价从小到大）**：
1. **先数频率**：清空 `/data/tombstones`（或记住序号），正常用一天，看 system_server 是否会再崩、
   以及是否**只在装了 hook 模块时**出现。当前 3 次/2h 样本太小。
2. **框架 A/B**：`touch /data/adb/modules/{zygisk_vector,rezygisk,hma_oss_zygisk}/disable` 后重启，
   复现「之前必崩」的场景。这条能一刀切开 (b)。⚠️ 会同时停掉笔桥，记得删掉 `disable` 再重启。
3. **验证 (a)**：比对 `libart.so` 的 BuildId 与 boot image 的编译指纹
   （`strings /system/framework/arm64/boot.oat | grep -i "dex2oat"`、`/system/framework/arm64/boot.art`
   的 `image_checksum`/`oat_checksum` 与 `libart.so` 的 `ArtMethod` 布局版本是否同源）；
   以及 `/data/dalvik-cache/arm64/` 里是否有**别的 ROM 留下的陈旧 odex**（移植后未清 data 的经典坑）。
4. **缓解（若能确认 a）**：避开压缩式 GC（`-Xgc:` 指到非 MarkCompact 的 collector，或调
   `dalvik.vm.gc.*`），使 ART 不走 `MarkCompact::RunPhases` 这条遍历路径。**未验证，先别动**。

**结论**：**已把失败模式钉死在 ART 的 `MarkCompact → Thread::VisitRoots → WalkStack → VisitFrame`
里**（不是 binder、不是 HAL、不是我们的 Java 代码），但 **(a) ROM ART/boot-image 不同源** 与
**(b) hook deopt 补丁** 两种成因**尚未区分**，需按上面 1→2 步做 A/B。**不作任何未经实验的结论。**
