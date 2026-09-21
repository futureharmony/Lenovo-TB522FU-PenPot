# 手写笔「吸附充电 + 锁屏静置 → 离座不可用」复现与根因分析

日期：2026-09-21｜设备：Lenovo TB522FU（HA2MGBSJ）｜模块：tb522fu_pen_bridge v0.1.8 → v0.1.16 / Hook v4.6.0

> 🔴 **2026-09-21 11:31 状态：v0.1.9 修复已部署并重启，但本场景仍复现。**
> 修复逻辑本身有效（救助实验再次验证），却**从未被执行** —— 它挂在两个同时失效的触发
> 条件后面。完整分析见 **`pen_exp3_v019_failure_20260921.md`**（实验 3 失败分析）。
> 本文件 §5 的 N2/N3/N4 状态需按下述口径重新解读：
> **"已实施"≠"已生效"**。
>
> ✅ **2026-09-21 11:38 状态：方向闭合。**
> 救助后用户实测**全程不吸附即可书写**（744 条笔尖事件、含压力曲线与倾角，`physical_docked=0` 恒定）。
> ⇒ 笔**不需回座唤醒**；硬重连（`DISCONNECT → CONNECT`）**有效且充分**；缺的只是**触发条件**。
> 同名文档 §13.5 复核全部候选：**证伪 D4**（正常态亦静默 3.5 分钟，静默≠休眠）、
> **降级 D1b**（休眠不拆 BLE，GATT 探测可能照常成功）、**仍主推 D1a**、
> 新增治本向 **D5**（停充后周期性微脉冲维持笔的发射子系统清醒）。
>
> 🟢 **2026-09-21 11:44 状态：D1a 已选定并实施于 v0.1.10。**
> 离座边沿改用「**笔尖节点短窗口活性**」判据：栈真断 → 直接硬重连；栈报「已连接」
> （可能仍是僵尸）→ 用 `event5` 在 2 秒窗口内有无事件裁决。
> 实现细节、设备实测与遗留风险见 `pen_exp3_v019_failure_20260921.md` **§13.6**。
> **§5 的 N2/N3/N4 已由本方案取代** —— 下文保留其分析价值，但实现口径以 §13.6 为准。
>
> 🟠 **2026-09-21 12:22 状态：用户复查「还是不行」→ 找到 D1a 的第二层失效。**
> 12:21:57 那次真实离座，模块日志**整段静默 80 秒以上**：判据、重连、日志一个都没有。
> 根因：`$(pen_tip_event_node)` 的子壳永久卡在 `/proc/bus/input/devices` 的
> **无限超时 poll** 上（`fdinfo` 读偏移冻结在 `pos:1`，`syscall` 六次采样一字不变），
> 父壳永远等不到结果 —— **`( ... ) &` 的失败是静默的**，整条 D1a 被吃掉。
> 顺带抓到第二个真 bug：硬重连把链断掉后 `request_pen_connect` 被
> `lenovo_pen_user_disconnect_requested` 挡掉（Hook 对 DISCONNECT_PENCIL 是**两个
> 闩锁一起置 1**），**断而不建，比僵尸连接更糟**。
> 修复：v0.1.14（节点改扫 sysfs + 探针看门狗 + `rc=2` 改为失败保险向重连）→
> v0.1.15（同时清两个闩锁 + 诊断日志 + 节点重试）。
> 完整证据链、对照实验与验证见 `pen_exp3_v019_failure_20260921.md` **§15**。
> **当前已部署 v0.1.15，待用户重做实验 6。**
>
> 🟢 **2026-09-21 13:30 状态：实验 6 通过（用户反馈「一切都正常」）；问题性质已变更，残留为 13 秒重连窗口。**
> 多路仪器取证：笔尖 `event5` 产生 **4674 行**完整 NVTCapacitivePen 报文（`ABS_X/Y` +
> `ABS_DISTANCE` + `ABS_TILT_X/Y` + `BTN_DIGI` DOWN/UP 严格配对、共 **5 段**），
> `hid9` 同步产出 12 组笔键报文、`event4` 活跃 673 行 —— 与"画 → 按笔键 → 再画 →
> 手指划"的测试动作逐项吻合。**笔的物理层、驱动层、输入层全部正常。**
>
> **13:12 那次"还是写不出"重新定性**：模块当时做对了每一步，但
> **「取下笔 → 链路可用」= 13:12:25 → 13:12:38 = 13 秒**，用户在窗口内就验收了。
> 13:12:38 之后 `pen-bridge.log` 再无任何输出 ⇒ 13:24 的成功书写**全程无模块介入**。
>
> **`rc=143` 定案（D6 关闭）**：不是 `getevent` 的毛病，是 **`timeout` 自身的退出码竞态**
> —— 同一条命令、同一个节点，13:12 模块记录 `143`（128+SIGTERM），13:29 实测得到
> `124`（toybox 正常超时码）。v0.1.16 已改为**读输出而非退出码**，与该竞态彻底解耦。
>
> ⇒ **问题已从「取下笔写不出」变为「取下笔后要等约 13 秒」。** 这 13 秒里笔侧 BLE
> 唤醒占约 7 秒、软件压不掉，**唯一能抹平延迟的是 D5**（停充后微脉冲防休眠）。
> 完整证据链见 `pen_exp3_v019_failure_20260921.md` **§16**；新增待办 **D8/D9**。
> **当前设备跑 v0.1.15；v0.1.16 已改好待部署**（改动仅限判据内部，不改外层决策）。
>
> 🟡 **2026-09-21 12:1x 状态：D1a 通路审计完成，v0.1.13 已部署。**
> 部署 v0.1.10 后重启发现模块连续三次 `OEM CONNECT_PENCIL request failed`。审计结论：
> **该 ROM 的 IPeManager CoreService 与模块自带的 penhidctl 都不是 direct-boot-aware，
> 用户处于 `RUNNING_LOCKED` 时 PMS 会过滤其组件，`am` 一律报 `not found`**（A/B 已证）。
> 但 `RUNNING_LOCKED` **只存在于「开机 → 首次解锁」之间**，解锁过一次后即使关屏也保持
> `RUNNING_UNLOCKED` ⇒ **用户复现路径全程在解锁态，D1a 主路径不受影响**。
> 仍据此把硬重连改成「**先尝试 OEM 投递、真投不出去才挂起**」，并在首次解锁后自动补做；
> 顺带修掉缺陷 B（开机 OEM 会话恢复的 3 次预算会在未解锁窗口被烧光）。
> 完整证据与代码改动见 `pen_exp3_v019_failure_20260921.md` **§14**。

