# tb522fu-pen-port — 联想手写笔 TB522FU 彻底适配

把 coloros-pad-fixes 的 `lenovo_pen_bridge`（ACLaniakea，v4.1.3，面向 TB710FU / SM8650Q / pineapple）移植到 **Lenovo TB522FU**（`sun` / SM8750P / 骁龙 8 Elite，运行移植版 ColorOS）。

## 结构

```
module/       KernelSU/Magisk Root 模块
  service.sh    主入口（约 290 行）：配置区 → 加载 lib → 生命周期编排
  lib/          按职责拆分的库，纯函数定义，由 service.sh 以 `.` 加载
                core       日志 / settings 读写 / 不 fork 的等待
                pen_id     笔 MAC 规范化与绑定记录发现
                pen_hw     Hall / 电量 / 充电 / CPS 只读真值
                pen_link   OEM 连接动作与链路真值判据
                pen_ui     状态镜像与磁吸胶囊发布
                boot       启动期需重试到确认生效的动作
                monitors   五条常驻监视循环（永不返回，由主文件 & 启动）
  bin/penlog.sh 日志裁剪 helper（条数 + 字节双上限，同 inode 就地回写）
  action.sh     管理器「执行」按钮的控制台（状态 / 日志 / 开关）
  customize.sh  安装时动作（inkdye / Hook APK / 权限 / 旧路径清理）
hook/         Xposed Hook 源码（com.futureharmony.lenovopenbridge）
scripts/      设备侦察/推送辅助脚本
docs/         分析与验证记录
fix-module/   显示基线（refresh_rate_config）等的独立 fix 模块
releases/     构建产物（模块 zip / Hook APK）
```

> **改 `service.sh` 前先读这一条**：函数定义全在 `lib/` 下，主文件只负责配置与
> 调用顺序。`source` 必须早于任何 `monitor_* &` —— 子 shell 在 fork 那一刻复制父
> 进程的符号表，晚 source 会让监视循环**静默** `command not found`（不报错、不退出，
> 只是循环体里什么都不发生）。`lib/` 在构建白名单里以**目录**形式声明，新增文件会
> 自动打包，但手工 `cp` 单文件到设备调试时别忘了 `lib/` 整个目录。

## 路线与框架事实

**走运行时 hook 路线**（静态 patch / 重打包 ROM 暂缓，待逻辑稳定后固化进底包）。

框架不是 LSPosed，而是 **Vector**（JingMatrix，`zygisk_vector` v2.2，Xposed 兼容，
Android 8.1~17），守护进程 `vectord`，管理器 `app.morphe.manager`，CLI 位于
`/data/adb/modules/zygisk_vector/cli`。LSPosed 未安装也不需要。

安装与验证步骤见 **[`docs/install-vector-route.md`](docs/install-vector-route.md)**（含
vector-cli 用法、踩坑记录、救援分层）。

## 安装/回退

- **安装（推荐一条命令）**：`scripts/deploy_vector.sh` —— 装 Hook APK（root `pm install`，ColorOS 拦 `adb install`）
  → `vector-cli modules enable` + 按 `scope.list` 重设作用域（**1 + 7 项**；system_server 必须用伪包名
  `system`，写 `android` 会让 hook 永不进 system_server，详见
  [`docs/install-vector-route.md`](docs/install-vector-route.md) 第 3 节）→ `ksud module install` → 重启。
  脚本装前校验 APK 声明、装后**回读断言** `system/0` 存在；手工分步见同文档第 2 节。
- **构建（统一入口）**：`python3 build.py`（APK + 模块 zip + md5 + 设备在线时做一次作用域自检），
  `--push` 顺带推送，`--no-device` 只构建。