用户实测现象：

> 吸附 dock 充电锁屏一会儿后，把笔拿下来，解除锁屏，笔无法使用；**必须重新吸附才能再使用**。

**结论：是，与既有审计的 §C（`powered_down` 误判）同源，但 `powered_down` 只是其中一环。**
真正让笔"拿下来也用不了"的是**三条独立链路同时失效**，其中一条是**本轮新发现的严重缺陷**（`penhidctl` 100% 崩溃）。

---

## 1. 关键结论速览

> ⚠️ **2026-09-21 11:12 实测补遗（见 §1.5）已修正本表：L1「笔掉电」被证伪（笔全程活着），
> L2「僵尸连接」被确认为**唯一**的硬阻塞，且已实测出有效解法。**

| # | 环节 | 状态 | 属于 |
|---|---|---|---|
| L1 | 笔固件在充电座满电静置后掉电 / 停播 | ❌ **已证伪**：11:12 破僵尸后 GATT 全链路重建，读到固件 `PARKER-V3 V1.26` / 序列号 `HVE70MYJ` / 电量 100% | 非本模块 |
| L2 | 蓝牙栈**僵尸连接**：`hogp connection state=2` 滞留 + 底层 LE ACL 不释放 → **拒绝一切重连** | ✅ 实证（**根因**） | Android 蓝牙栈行为 |
| L3 | **`penhidctl` 每次拉起即崩溃**（APK 在 App 进程打不开）→ 唯一可主动 `disconnect()` 的通道失效，**无法破僵尸** | ✅ 实证 | **我方缺陷** |
| L4 | `powered_down=1` 使 `real_bt_connected()` 短路 → 镜像与栈状态**双向分歧**，两边都修不好 | ✅ 实证 | 我方判据（§C） |
| L5 | hook 明知 `live uhid link=false` 仍上报 `connected`（`pen link state disagrees ... reporting connected`） | ✅ 实证，**新发现** | 我方缺陷 |

L1 提供"笔为何失联"的前提；**L2+L3 让"主动恢复连接"整条路径不可用**；L4 关掉了"系统自动恢复"的兜底。
唯一剩下的恢复手段就是 L1 的反向操作——**重新吸附上电**，与用户观察完全一致。

---

## 1.5 2026-09-21 11:00–11:12 实测：僵尸连接是唯一硬阻塞，先断后连 6 秒可破

### 1.5.1 失败窗口的完整因果链（复现用户第 4 步）

用户操作：吸附 → 锁屏 → 等 2 分钟 → 解锁 → 拿起笔书写 → **写不出来**。

| 时刻 | 事件 | 来源 |
|---|---|---|
| 10:57:04 | 重新吸附，`Hall docked=1`；10:57:08 起 `charging=1` | pen-bridge.log |
| 10:57:18 | `real BT state mirror connected=1` —— 笔上电，链路建立 | pen-bridge.log |
| **10:59:00** | **`charging=0`** —— 笔报满电，经 ASK 包令 CPS 停充 → 驱动关 TX | pen-bridge.log |
| **11:00:50** | **`connected=0 (was 1)`** —— 笔失去载波，物理链路断 | pen-bridge.log |
| 11:02:07 | `PEN_FRAMEWORK uevent ... attached=0 physicalDocked=1` | hook |
| **11:02:08.979** | `Hall observer synced from settings docked=0 -> hall=1` | hook |
| **11:02:08.980** | `NVT pen touchpad enabled devices=1` | hook |
| **11:02:08.989** | `pen input devices enabled count=2` | hook |
| **11:02:08.989** | **`pen input gate -> true (docked=0 disconnect=false hid=true)`** ← **门正常重开** | hook |
| 11:02:09.406 | `stock CONNECT_PENCIL selected pen MAC=DC:EB:4D:06:77:DD` | hook |
| **11:02:09.528** | `BluetoothHidHost.connect(XX:XX:XX:XX:77:DD)` → **`E/HidHostService: Device XX:XX:XX:XX:77:DD not disconnected. state=2`** ← **被栈拒绝** | HidHostService |
| 11:02:10.178 | `BleManager.b GATT connect requested` | hook |
| **11:02:20.615** | `penhidctl` (PID 18189) 拉起 → **`Failed to open APK '/system/priv-app/aclpenhid/PenHidCtl.apk': I/O error`** → NPE 崩溃 | AndroidRuntime |
| **11:02:20.889** | `IPe state handoff delivered: connected=false` —— **GATT 10 秒超时，笔不响应** | hook |

**判定：门控（hook）在 11:02:08.989 已完全就绪，软件输入层无辜；
卡死的是 BT 链路重建 —— `connect()` 被僵尸 state=2 直接拒绝，
退化的 GATT 也超时，唯一能 `disconnect()` 的 `penhidctl` 又崩了。**

### 1.5.2 决定性实验：root 下发「先断后连」（零用户操作）

```
11:11:23  before  HOGP=2   xx:xx:xx:xx:77:dd : selected transport=2 hid connection state=0 hogp connection state=2
11:11:57  DISCONNECT_PENCIL
11:11:57.970  HidHostService: broadcastConnectionState transport=2 prevState=2 -> newState=3
11:11:58.006  HidHostService: broadcastConnectionState transport=2 prevState=3 -> newState=0   ← 僵尸破除
11:12:09  CONNECT_PENCIL
11:12:10.309  IPe OEM s0 services discovered  services=12 characteristics=36 notify=18 ... hid=true
11:12:11.197  IPe OEM metadata: type=SECOND_GENERATION_PENCIL_LITE firmware=PARKER-V3 V1.26 hardware=Lenovo Tab Pen serial=HVE70MYJ
11:12:12.094  IPe BLE hardware battery sample=100
11:12:12.811  IPe OEM s0 notify uuid=0000000a-... value=01 00 01 0d 00 00 00 00 1f b5 00 24 00 94 8d 00
11:12:13.432  real HID connected: pen input gate restored
11:12:13.928  IPe stock s0.V profile CONNECTED
11:12:14.037  ColorOS pen state synced: connected=true battery=100
```

**结论逐条：**

1. **僵尸连接可被 `DISCONNECT_PENCIL` 即刻破除**（`2 → 3 → 0`，<2s）。**N2 方案实证有效。**
2. **破除后 6 秒内 GATT 全链路真实重建**：服务发现 → 读固件/序列号/电量 → notify 正常 → `real HID connected`。
3. **L1「笔自行掉电」被证伪**：笔能应答、能报电量 100%、能回 notify ⇒ **笔一直活着**。
   11:02 那次失败不是"笔关机"，而是**幽灵 LE ACL 占住了笔，笔的笔尖/上行未激活，且主机侧所有重连都被拒**。
4. 二次 `CONNECT_PENCIL` 之所以在 11:02 失败、11:12 成功，**唯一差异就是 11:11:57 的那次 DISCONNECT**。

### 1.5.3 顺带发现的两处缺陷

- **L5（新）**：`pen link state disagrees: HID Host profile=true live uhid link=false; reporting connected`
  （10:48:56 与 11:12:13 各一次）——**内核 uhid 链路实际不在**，HID Host profile 却报 connected，
  hook 明知分歧仍上报 `connected` ⇒ `event8/9`（笔按键/手势）在该状态下不会工作。
- **判据分歧**：`dumpsys` 同一行同时给出 `hid connection state=0` 与 `hogp connection state=2`；
  且 `powered_down=1` 时 `real_bt_connected()` 短路为"未连接"，而栈实际持僵尸 state=2
  ⇒ **模块认为"没连"、栈认为"已连"，两边同时为假，谁都不会去修。**

### 1.5.4 复现实验的读取口径（踩坑记录）

- 该 ROM 的 `getevent` **不接受多个设备节点**：`getevent -lt ev5 ev8 ev9` → usage error 且 **exit 1**；
  单节点或 `-lt`/`-l`/`-t` 单独用都正常。**必须一事一进程。**
- `getevent` 重定向到文件**是行缓冲、会正常落盘**（触屏节点实测 15.8 KB），0 字节只代表"确无事件"。
- `am startservice` 以 **shell 身份**发 OEM pen action 会被 `com.oplus.permission.safe.IOT` 拒绝；
  **必须 `su -c`**。模块自身以 root 运行，故不受影响。
- `real_bt_connected()` 的 `tail` 由 `tail=${mac#*:*:*:*:}` 得到，是 **`77:dd`（带冒号）**；
  写成 `77dd` 会永远匹配不上，把"已连接"误判成"未连接"。自查探针时须对齐。

### 1.6 用户实测确认（11:18）：笔**全程未吸附**即可书写 → L1 彻底证伪，并定位模块自恢复失效的代码级原因

**用户操作反馈**：写出字来了；且整个实验期间笔**从未放回吸附位**。

**状态日志佐证**：`physical_docked=0` 自 11:11:13 到 11:18:23 共 196 个 1 秒采样**恒定不变**，
即本次恢复**没有任何吸附/无线充参与**。

**原始事件铁证**（`/dev/input/event5` = `NVTCapacitivePen` 面板数字笔节点）：

```
[ 3133.795067] EV_ABS ABS_X / ABS_Y / ABS_TILT_X / ABS_TILT_Y / ABS_DISTANCE
[ 3133.795067] EV_KEY BTN_DIGI DOWN
...
[ 3361.594250] EV_KEY BTN_DIGI UP / EV_SYN SYN_REPORT
```

- 3822 条事件 / 227.8 秒，含 `ABS_PRESSURE`×481、`ABS_TILT_X`×224、`BTN_DIGI`×14、`BTN_TOUCH`×16
  ⇒ 位置 / 压力 / 倾斜 / 落笔抬起**全部齐全**，是完整书写而非悬停
- 内核时间锚点换算（`offset = wall_epoch - uptime`）：`t=3133.795 → 墙钟 11:14:06`，而**救助发生在 11:12:13**
- 同期 `event8/9`（BT HID 笔设备）= **0 字节**，与 §1.5.3 的 L5 相互印证