- **inkdye 默认禁用**：模块开机把系统内置笔桥 `com.inkdye.lenovopentocoloros`
  （实际装在 `/system/priv-app/LenovoPenBridge/`）`disable-user`，笔能力由本模块
  （Root 服务 + Hook）接管。想回退到系统内置笔桥：在 KSU/Magisk 管理器「执行」
  按钮（`action.sh`）里开启。
  - 切换：`su -c 'sh /data/adb/modules/tb522fu_pen_bridge/action.sh enable|disable|toggle'`（默认 disable）
  - 用户选择记录于 `inkdye-enabled.state`（存在 = 显式**启用**内置笔桥，覆盖默认）。
- **回退**：卸载模块 → `uninstall.sh` 自动 `pm enable` 恢复 inkdye、杀守护、恢复 TX=1。
  临时仅停守护：`touch $MODDIR/disable-charge-guard`。

> ⚠️ 启动失败自保（boot guard）**只管 KSU 模块**。Vector hook 若导致卡死，
> 须用 `vector-cli modules disable com.futureharmony.lenovopenbridge` 或安全模式。
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
9. **部署链加固：作用域单一真源 + 幂等自检（2026-09-19，v4.5.5）**：改名（第 8 条的包名迁移）
   在 Vector 里等于**新建一个模块身份**，作用域回落到空 → 重设时又写成 `android`，
   全部自定义键（101–113）静默失效（同 P1.5 的坑第二次踩）。根因不在 hook 代码，
   而在**没有任何一处脚本对设备端 scope 负责**。现改为：
   - `scripts/pen_release.py`：产物「最新版」按**版本**排序（旧的 `sorted(glob)[-1]` 是字典序，
     `v4.5.10` 会输给 `v4.5.4`）；作用域从 `hook/source/resources/META-INF/xposed/scope.list`
     派生，`deploy_vector.sh` / `push_to_device.sh` 不再各写一份（原两者分别硬编码
     `v4.1.13`、`v4.1.3`，**都是旧包名的 APK**，直接推过去装 = 装了个永远不会被 enable 的包）。
   - `build.py` 第 4 步：读设备运行时 scope 与 `scope.list` 比对，缺 `system/0` 用 `scope add`
     补齐、多余项 `scope rm` 清掉，再复读校验；无设备自动跳过。
   - `deploy_vector.sh`：adb 路径自动解析、APK 版本地板（拒 pre-rename）、安装前校验
     APK 内声明 scope 含 `system`、`scope set` 后**回读断言**，失败即 fail-fast。
   - Hook APK 版本收敛到 `build_hook_source.py` 的单个常量 `APK_VERSION`。
10. **日志查看控制台 + 日志限流（2026-09-19，模块 v0.1.1）**：管理器模块卡片的「操作」按钮
   （跑 `module/action.sh`）在原有状态摘要之后，**追加最近日志片段**：
   `pen-bridge.log` 120 行 / `charge-guard.log` 60 行 / `note_engine_guard.log` 60 行 /
   Hook 日志（logcat tag `LenovoPenBridge`）200 行，末尾给出本次截断说明。
   直接用管理器原生控制台，**没有引入 WebUI**（该管理器每张卡片只有一个控制台入口，
   `hasActionScript=` / `hasWebUi=` 是仅有的两个开关）。
   写入侧同时上双上限（新 `module/bin/penlog.sh`）：**条数上限**（`pen-bridge.log` 400 行、
   `charge-guard.log` 200 行、`note_engine_guard.log` 200 行）**+ 字节上限** 128 KB，
   超限**保留最近 N 行**而不是整file清空。
   ⚠️ `service.sh` 用 `exec >>"$LOGFILE"` 持有 fd，所以裁剪必须**同 inode 就地回写**，
   不能 `mv`（否则写入会落到已 unlink 的旧 inode）。另：本 ROM 的 logcat **不支持
   `-t N` 与 `-s TAG` 同用**（静默返回空），Hook 日志用 `-s TAG | tail -n N` 截断。
   快捷入口：`sh action.sh log`（只看日志段）、`sh action.sh clearlogs`（就地裁到 5 行）。