**结论**：`DISCONNECT → CONNECT` 之后，笔在**未吸附、未上无线充**的前提下恢复了完整书写能力。
⇒ **L1「笔固件掉电、只能靠线圈唤醒」被彻底证伪**。笔从未真的关机，卡的只是**链路重建**。

#### 1.6.1 新发现：模块自身的自动恢复被一行短路判据废掉（代码级铁证）

`module/service.sh:1049` 的 `real_bt_connected()`：

```sh
case "$(settings get global lenovo_pen_powered_down)" in
    1) return 1 ;;          # ← 不读蓝牙栈，直接判「未连接」
esac
```

而 `request_pen_connect_bounded()`（`service.sh:1383`）**正是拿它当重连成功判据**。实测（`pen-bridge.log`）：

```
11:12:14 HID connect service requested
11:12:17 explicit pen HID retry attempt=1
11:12:20 explicit pen HID retry attempt=2
11:12:23 explicit pen HID retry attempt=3
11:12:23 explicit pen reconnect window ended without HOGP     ← 宣告失败
11:12:23 real BT state mirror connected=0 (was 1)             ← 于是把镜像清成「未连接」
```

**而 `dumpsys` 从 11:12:14 起 `hogp connection state=2` 就已经成立**（`cap_state.log` 同步记录）。
即：**链路已经通了，模块却报「没通」，还把镜像清成 0。**

**为什么这是个自锁环**：

| 步 | 事件 |
|---|---|
| 1 | `pen-revive-guard.sh` 置位条件：`hall=docked` 且 CPS TX 关闭 ≥ `POWERED_DOWN_AFTER_SEC` → `powered_down=1` |
| 2 | **唯一**解除条件：`tx=1 \|\| online=1`（笔回到线圈） |
| 3 | 离座分支**故意不改标志**（`pen-revive-guard.sh:182-193` 注释明确写了） |
| 4 | ⇒ 笔一旦带着 `powered_down=1` 离座，**该标志在结构上再也无法更新** |
| 5 | ⇒ `real_bt_connected()` 恒假 ⇒ 自恢复确认永远失败 + 镜像永远报「未连接」 |

自锁环：`powered_down=1` → 判「未连接」→ 不更新 `powered_down` → 永远判「未连接」。
原设计的注释写着「笔离座后按固件行为本就自行关机，保持标志=1 是诚实的」——**这个前提今天被用户实测证伪**。

**连锁影响**（`service.sh:1148-1151`）：

```sh
pen_in_use=0
[ "$connected" = 1 ] && [ "$docked" != 1 ] && pen_in_use=1
settings put global settings_enable_oppo_pencil "$pen_in_use"
settings put global ipe_pencil_present "$connected"
```

镜像为 0 ⇒ 模块持续向 ColorOS 宣告「笔不存在」⇒ `settings_enable_oppo_pencil=0`（笔 120Hz 投票关闭）、
`ipe_pencil_present=0`、`ColorOS pen state synced: connected=false battery=-1`。

**影响面**：`HookUtils.linkConnected()` 在 `system_server`/`android` 进程读**真实蓝牙栈**，
但在普通 app 进程（如 `com.coloros.note`）读的是 `lenovo_pen_link_connected` **镜像** ⇒ 镜像写 0 会直接污染 app 侧笔状态。

#### 1.6.2 已实施的修复（模块 v0.1.9）

| 编号 | 改动 | 位置 |
|---|---|---|
| **F-A** | 拆出 `bt_stack_hogp_live()`（纯栈读取）；`real_bt_connected()` 的 `powered_down` 短路**限定在 `physical_docked=1` 时**才生效 | `service.sh:1046-1099` |
| **F-B** | 新增 `request_pen_reconnect_hard()`：`DISCONNECT` → 轮询等 `HOGP` 落下（≤6s）→ 清自造闩锁 → `CONNECT` | `service.sh:1465` |
| **F-C** | 离座边沿：若 `left_powered_down=1`（= 笔是在座上满电静置后被拿走的，**失败场景指纹**）走 F-B；否则维持原普通路径 | `service.sh:1315-1332` |
| **F-D** | `request_pen_connect_bounded()` 三次普通 `connect` 耗尽后，兜底追加一次 F-B | `service.sh:1436-1441` |

- **F-A 修「假阴性」**（连上了却报没连）；
- **F-C 修「发错原语」**（11:02 其实**发过** connect，只是发的是普通 connect，被僵尸 `not disconnected. state=2` 拒绝）；
- F-B 的 `bt_stack_hogp_live` 采用「HOGP 是否落下」作为僵尸是否破除的判据，避免沿用被污染的 `real_bt_connected`。

**未做（保留观察）**：F-A 之后 `powered_down` 在离座期间仍为 1，但对 `real_bt_connected()` 已无影响；
`pen-revive-guard.sh` 的「离座保持标志」策略暂不改动，避免同时变动两处引入新变量。


---

## 2. 完整时间线（全部来自设备日志，可复现）

| 时刻 | 事件 | 来源 |
|---|---|---|
| 10:22:27 | 开机；`powered_down=0`、`link_connected=1` | pen-bridge.log |
| 10:29:43 | **吸附**，驱动开 TX | pen-revive.log |
| 10:30:35 | **100% 满电，驱动自行关 TX**（`手写笔已充满，已停止充电`） | charge-guard.log |
| 10:32:14 | `tx off 91s >= 90s` → **`lenovo_pen_powered_down=1`** | pen-revive.log |
| 10:32:33 | **镜像翻**：`real BT state mirror connected=0 (was 1)` → `present=0 / battery=-1 / link=0` | pen-bridge.log |
| — | （锁屏静置约 8 分钟） | — |
| 10:40:37 | 按键唤醒（`KEYCODE_WAKEUP`，`screenOn=false→true`） | KEYLOG |
| 10:40:37.821 | 蓝牙栈：`oplus_hh_le_on_screen_on: pensearch, **no device waiting for screen on, skip**` | oplus_bta_hh_le |
| 10:40:39 | **拿笔**（hall undocked）；Java 侧 **`pen input gate -> true (docked=0 disconnect=false hid=true)`** | LenovoPenBridge |
| 10:40:39.778 | `BluetoothHidHost(14301).connect(...)` → **`E/HidHostService: Device XX:..:77:DD not disconnected. state=2`** ← **僵尸连接拒绝** | BluetoothHidHost |
| 10:40:40.429 | `IPe BleManager.b GATT connect requested` | LenovoPenBridge |
| 10:40:40.7 | `run_hidctl connect` 拉起 `penhidctl` → **进程崩溃 #1** | ActivityManager |
| 10:40:41.8 | 自动重启 → **崩溃 #2** | ActivityManager |
| 10:40:54 | **用户重新吸附** | pen-bridge.log |
| 10:40:55 / 10:40:56 | 再次拉起 → **崩溃 #3 / #4** | ActivityManager |
| 10:41:01 | `pen docked: tx=1 online=1` → **`powered_down=0 (was 1)`**（唯一解除条件） | pen-revive.log |
| 10:41:06 | `real BT state mirror connected=1` —— 恢复 | pen-bridge.log |

**链路已闭合**：拿笔后所有主动恢复路径都失败，只有重新吸附（10:40:54）才把状态拉回。

---

## 3. 逐条取证

### L2 僵尸连接（直接拒绝重连）

```
10:40:39.778 D/BluetoothHidHost(14301): connect(XX:XX:XX:XX:77:DD)
10:40:39.778 E/HidHostService( 4593): Device XX:XX:XX:XX:77:DD not disconnected. state=2
```

`BluetoothHidHost.connect()` 的前置条件是 profile **不是已连接态**；栈认为 `HOGP=2`（仍连接），于是**直接拒绝**，不发任何 LE 连接请求。
而 `HOGP=2` 在这支笔掉电后**不会自行清零**——这正是源码注释里反复提到的"僵尸连接"。

**当前代码没有任何"先断开再连接"的动作**：`request_pen_connect()`（`service.sh:1349`）只做去重后发 CONNECT_PENCIL，不先 disconnect。

### L3 `penhidctl` 100% 崩溃（新发现，我方缺陷）

```
拉起次数(Start proc com.aclaniakea.penhidctl): 4
崩溃次数(Process: com.aclaniakea.penhidctl): 4
```

崩溃原文：

```
W iakea.penhidctl: Unable to open '/system/priv-app/aclpenhid/PenHidCtl.apk' : No such file or directory
E iakea.penhidctl: Failed to open APK '/system/priv-app/aclpenhid/PenHidCtl.apk': I/O error
E ResourcesManager: failed to add asset path ...
E AndroidRuntime: java.lang.NullPointerException:
    Attempt to invoke virtual method 'java.lang.String[] android.content.res.Resources.getStringArray(int)'
    on a null object reference
```

**矛盾点已排查**：

| 检查 | 结果 |
|---|---|
| `ls -la /system/priv-app/aclpenhid/`（shell） | ✅ 可见，16785 B |
| `wc -c < .../PenHidCtl.apk`（shell） | ✅ 16785 |
| `su -c wc -c < ...`（root） | ✅ 16785 |
| `ls -laZ` SELinux 标签 | ✅ `u:object_r:system_file:s0`（正确） |
| `pm path com.aclaniakea.penhidctl` | ✅ 返回该路径 |
| privapp 权限白名单 | ✅ 存在（BLUETOOTH_CONNECT/SCAN/PRIVILEGED/WRITE_SECURE_SETTINGS） |
| **App 进程（`u:r:priv_app:s0`）打开 APK** | ❌ **No such file or directory** |

shell/root 都能读、SELinux 标签正确、whitelist 存在，**唯独 App 进程打不开** → 该 APK 由**模块 system overlay** 提供（`/data/adb/modules/tb522fu_pen_bridge/system/priv-app/aclpenhid/`），**App 进程的 mount namespace 里没有这份 overlay**。

后果：`run_hidctl connect` / `run_hidctl disconnect` 是**唯一能程序化调用 `BluetoothHidHost` 的通道**，它 100% 崩溃 ⇒
- 无法主动 connect（L2 的补充手段没了）
- **无法先 disconnect 破除 L2 的僵尸连接**（关键）

### L4 `powered_down` 关掉系统自动恢复

```
10:40:37.821 oplus_bta_hh_le: oplus_hh_le_on_screen_on: pensearch,
             screen turned on, g_waiting_for_screen_off = false
10:40:37.821 oplus_bta_hh_le: ... no device waiting for screen on, skip
```

亮屏时蓝牙栈会重连"因息屏断开、正等待亮屏重连"的设备；此处**队列为空**（`no device waiting for screen on`）⇒ 系统侧的重连**根本没覆盖这支笔**。