11. **画布内 107/108 直达撤销/重做（2026-09-20，v4.6.0）**：全屏涂绘画布对按键**完全惰性** ——
   `NewPaintActivity`(16 个成员) / `NewPaintFragment`(480 个，`KEY_*` 全是 Intent extra 常量) /
   `CoverPaintView`(188 个) / `doodleengine.PaintView`(370 个) 声明了 **0 个按键回调**，
   `PaintView extends FrameLayout` 也不覆写任何 key 方法，所以注入的 `Ctrl+Z` 送到焦点窗口后
   直接被丢弃；标题栏 ↺ 按钮走的是 `NewPaintFragment.initTitleBar$lambda$63 → PaintView.undo()`。
   新增 `hook/.../CanvasPaintHooks.java`：system_server 侧在注入按键**之前**额外向
   `com.coloros.note` 发 `CANVAS_UNDO` / `CANVAS_REDO` 定向广播，便签进程内的接收器把它转成
   `NewPaintEditPresenter.undo()/redo()`（PUBLIC、零参数、与 ↺ 按钮同一条链路）。
   **两条路不会同时生效**：命令只在「画布片段 `isAdded() && isResumed()`」时被消费，而
   `NewPaintFragment` 只被 `com.nearme.note.paint.*` 引用（`MainActivity` / `NoteDetailFragment` /
   `NoteDetailPaintManager` 完全不引用），该条件因此等价于「全屏画布正在前台」—— 而那正是
   按键惰性的场合。WebView 编辑器等键盘界面不受影响（无画布 → 命令被忽略 → 仍走按键注入）。
   **部署状态**：v4.6.0 已于 2026-09-20 部署到 TB522FU（含 Vector scope 回读断言与开机验证），
   接收侧通道用 root 广播实测通过（1 次广播 = 1 次撤销）；发送侧（实际捏握）待笔实测。详见 `TODO.md` P0.14。
12. **唤醒守护从"形同虚设"修到可用（2026-09-21，v0.1.19 → v0.1.21）**：用户实测报「dock 睡死取下后
   震动可用但写不出字」。定位到**守护一次都没执行过** —— 笔尖 evdev 节点解析的 `/proc` awk 在
   `H: Handlers=event5 cpufreq` 这种粘连写法下永不命中，而 rc=2 又被当作"跳过"，属**静默失败**。
   同时修掉"断而不建"（自发的 `DISCONNECT_PENCIL` 会置上断开闩锁，连不回来）与默认关闭两个问题，
   并把 `link=0` 分支从空转改为走同一套 link cycle。**两分支均已实机验证**（睡死→自动断链重连+
   震动握手重放；清醒→不误伤）。
   v0.1.20 又补上**第四处同类静默空转**：原厂 `CoreService` 没有 `directBootAware`，
   **开机到首次解锁之间** `am` 投递必然 `rc=255`，而守护会在这一瞬间把"本次离座会话的一次性
   武装"直接烧掉 → 用户随后解锁落笔时笔仍是死的。修法是在消费武装**之前**加锁屏闸门：
   未解锁则保持武装不消费，解锁后下一次轮询补做。
   v0.1.21 是**第一次拿到生产样本**（21:36 用户报"有震动无笔画，过了一会正常"）后调参：
   ① 断链→笔出笔画实测 **3.9s**，而失连保持只有 3s ⇒ 余量 0.37s，属撞运气 →
   提到 5s（E4 区间上沿）；② 补上**循环后验证**（`cycle OK` / `WARN cycle FAILED`），
   让"循环跑完但白跑一趟"从不可见变成可见（此前返回 0 只代表"链路回来了"，不代表笔能写）；
   ③ 冷却跳过也不再无声。真·深睡场景仍待用户复测。详见
   `docs/pen_wake_guard_silent_noop_20260921.md`（§12 是这次生产样本的完整时间线）。

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