同时 `powered_down=1` 使 `real_bt_connected()`（`service.sh:1056`）**最优先短路返回"未连接"**，
导致离座期间（10:32:14 → 10:41:01，共约 9 分钟，**含拿笔后的那 22 秒**）：
- `monitor_real_bt_state` 把 `lenovo_pen_link_connected` 钉在 0（即使真实链路已恢复也报 0）
- `request_pen_connect_bounded` 的"确认成功"分支 `if real_bt_connected; then ... exit 0` **永不成立**，3 次重试必然耗尽
- `ipe_pencil_present=0 / battery=-1` → OEM/IPe 及所有消费者看到"笔不存在"

### 附：一处语义冲突（供参考）

同期系统的设备卡片仍认为笔在线：

```
10:40:39.428 mydevices[core]: type=PENCIL, name=Le**ro 2, mac=DC:**:**:77:DD,
             connectState=CONNECTED, online=true, battery=SINGLE:100
```

即"系统的 mydevices 说 CONNECTED"与"我们镜像的 `link_connected=0 / present=0`"**互相矛盾**，
说明 L4 的镜像翻转与 OEM 自身认知不一致，属于需要解耦的耦合。

---

## 4. 为什么"重新吸附"能修好

吸附边沿做了三件拿笔时做不到的事：

```sh
# service.sh monitor_hall_capsule，state=1（吸附）分支
settings put global lenovo_pen_disconnect_requested 0      # 清断开闩锁
settings put global lenovo_pen_user_disconnect_requested 0
if ! real_bt_connected || ! pen_cps_responsive; then
    request_pen_connect                                       # 强制重连
fi
```

1. **TX 上电** → 给笔一个物理唤醒脉冲 ⇒ 笔重新广播/响应（**L1 的反向操作，唯一真正有效的一步**）
2. 笔重新广播后，蓝牙栈**从笔侧**建立新链路，`HOGP` 脱离 `state=2`（**绕开 L2**）
3. `tx==1` 命中 `pen-revive-guard` 唯一解除条件 ⇒ `powered_down=0`（**解开 L4**）

因此现象是"必须重新吸附"——不是吸附本身有魔力，而是**它是当前唯一能产生唤醒脉冲的路径**。

---

## 5. 修复建议

**优先级（依 §1.5 实测重排）：**

| 序 | 项 | 理由 |
|---|---|---|
| 1 | **N2 先断后连** | 已实证 **6 秒内可破僵尸并重建全链路**，是唯一直接命中根因的改动 |
| 2 | **N1 让 `penhidctl` 可用** | N2 的**前置条件**：`disconnect()` 通道只有它；它崩 ⇒ N2 无法实现 |
| 3 | N3 / N4 | 解耦 `powered_down`、离座清标志，消除"两边都以为对方该修"的分歧 |
| 4 | **N6（新）修正 uhid 分歧上报** | 见 L5：`live uhid link=false` 时不应上报 `connected`，否则笔按键/手势静默失效 |
| 5 | N5 唤醒脉冲 | **降级待定**：L1 已证伪（笔不掉电），临场不再需要 |

### N1【高】让 `penhidctl` 真正可用（部署方式改造）

问题不是 APK 内容，而是**它放在依赖 overlay 的 `/system/priv-app`，App 进程 ns 看不到**。
两者择一：

- **方案 A（推荐，最稳）**：改由 `service.sh` 在首次运行时 `pm install` 到 `/data`
  （APK 源文件放模块目录 `/data/adb/modules/tb522fu_pen_bridge/`），再 `pm grant` `BLUETOOTH_CONNECT`/`BLUETOOTH_SCAN`。
  普通应用即可调用 `BluetoothHidHost`（API 12+ 只需 `BLUETOOTH_CONNECT`）。`system/priv-app` 的 overlay 副本可保留作后备。
- **方案 B**：保留 priv-app，但确认 KSU 模块的 mount 模式能否让 zygote/App ns 看到 overlay（需查 `ksud` 的挂载策略）；
  或在 `service.sh` 里显式把 APK `cp` 到 `/data` 再 `pm install -r`，绕开 overlay ns 差异。

**验收判据**：`logcat | grep 'service start action='` 出现且进程存活 ≥5s、**不再有** `Process: com.aclaniakea.penhidctl` 崩溃。

### N2【最高｜已实证｜⚠️ 已实施 v0.1.9 但**未生效**】破除僵尸连接：重连前先"断开"

> 🔴 **11:31 实测：本项从未被执行。** 硬重连被挂在 `left_powered_down=1` 与
> `! real_bt_connected` 两个条件后面，本次二者**同时为假** ⇒ 零动作。
> 详见 `pen_exp3_v019_failure_20260921.md` §2。
> **结论不变**：动作本身有效（11:34 救助再次证实），缺的是**触发条件**。

> **2026-09-21 11:12 实测通过**（§1.5.2）：root 下发 `DISCONNECT_PENCIL` → `HOGP 2→3→0`（<2s）；
> 再 `CONNECT_PENCIL` → **6 秒内**服务发现 / 固件读取 / notify 全部恢复，`real HID connected`。
> 二次 `CONNECT` 在 11:02 失败、11:12 成功，**唯一差异就是中间那次 DISCONNECT**。
> **11:18 用户实测确认**：破僵尸后笔在**全程未吸附**的前提下恢复书写（§1.6）。
> **实施**：`request_pen_reconnect_hard()`（见 §1.6.2 的 F-B / F-C / F-D）。

针对 `E/HidHostService: Device ... not disconnected. state=2`：

```
离座/重连时：disconnect(profile HID_HOST) → 等 1~2s → connect()
```

- 前提是先修好 N1（否则没有可用的 disconnect 通道）
- 或走 OEM 路径：先 `DISCONNECT_PENCIL`，等 2s，再 `CONNECT_PENCIL`
- 并给 `request_pen_connect` 增加"若栈报 `not disconnected. state=2`，则降级为先断开再连"的分支