> **v0.1.25（2026-09-22）：根除磁吸充电胶囊广播风暴 + 状态键对账自愈。**
> 三条补丁合力掐掉「笔一吸附就无限弹充电提示 / 吸附后仍一直弹」的反馈回路：
> ① `HookUtils.setPhysicalDocked` 与 `markOemCharging`/`clearOemCharging` 改为**只在值真变化时写**
>    `Settings.Global` —— `putInt` 即使值没变也会通知 `ContentObserver`，正是自持回路的燃料；
> ② `PenBridgeReceiver.broadcastColorOs` 对 ColorOS 状态广播做**去重限流**（同一状态签名 1.5s 内只发一次），
>    实测把 26s/264 次广播压到正常周期刷新；
> ③ `SystemStylusHooks.showDockCapsule` 最后一道闸：**同一磁吸边沿 6 秒内只允许自动弹一次**。
> 另补 `monitors.sh` 每 5s 与原始 Hall 对账 `lenovo_pen_physical_docked`，自愈外部写者写歪的死值
> （实测事故：hall=0 而该键卡在 1，笔 UI 长期显示「吸附/充电」）。
> 实测：胶囊弹出 26s 内由 **19 次降到 1 次**。正常场景书写已确认可用（见 TODO.md）。
>
> **v0.1.24（2026-09-22）：`service.sh` 架构重构（纯结构调整，行为等价）。**
> 单文件 1464 行按职责拆成 `module/lib/` 七个库，主文件降到 289 行，只保留配置区、
> 单例锁、加载与调用编排、启动尾巴和历史留档。同一批改动收掉了长期堆积的语法噪音：
> `log`/`log_err` 51 处、`get_global` 42 处、`put_global` 38 处、`put_global_diff`
> 3 处读-比-写 —— 原先这些管道与重定向在业务判断里占了大量视觉空间。
> **等价性对账**：settings 键 24 个、`am broadcast` action 3 个、45 个函数定义
> 与重构前逐条一致，无丢失、无重复定义；`sh -n` 单文件与合并校验双通过。
> 构建侧顺带把 `lib/` 改成**目录形式**声明进白名单，以后新增库文件自动打包，
> 杜绝「忘了加白名单 → zip 永久缺文件 → 重装后静默失效」这个老坑。
>
> **v0.1.23（2026-09-21 深夜）：深睡唤醒能力整段下线，代码回简。**
> 不再尝试把深睡的笔用软件叫醒 —— 这是**原厂固件本身就没实现**的能力，官方语义
> 就是「充满断电 → 笔深休眠 → 重新吸附唤醒」。当晚的三层实测结论（保留在
> `module/service.sh` 的「深睡唤醒守护：已整段移除」注释块里）：
> ① **Profile/GATT 层无效**：`run_hidctl disconnect` 与 OEM `DISCONNECT_PENCIL` 都只让
>    HOGP 掉到 0，**ACL handle 始终没变**（23:04/23:16 实测）。根因是 ACL 上的 5 个
>    持有者里 `hid(49)`/`BatteryService(59)` 属于 `com.android.bluetooth` 内部，
>    App 层无从释放 ⇒ 笔固件感知不到"链路没了"，不醒；
> ② **单条 ACL 断开在这台机器上硬件不可达**：本板是 QTI 用户态 H4 架构，HAL 独占
>    `/dev/ttyHS0`，kernel 未注册 hci 设备（无 `/dev/hci*`、无 hcitool/hciconfig），
>    强写该 tty 只会打乱 HAL 的 H4 帧同步；
> ③ **只有重启整片蓝牙有效**（23:20 实测 ACL handle 换号、笔恢复），但它是全局 API，
>    连累平板上所有蓝牙设备 —— 拿不到"只重连笔"，代价与收益不成比例。
> **删除范围**：KernelSU 侧唤醒守护全套函数与变量、priv-app `PenHidCtl`（源码与
> 装载物全部移除）、Hook 侧的 `HAPTIC_REFRESH` 握手重放环路。`service.sh` 由
> 1940 行降到 1466 行。**保留**：吸附边沿连接、设置页断开、状态镜像、磁吸胶囊、
> 电量与充电监控等正常链路管理一律不动。
> 睡死的笔 → **重新吸附一次**。模块已实机重启验证（0.1.23 + Hook 4.8.0，
> `system_server stylus hooks installed` 判据在，作用域 8/8 与真源一致）。
>
> ~~**v0.1.22（2026-09-21）：锁屏期唤醒通路打通**~~ **【已被 v0.1.23 废弃】**
> 以下 v0.1.19–v0.1.22 的唤醒相关工作随本次减法下线，仅作历史决策留档。
>
> **v0.1.22（2026-09-21）**：**锁屏期唤醒通路打通** —— 自研 priv-app `PenHidCtl`
> 的 `PenHidService` 标上 `directBootAware`，锁屏（user 0 RUNNING_LOCKED）期 PMS
> 不再过滤它，唤醒守护的断/连不再依赖原厂 CoreService（非 DBA，锁屏期 `am` 必
> rc=255）。关键发现与改动：
> ① **这台 ROM 息屏就把 user 0 锁回**（推翻 v0.1.20"仅开机首解前"的假设），
>    v0.1.20 的锁屏闸门实际把每次息屏期间的守护全部挡死 —— 已删除；
> ② 这台 ROM 的 BluetoothHidHost 没有 1 参 `connect()/disconnect()` 隐藏方法，
>    **`setConnectionPolicy(FORBIDDEN/ALLOWED)` 就是踢链/回链触发器**（HOGP 2→0→2 实测）；
> ③ `run_hidctl` 升级为带回执的已验证调用（`penhid.result`，先删后收+轮询），
>    `am rc=0` 只代表投递成功、回执才代表做了；
> ④ PMS 对 /system 应用按 mtime 跳过重扫的坑（缓存时间戳与 overlay 呈现 mtime 逐秒
>    相同 ⇒ 重启仍跑旧版）：覆盖安装场景改走 `pm install -r`（UPDATED_SYSTEM_APP，
>    保留 PRIVILEGED 与白名单授权，且根治 App 进程打不开 overlay APK 的历史崩溃）；
> ⑤ 回链窗口 8s→25s（锁屏态实测回链 ~23s）；断链确认改双判据（镜像或蓝牙栈）；
>    abort 时恢复 ALLOWED，否则笔会被留在 FORBIDDEN 死态。
> **全部锁屏态实机验证通过**，含最严苛的"重启后 FBE 从未解锁"窗口（断链/回链 +
> 回执 + 0 崩溃）。模块 `0.1.22`，PenHidCtl `4.1.5`；Hook 无改动（仍 v4.7.0）。
> 详见 `docs/pen_wake_guard_silent_noop_20260921.md` §13。
>
> **v0.1.21（2026-09-21）**：唤醒守护**时序调参 + 循环后验证**，依据是 21:36 第一次真实
> 生产样本（用户报"解锁后笔有震动无笔画，过了一会正常"）。三处：
> ① **失连保持 3s → 5s**。实测"断链 → 笔出首个笔画"为 **3.9s**，3s 只留 0.37s 余量
> （`21:36:44.58` 断链 → `21:36:48.5` 出笔画 → `21:36:48.87` 重连），而 3s 本就是 E4
> 实测区间 2–5s 的**下沿** —— 一旦偏移就会退回"连上了但触控死"。
> ② **新增循环后验证** `verify_pen_awake`：重连后看笔尖节点 12s，落 `cycle OK` /
> `WARN cycle FAILED` / `WARN cycle UNVERIFIABLE` 三态日志。此前 `do_pen_wake_cycle`
> 返回 0 只代表"链路回来了"，**不代表笔能写**，白跑一趟完全不可见。**只记日志不改行为**：
> 无证据表明第二轮有用，且"验证窗口内静默"本身歧义（笔放一边不碰屏幕同样静默），
> 先让失败可见，按真实发生率再决定要不要加重试。
> ③ 冷却跳过不再无声（`in cooldown (Ns < 120s); skipping this undock`）。
> 同时在正常分支与 `cycle OK` 分支打上 `${n}s after undock`，用于标定
> `WAKE_QUIET_SECONDS=6` 是否偏短（当前唯一样本是 11s ⇒ 窗口内必然判"睡死"，
> 守护实际退化成每次离座都断链重连；待对照组数据）。
> 模块 `0.1.21`，`service.sh` md5 `35096ae29c37963d436a6951c240e5a9`；Hook 无改动（仍 v4.7.0）。
>
> **v0.1.20（2026-09-21）**：唤醒守护加**锁屏闸门**。原厂 `com.oplus.ipemanager/.btadsorb.CoreService`
> 在 manifest 里没有 `android:directBootAware` ⇒ user 0 尚未首次解锁时 PMS 拒绝投递
> （`am startservice --user 0` 返回 `rc=255`）。原实现会在这一瞬间把"本次离座会话的一次性武装"
> 消费掉，导致用户解锁后笔仍是死的。现在未解锁时**保持武装不消费**，解锁后自动补做。
> 模块 `0.1.20`，`service.sh` md5 `edc415fc96fd554ca002cbd2093dc9da`；Hook 无改动（仍 v4.7.0）。
>
> **v0.1.19（2026-09-21）**：**唤醒守护修复 + 默认开启。** 深睡笔取下后自动断链重连唤醒
> （触发器=笔自身 BLE 链路完整 link-down→link-up，实验定案见
> `docs/pen_wake_experiment_E0_E4_20260921.md`），重连后自动重放 inkdye 握手恢复书写震动
> （Hook v4.7.0 `PenHapticGatt.refreshSession`）。`action.sh wake` 为手动入口。
>
> ⚠️ **v0.1.18 的守护其实一次都没执行过**：笔尖 evdev 节点解析用 `/proc/bus/input/devices`
> 按空白分词找 `eventN`，而该文件写的是 `H: Handlers=event5 cpufreq`（第一个 handler 与
> `Handlers=` 粘连），正则永不命中；失败（rc=2）又被当成"跳过"，**日志有、动作无**。
> 连带两处：守护自己发的 `DISCONNECT_PENCIL` 会置上断开闩锁导致"断而不建"；且守护默认关闭。
> 三处均已修（详情与实机验证见 `docs/pen_wake_guard_silent_noop_20260921.md`）。
> 关闭：`sh action.sh wake-guard-off`。

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

> ⚠️ **已知阻塞性风险（2026-09-18 实机命中一次，已落降险）**：Hook 注入 system_server 会**偶发卡开机**
> —— 同配置多数开机正常，偶发卡死在开机动画且不自恢复。根因是 Vector 安装 hook 的
> `ThreadList::SuspendAll`（持独占 mutator 锁）与 system_server 主线程在 binder JNI 调用
> （`BatteryService.onStart → IHealth.update()`）退出处的重新加锁互等 → 死锁。
> **降险已部署（Hook APK v4.1.4）**：`install()` 改到守护线程里延迟 2500ms 执行，把 deopt 挪出启动最密的
> `startCoreServices` 窗口；**连续 6 次重启均正常开机**，日志可见 `install deferred by 2500ms` →
> `stylus hooks installed (startOtherServices=1 run=1)` → `deferred install done`。
> 这是**概率降低非根治**（底层 ART/framework 竞态无法从模块侧消除），仍需持续多刷回归。
> Vector 侧无「免 deopt」配置（已证伪）。诊断与方案见 `TODO.md` P0.5 与 `docs/install-vector-route.md` 踩坑表第 7、8 条。

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