### N3【中｜✅ 已实施 v0.1.9｜⚠️ 副作用已确认】解耦 `powered_down` 的两种用途（承接审计 §C.5 / F3）

> 🔴 **11:31 实测暴露负向副作用**：解耦后 `real_bt_connected()` 变成纯栈读，
> 而僵尸连接下栈自报 `HOGP=2` ⇒ 判据恒真 ⇒ 离座边沿的 `elif ! real_bt_connected` 永不成立。
> **旧代码靠 `powered_down` 短路的"歪打正着"被移除，反而少了一次尝试。**
> ⇒ 解耦本身正确（镜像不再撒谎），但**必须有新的活性判据补位**（见失败分析 §10 D1）。

`real_bt_connected()` 恢复为**纯蓝牙栈判定**；`powered_down` 只用于"吸附边沿是否需要强制重连"。
`link_connected` / `ipe_pencil_present` / `ipe_pencil_battery_level` 交由真实链路事件驱动。

**实施方式（比原提议更保守）**：没有直接删除短路，而是**限定作用域** —— `powered_down` 只在
`lenovo_pen_physical_docked=1` 时参与判定（因为置位与解除条件都要求 `docked=1`）。
这样既保留"笔在座上已关机时不要误报已连接"的原意，又消除了 §1.6.1 的自锁环。
见 `service.sh:1088-1099`（F-A）。

### N4【中｜❌ 已证伪（原设计）】离座边沿处理 `powered_down`（承接审计 F4）

> 🔴 **"`powered_down=1` 是失败场景指纹"这一前提已被证伪**：其置位需 90 秒静置，
> 而本次失败只需 **47 秒**；`pen-revive.log` 显示离座时 `tx off 45s/90s`，标志仍是 0。
> ⇒ 以它为门控的硬重连路径**结构上会漏掉快节奏复现**。见失败分析 §3、§4。

笔被拿起 = 用户要用 ⇒ 立刻清标志，让系统把笔当"存在"处理，恢复亮屏自动重连与镜像。
原设计担心的"拿起→放回时标志=0 导致重连被僵尸连接吃掉"，改由 **N2 的先断后连**解决，不再需要靠标志。

**本轮实际做法（F-C）**：**不清标志**，而是把它当作"失败场景指纹"来用 —— 离座瞬间
`powered_down=1` 恰好标识出「笔是在座上满电静置之后被拿走的」，正是会产生僵尸连接的那条路径，
于是该路径直接走硬重连。这样避免同时改动 `pen-revive-guard.sh` 与 `service.sh` 两处策略。
N3 落地后标志已不再污染 `real_bt_connected()`，因此保留它不再有害；
若后续要恢复 app 侧镜像的完整性，可再按原提议在离座边沿清标志。

### N5【降级：待定】主动唤醒脉冲

原假设是"笔固件掉电、只能靠 TX 脉冲唤醒"。但 §1.5.2 已**证伪 L1**（笔全程活着，破僵尸后即可重建链路），
故本项**不再是必要路径**。仅当 N1~N4+N6 全部落地后仍复现"必须重新吸附"时才回头验证。

### N6【中｜新】修正 `uhid` 分歧上报（对应 L5）

```
pen link state disagrees: HID Host profile=true live uhid link=false; reporting connected
```

当前逻辑在 `live uhid link=false` 时**仍然上报 `connected`**，导致：
- 上层认为笔在线 ⇒ 不做恢复
- 但内核 uhid 输入链路不在 ⇒ `event8/9`（笔按键 / Consumer Control）**静默失效**，用户按笔键无反应

**建议**：把 `connected` 上报条件改为「HID Host profile=connected **且** live uhid link=true」；
若仅 profile 为真而 uhid 为假，走 **N2 的先断后连**重建，而不是就地宣告成功。


---

## 6. 实验进度

| 实验 | 目的 | 状态 |
|---|---|---|
| 实验 1（软件，root） | 验证 L2 僵尸可破、N2「先断后连」可行 | ✅ **2026-09-21 11:12 完成并通过**（见 §1.5.2）：`DISCONNECT` → `HOGP 2→0`；`CONNECT` → 6 秒内 GATT 全链路重建 |
| 实验 1b（用户手写） | 验证破僵尸后**不吸附**即可书写（即 L1 真伪） | ✅ **2026-09-21 11:14–11:18 完成并通过**（见 §1.6）：`event5` 3822 条书写事件（含压力/倾斜），`physical_docked=0` 恒定 196 采样 ⇒ **L1 证伪** |
| 实验 2（需手） | 验证 N1 落地后 `penhidctl` 存活 | ⏳ 待 N1 实施 |
| 实验 3（需手） | 验证 v0.1.9 在**真实复现路径**上自动恢复：吸附 → 满电静置（TX 关）→ 锁屏 ≥2 分钟 → 解锁拿笔 → **直接书写**，不再需要重新吸附 | ❌ **2026-09-21 11:31 执行，仍失败** —— 修复未被触发（`powered_down` 未到 90s 阈值 + 僵尸使判据恒真 ⇒ 离座边沿零动作）。详见 `pen_exp3_v019_failure_20260921.md` |
| 实验 3b（软件，root） | 失败现场救助：确认笔是否可被蓝牙唤醒 | ✅ **11:34 完成并通过**：`DISCONNECT` → `HOGP 2→0`；`CONNECT` → 6 秒内真实重建（GATT 读写 + 笔 notify 回包 `01 00 01 0d …`）⇒ 笔活着，修复动作有效，缺的只是触发 |
| 实验 3c（需手） | 救助后不吸附直接书写 —— 区分"笔尖无信号"与"信号被下游吞" | ✅ **2026-09-21 11:38 完成并通过**：`event5` 新增 744 条笔迹事件（`ABS_PRESSURE` 113 值 / `ABS_TILT` 84 值），`physical_docked=0` 全程恒定 ⇒ **笔不需回座唤醒，硬重连有效且充分**。详见 §13 |
| 实验 4（软件，root） | 休眠态下发起 GATT read 探测，判定 D1b 是否可行（§13.4(b) 存疑：休眠不拆 BLE，read 可能照常成功） | ⏳ 待休眠态现场 |
| 实验 5（软件，root） | 休眠态下发一次极短 TX 脉冲，验证 D5（线圈激励能否唤醒笔的发射子系统） | ⏳ 待休眠态现场 |
| 实验 6（需手） | 验证 **D1a（v0.1.15）** 在真实复现路径上自动恢复：吸附 → 满电静置（TX 关）→ 锁屏 ≥2 分钟 → 解锁拿笔 → **直接书写**。验收：模块日志出现 `pen tip silent for 2s after undock; assuming sleeping emitter`（或 `tip probe: …` 诊断行）并随即硬重连；书写成功且**不需重新吸附** | ❌ **12:22 用户复查失败** —— 不是判据错，而是判据所在的子壳**静默死锁**（§15.2），整条 D1a 零输出。⏳ **待重做**：v0.1.14（sysfs + 看门狗）→ v0.1.15（双闩锁 + 诊断 + 重试）已部署重启，见 §15 |
| 实验 6b（需手，反例） | 对照：正常态拿笔立即书写，应看到 `pen tip alive after undock; link kept`、**不触发重连** | ⏳ 待执行（与实验 6 同批） |
| 实验 6c（软件） | 验证 D1a 的 `rc=0` 分支（有事件时 `getevent` 返回 0）。因内核 6.6.82 下 `sendevent` 注入不被广播，只能靠真实书写观察 | ⚠️ 随实验 6/6b 一并确认 |
| 实验 6d（软件） | 验证缺陷 B 修复的**交付侧**：首次解锁后 ≤30 秒，日志应出现 `live HOGP missing OEM haptic session; recovery requested attempt=1/3`（此前会一直等到「下一次真实蓝牙边沿」） | ⏳ 待用户解锁 |

**实验 3 的判别口径：**

- `exp2/cap_ev5.log` **有事件** ⇒ 笔尖信号到达面板 ⇒ 11:02 的"写不出来"确由 L2 僵尸导致 ⇒ **N2 即为完整修复**。
- `exp2/cap_ev5.log` **无事件** ⇒ 笔尖另有门控/供电问题 ⇒ 需继续查 `NVTCapacitivePen` 的 enable 与笔固件侧笔尖激活条件。

> 采集脚本 `/data/local/tmp/exp2/capture_v2.sh`（1200 s，含 `getevent` 四节点 + `logcat` + 状态/HOGP/DEVIN 轮询）、
> 救助脚本 `/data/local/tmp/exp2/rescue_test.sh`（root 执行）。
> 探针口径与踩坑见 §1.5.4。


---

## 7. 复现命令

```sh
ADB=/opt/homebrew/bin/adb
M=/data/adb/modules/tb522fu_pen_bridge

# 现状快照
for k in lenovo_pen_powered_down lenovo_pen_link_connected lenovo_pen_physical_docked \
         ipe_pencil_present ipe_pencil_battery_level ipe_pencil_charging_state; do
  printf '%-34s = %s\n' "$k" "$($ADB shell "settings get global $k" | tr -d '\r')"
done

# 僵尸连接（拿笔后必须看这条）
$ADB logcat -d -v time | grep -E "HidHostService|BluetoothHidHost.*connect"

# penhidctl 崩溃
$ADB logcat -d | grep -cE "Process: com.aclaniakea.penhidctl"
$ADB logcat -d | grep -E "Failed to open APK.*PenHidCtl"

# 亮屏自动重连是否覆盖笔
$ADB logcat -d | grep "oplus_hh_le_on_screen_on"

# 输入门控（证明 NVT 侧不是瓶颈）
$ADB logcat -d -s LenovoPenBridge | grep "pen input gate"

# 三个日志
$ADB shell "su -c 'tail -40 $M/pen-revive.log'"
$ADB shell "su -c 'tail -30 $M/charge-guard.log'"
$ADB shell "su -c 'tail -40 $M/pen-bridge.log'"
```

---

## 8. 与既有审计的对应关系

| 本文条目 | 既有审计编号 | 说明 |
|---|---|---|
| L4 | §C / C.5 | `powered_down` 误判与"两种用途耦合" |
| N3 | F3 | 解耦 `powered_down` |
| N4 | F4 | 离座清标志 |
| L2 / L3 | **新增** | 僵尸连接 + `penhidctl` 崩溃，本轮首次定位 |
| N1 / N2 | **新增** | 对应 L3 / L2 的修复 |

`docs/pen_charge_sleep_bug_audit_20260921.md` 的 §E 判别实验（拿笔写字）在本轮已由用户实测给出方向性答案：
**拿笔后写不出字**，且日志显示 `pen input gate -> true`（输入设备已使能）⇒ 瓶颈不在输入层。
§1.5 进一步实测判定：瓶颈在 **BT 链路重建被僵尸连接拒绝（L2）**，
**不是**先前推测的 L1（笔掉电）—— 该假设已于 §1.5.2 被证伪。
