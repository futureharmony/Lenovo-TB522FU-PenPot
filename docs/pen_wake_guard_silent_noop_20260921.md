# 唤醒守护「静默空转」定位与修复（v0.1.19 → v0.1.20，2026-09-21）

> **一句话**：项目声称已用 BLE 重连解决「dock 睡死 → 取下不可用」，但**即使守护开关打开，
> 那段重连代码也从未执行过** —— 一个 `/proc/bus/input/devices` 的解析写法在本机永远匹配不到，
> 而失败是**静默**的。修完（v0.1.19）再做重启验证时，又挖出**第四处**同类静默空转：
> 开机到首次解锁之间，原厂 `CoreService` 因缺 `directBootAware` 被 PMS 拒绝投递，
> 而守护会在这一瞬间把「本次离座会话的一次性武装」直接烧掉（v0.1.20 修复）。

## 1. 用户报告（2026-09-21 21:0x）

> 笔放在 dock 栏彻底睡死，拿下来后震动可用，但**笔画仍然无法使用**；有时甚至连震动都无法使用；
> **必须重新放到 dock 栏上连接一次、再断连**才能正常使用。

对照既有结论（`docs/pen_wake_experiment_E0_E4_20260921.md` §3/§3b）：触发器是笔自身 BLE 链路的
完整 `link-down → link-up`，且重连后必须重放震动握手 —— 实现（v0.1.18）在**代码结构上**与实验结论一致。
所以问题不在结论，在**执行**。

## 2. 设备侧证据：守护从未运行

```text
$ grep "wake guard" /data/adb/modules/tb522fu_pen_bridge/pen-bridge.log
...
20:21:26 wake guard: undocked with live link; listening for pen input (6s window)
20:21:26 wake guard: pen input node unresolvable; skipping     ← 同一秒，窗口根本没进
20:22:36 wake guard: undocked with live link; listening for pen input (6s window)
20:22:36 wake guard: pen input node unresolvable; skipping
20:23:18 ... 20:25:16 ... 20:25:22 ... 20:57:09 ... 21:00:49   ← 全部同一形态
```

两次日志**同一秒**出现，说明 `pen_input_silent` 的 6 秒窗口一秒都没走（走了必然跨 6 秒），
`resolve_pen_input_node` 是**立即失败**。7 次离座，7 次全灭。

## 3. 根因 1：节点解析永不命中（决定性）

`v0.1.17/v0.1.18` 的实现：

```sh
PEN_INPUT_NODE=$(awk '
    /^N: Name=/ { pen = tolower($0) ~ /pen/; next }
    pen && /^H: Handlers=/ {
        for (i = 1; i <= NF; i++)
            if ($i ~ /^event[0-9]+$/) { print "/dev/input/" $i; exit }
    }
' /proc/bus/input/devices | head -n 1)
```

它假定 `H:` 行能按空白切出**裸的 `eventN`**。实际格式是：

```text
N: Name="NVTCapacitivePen"
P: Phys=input/pen
S: Sysfs=/devices/virtual/input/input8
H: Handlers=event5 cpufreq          ← 第一个 handler 与 "Handlers=" 粘在一起
```

分词结果是 `H:` / `Handlers=event5` / `cpufreq` —— **没有任何一个等于 `event5`**，
正则永远为假。设备实测（同一台机器、同一时刻）：

```text
$ awk '<上面那段>' /proc/bus/input/devices        →  (空)
$ awk '/Name=/{pen=tolower($0)~/pen/} pen&&/Handlers=/{print $2; exit}'  →  Handlers=event5
$ for d in /sys/class/input/input*; do cat $d/name; done                   →  ... NVTCapacitivePen
$ ls /sys/class/input/input8/                                              →  event5
```

即：**节点一直都在 `/dev/input/event5`，只是这段 awk 找不到它。**
这解释了「震动能用但写不出」—— 笔的 BLE 命令总线活着，而死掉的是触控子系统，
恰恰是链路 cycle 才能唤醒的那一半（实验 E1b：haptic/命令总线 ≠ 触控管线）。

### 为什么没人发现

失败路径返回 `2`，而 `run_wake_guard_once` 对 `2` 的处理是

```sh
echo "... pen input node unresolvable; skipping"
return 0
```

—— **日志有、动作无**，且与「笔醒着，不需要唤醒」共用同一条"什么都不做"的出口。
（v0.1.16 的旧实现在同一处明确写过"判不了要失败保险向"，v0.1.17 重写时丢了这条原则。）

## 4. 根因 2：断而不建（v0.1.15 修过，随 D1a 一起被删）

`request_pen_disconnect` 发出的 `DISCONNECT_PENCIL` 会让 Hook 把
`lenovo_pen_disconnect_requested` 与 `lenovo_pen_user_disconnect_requested` **两个闩锁一起置 1**
（`IpeManagerHooks` 的断开接收器），而 `request_pen_connect` 开头就是：

```sh
[ "$requested" = 1 ] && { echo "pen connect skipped: Settings disconnect latch is set"; return 0; }
```

⇒ 守护自己断的链，自己再也连不回去。20:11:57 那次手动唤醒的日志实证：

```text
20:12:10 wake guard: link cycle start
20:12:10 pen connect skipped: Settings disconnect latch is set     ← 连不上
20:12:10 real OEM boot connect retry attempt=2                     ← 靠别的循环救回来
20:12:16 wake guard: link restored after 6s
20:12:17 explicit pen connect already has a real link
```

那次成功了，但**成功靠的是当时还在跑的 boot 重试循环**（设备刚启动）。稳态下没有这个循环，
结果会是"链断了、笔连不回来"——比僵尸连接更糟。

## 5. 根因 3：默认关闭 + v0.1.17 已删掉无条件兜底

- v0.1.17「减法重构」删除了 D1a 硬重连全套（离座边沿的笔尖探针 + `request_pen_reconnect_hard`）。
- v0.1.18 新增的唤醒守护**默认关闭**（`pen-wake-guard.state` 存在才生效）。

⇒ **默认安装下，用户在 dock 睡死场景里得不到任何接管**。这与用户"项目声称修好了但没修好"的
观感完全吻合。

## 6. 修复（v0.1.19）

| 项 | 改动 |
|---|---|
| 节点解析 | 主路径改回 **sysfs 精确名**：扫 `/sys/class/input/input*/name`，命中 `NVTCapacitivePen`（常量 `PEN_TIP_DEVICE_NAME`）后取其 `eventN` → `/dev/input/eventN`。`/proc` 仅作兜底，且改用 `match($0, /event[0-9]+/)` 在**整行**里定位，不再分词。 |
| 失败保险向 | `rc=2`（判不了）不再"跳过"，改为**当作笔没在发射、照常唤醒**（宁可多走一次 ~13 秒循环，也不让"判据坏了"表现为"笔坏了"）；日志打 `WARN`。有"每吸附会话一轮 + 120 秒冷却"兜底，不会变风暴。 |
| 断而不建 | 循环内先快照用户意图；若不是用户在设置页的显式选择，断链确认后**主动清掉两个闩锁**再连；若用户确实选了断开，则整轮中止（尊重用户）。 |
| `link != 1` 分支 | 不再打一行 `nothing to cycle` 就返回 —— 该形态正是"连震动都没了"（连 BLE 载波都掉）。改走同一套 link cycle（先断后连是破僵尸 HOGP 的唯一路径，见 §1.5.2）。 |
| 默认值 | `customize.sh` 安装时 `touch pen-wake-guard.state`（默认开启）；`action.sh wake-guard-off` 删除即持久关闭。 |

## 7. 实机验证（2026-09-21 21:11–21:13，免重启替换 service.sh + ksud 安装）

### 7.1 睡死分支（笔尖 6 秒零事件）

```text
21:11:04 wake guard: undocked with live link; listening for pen input (6s window)
21:11:10 wake guard: link cycle start (was_connected=1; disconnect pen BLE link)
21:11:10 OEM com.oplus.ipemanager.action.DISCONNECT_PENCIL requested mac=DC:EB:4D:06:77:DD
21:11:11 HID disconnect service requested mac=DC:EB:4D:06:77:DD
21:11:12 wake guard: link down confirmed after 0s
21:11:12 wake guard: cleared self-inflicted disconnect latches; holding offline 3s
21:11:15 OEM com.oplus.ipemanager.action.CONNECT_PENCIL requested mac=DC:EB:4D:06:77:DD   ← 闩锁清掉后投递成功
21:11:16 HID connect service requested mac=DC:EB:4D:06:77:DD
21:11:17 wake guard: link restored after 0s; replaying haptic handshake
```

Hook 侧（`logcat -s LenovoPenBridge`）：

```text
21:11:18.767 haptic session refresh: inkdye handshake replay requested (post-wake)
21:11:18.777 haptic session refresh: inkdye handshake replay requested (post-wake)   ← 新旧 action 各一
```

⇒ 唤醒动作链完整闭环；循环后两闩锁均为 `0`，`pen-wake-arm.state` 被消费，冷却戳正常写入。

### 7.2 清醒分支（窗口内注入笔尖事件，防误伤）

先用 `sendevent /dev/input/event5 3 0/1 …` 验证注入确实能被 evdev 读者看到
（`timeout 5 dd if=event5 …` 立即返回），再在 6 秒窗口内持续注入 20 秒：

```text
21:12:41 wake guard: undocked with live link; listening for pen input (6s window)
21:12:47 wake guard: pen emitted input; awake, no wake needed
冷却戳 = (无 → 未走唤醒循环)
```

⇒ 判定正确，**没有把醒着的笔误断链**。

### 7.3 部署状态

- 模块 `0.1.19 / 119`（`ksud module install` 成功，`customize.sh` 已创建 `pen-wake-guard.state`），
  `service.sh` md5 `1caf29bf980fad59ec6feb4018cd2d28`；Hook 未改动（仍 v4.7.0），Vector scope 8 项回读一致。
- 进程树：1 个重生循环（busybox sh，PPID 1）+ 1 个主体 + 7 个监视子壳，**无 PPID=1 的孤儿**。

## 8. 未验证项（诚实边界）

1. **真·深睡场景未复现**：需要"吸附满电 → 驱动 close tx → 笔深睡 → 取下"的完整物理链路，
   无法在会话内按需构造。**需用户复测**：`sh action.sh log` 搜 `wake guard`，必须看到
   `link cycle start`，且**不得**再出现 `unresolvable; skipping`。
2. **`link=0` 分支**（§6 第 4 行）只做了代码级检查，未构造该状态实测。
3. 6 秒判据窗口仍是启发式：用户取下笔后**远离屏幕**超过 6 秒（例如放进包里）会误判为睡死，
   代价是一次约 13 秒的无谓断链（循环结束即恢复）。当前认为可接受，若实测扰民可调
   `WAKE_QUIET_SECONDS`。
4. **锁屏闸门（§11）只验证了"闭"的一半**：设备有安全锁，会话内无法用 `wm dismiss-keyguard`
   解锁，因此"解锁后闸门打开、武装被正常消费"这半只做了代码级 + 交互式逻辑复刻验证，
   未在真机观察到端到端执行。

## 9. 排查坑：手动重启本服务的正确姿势

本次为了免重启验证，直接用 `su -c 'sh service.sh'` 拉起，结果**进程树空、日志无新行**，
极易误判成"代码改坏了"。真因在入口的重生锁：

```sh
exec 9>"$SERVICE_LOCK" 2>/dev/null || exit 0
if ! /data/adb/ksu/bin/busybox flock -n 9 2>/dev/null; then exit 0; fi
```

`/system/bin/sh` 是 **mksh**，`exec 9>file` 在 exec 时会**丢掉 fd**，于是 busybox flock 报
`flock: Bad file descriptor` → 脚本静默 `exit 0`。**busybox ash 保留该 fd**。
开机时 KernelSU 用 busybox ash 调用，所以正式路径不受影响；手动时必须：

```sh
PATH=/data/adb/ksu/bin:$PATH setsid /data/adb/ksu/bin/busybox sh \
    /data/adb/modules/tb522fu_pen_bridge/service.sh
```

（`PATH` 前置是必须的：重生循环里写的是 `sh "$0"`，PATH 不对会解析到 mksh，循环空转。）

## 10. 教训

- **静默的判据失败比功能缺失更危险**。这条通路上已经踩过第四次同类：
  ① v0.1.9「修复挂在一个从未成立的条件后面」；② v0.1.10「子壳永久卡住、`( … ) &` 失败静默」；
  ③ §3「解析恒失败、rc=2 被当成跳过」；④ §11「锁屏窗口内判据必然为假，一次性武装被静默烧掉」。
  **凡是有"判不了"分支的地方，都要么失败保险向，要么在日志里打出足够刺眼的标记；
  凡是"一次性配额"的地方，都要先确认配额不会被一个必然失败的窗口吃掉。**
- 把功能从"无条件执行"改成"可选 + 默认关闭"时，必须同时确认**默认安装下**的场景归属，
  否则等于静默下线。

## 11. 第四处静默空转：锁屏窗口（v0.1.20）

### 11.1 发现经过

v0.1.19 用 `ksud module install` + 重启做正式部署验证时，开机日志里反复出现：

```text
21:26:20 OEM com.oplus.ipemanager.action.CONNECT_PENCIL request failed mac=DC:EB:4D:06:77:DD unlocked=0
21:26:20 OEM haptic recovery not deliverable; channel unreachable, budget kept (1/40)
21:26:50 ... unlocked=0   budget kept (2/40)
21:27:20 ... unlocked=0   budget kept (3/40)     ← 每 30 秒一次，一直失败
```

`unlocked=0` 不是噪声。设备侧直接复现（设备当时 `RUNNING_LOCKED`）：

```text
$ dumpsys user | grep State:
    State: RUNNING_LOCKED
$ am startservice --user 0 -n com.oplus.ipemanager/.btadsorb.CoreService \
      -a com.oplus.ipemanager.action.CONNECT_PENCIL --es device_mac_info DC:EB:4D:06:77:DD
Starting service: Intent { act=...CONNECT_PENCIL cmp=com.oplus.ipemanager/.btadsorb.CoreService }
Error: Not found; no service started.
am rc=255
```

根因在 manifest 里 —— `CoreService` **没有** `android:directBootAware`：

```xml
<service android:name="com.oplus.ipemanager.btadsorb.CoreService"
         android:permission="com.oplus.permission.safe.IOT"
         android:enabled="true" android:exported="true"
         android:process=":ble">        <!-- 无 directBootAware / encryptionAware -->
```

⇒ user 0 处于 `RUNNING_LOCKED`（**开机后尚未首次解锁**）时，PMS 直接过滤掉该组件，
`am` 报 "Not found; no service started"（rc=255）。**这是结构性限制，不是时序偶然。**

（窗口边界很重要：FBE 在**首次解锁后**，user 0 的 State 恒为 `RUNNING_UNLOCKED`，
之后息屏、锁屏都**不会**回到 `RUNNING_LOCKED`。所以暴露面 = **开机 → 首次解锁**。）

### 11.2 为什么这又是"静默空转"

`run_wake_guard_once()` 的第一行就是消费武装标记：

```sh
run_wake_guard_once() {
    # 消费武装标记：每次吸附会话最多一轮。
    rm -f "$WAKE_GUARD_ARM_FILE"
```

于是在锁屏窗口里用户**先取下笔**（屏幕还没解锁）时：

| 步骤 | 结果 |
|---|---|
| 离座边沿武装 `pen-wake-arm.state` | ✓ |
| 6 秒窗口内笔尖零事件 | ✓（笔确实睡死） |
| Harness 断/连 → `DISCONNECT_PENCIL` / `CONNECT_PENCIL` | **✗ rc=255，投递不出去** |
| 轮询 6s 等断链 → 不变 → `link never went down within 6s; abort` | 守护失败退出 |
| 一次性武装 | **已被烧掉，本次离座会话不再重试** |

用户随后解锁、打开笔记、落笔 —— 笔是死的。**与用户原始报告完全同形**，
只是换了一条静默路径。这正是"一次性配额被一个必然失败的窗口吃掉"。

### 11.3 修复

`monitor_wake_guard` 里加**锁屏闸门**（判定放在消费武装**之前**）：

```sh
elif [ -f "$WAKE_GUARD_ARM_FILE" ] \
        && [ -f "$WAKE_GUARD_ENABLED_FILE" ] \
        && [ "$(read_hall_state)" = 0 ]; then
    if user_unlocked; then
        rm -f "$WAKE_GUARD_DEFER_FILE" 2>/dev/null
        run_wake_guard_once          # 正常路径，消费武装
    elif [ "$(cat "$WAKE_GUARD_DEFER_FILE" 2>/dev/null)" != locked ]; then
        echo "... wake guard: user not unlocked yet; OEM channel unreachable, deferring (arm kept)"
        echo locked >"$WAKE_GUARD_DEFER_FILE"
    fi
fi
```

要点：

- **保持武装、不消费** —— 解锁后（FBE State 翻成 `RUNNING_UNLOCKED`）下一次 2 秒轮询
  就会正常走 `run_wake_guard_once`，把这次离座会话的唤醒补做掉。
- 新增 `pen-wake.defer` 只做**日志去重**（2 秒轮询不刷屏），并在离座边沿（`monitor_hall_capsule`
  写武装标记处）复位，使标记严格限定在**一次吸附会话**内。
- 手动入口 `action.sh wake`（`pen-wake-now`）**不受**闸门约束 —— 那是用户当面的显式意图。
- 谓词 `user_unlocked()`（`dumpsys user | grep -q 'State: RUNNING_UNLOCKED'`）是既有函数，
  原本只用于把失败原因写进日志（"不参与任何决策"）；v0.1.20 起它**参与**一次决策，
  注释已同步更新。

### 11.4 验证

设备侧逻辑复刻（设备当时确为 `RUNNING_LOCKED`）：

```text
--- 构造武装标记 + 复位去重标记 ---
--- 第 1 次判定 ---
  => 打印: user not unlocked yet; OEM channel unreachable, deferring (arm kept)
  武装标记是否仍在: 是-未消费          ← 关键属性成立
  去重标记        : locked
--- 第 2 次判定（应静默去重，不重复刷日志）---
  => 静默（去重生效，2 秒轮询不会刷屏）
  武装标记是否仍在: 是-未消费
```

`wm dismiss-keyguard` 未能解锁（设备设了安全锁），故**"解锁后闸门打开"这半未在真机观察到**
（见 §8.4）。

### 11.5 顺带确认：`/sdcard` 在锁屏下不可用

`ksud module install` 成功后想把产物放回 `/sdcard/Download/…` 时踩到：

```text
$ adb push …/tb522fu-pen-bridge-v0.1.20.zip /sdcard/Download/tb522fu-pen-bridge/
adb: error: failed to copy …: remote secure_mkdirs() failed: No such file or directory
$ su -c 'ls /sdcard/Download/tb522fu-pen-bridge/'
ls: /sdcard/Download/tb522fu-pen-bridge/: No such file or directory
```

`/sdcard` 是 user 0 的 FUSE 挂载点，**user 0 未解锁时整条不挂载**（`adb push` 甚至可能报
假的 "1 file pushed"）。锁屏状态下要落盘，走 root 直写 `/data/media/0/…`。
**别据此判断包没推成功。**

---

## 12. 生产样本：第一次真实"离座后无笔画"（2026-09-21 21:36，v0.1.21 依据）

> 用户报告："刚才解锁第一次，笔有震动但是无笔画。**过了一会，笔震动笔画都正常了**。"
> 这是 v0.1.19 上线后**第一次真实复现**该场景，且带完整日志 —— 既验证了修复，
> 又暴露出一个新的配置缺陷。

### 12.1 事实时间线（全部可从日志复算）

| 时刻 | 事件 | 证据来源 |
|---|---|---|
| 21:30:54–21:36:28 | 12× `OEM … CONNECT_PENCIL request failed … unlocked=0`（开机重试循环，30s 一次，预算 12/40） | `pen-bridge.log` |
| 21:36:29.16 | 屏幕点亮，`Device reconfigured: id=9, name='NVTCapacitivePen' … mode DIRECT` ← **Android 侧笔设备是 ENABLED 的** | `InputReader` |
| 21:36:36.91 | 首次解锁后原厂 s0 会话建立（`IPe OEM s0 services discovered`，inkdye 通路就绪） | Hook 日志 |
| 21:36:37.49 | hall 1→0（离座），写武装 | 模块 + `InputReader` |
| 21:36:38 | 守护 6s 静默窗口开始 | 模块 |
| 21:36:39.4–44.4 | **5× 捏握 impact 震动**（`op=impact`）+ `injected combo [113, 54]`；21:36:44.37 截屏广播 | Hook |
| **21:36:38–48.5** | **笔画事件数 = 0** | logcat `TOOL_TYPE_STYLUS` |
| 21:36:44.58 | 守护断链 | 模块 + Hook |
| 21:36:45.0 | `HID pencil state 3→0` | `EventManager` |
| **21:36:48.5** | **首批笔画**（`deviceId=9, TOOL_TYPE_STYLUS`，HOVER 起手）**= 断链后 3.9s** | logcat |
| 21:36:48.87–49.46 | 守护重连，`HID pencil state 1→2`，LE `handle 0x0002` | Hook/`EventManager`/QHCI |
| 21:36:49.46 | `start writing haptic pressure=0.626` | Hook |
| 21:36:52.01 | `haptic session refresh: inkdye handshake replay requested (post-wake)` | Hook |
| 21:36:55.11 | 最后一次笔画（本段共 **656** 个 stylus 事件，全部落在 21:36:48–55） | logcat |

### 12.2 判定

1. **守护这次真的跑完了**，且**没有** `unresolvable; skipping` —— v0.1.19 的根因修复在生产成立。
2. **"有震动无笔画"窗口 = 21:36:37 → 21:36:48，约 11 秒**。震动链路（BLE GATT `op=impact`）
   整段健在，笔尖触控链路整段为零；不是"因为被判定为睡着才故意关掉"。
3. **不是 Android 屏蔽**：`NVTCapacitivePen`（device 9）在 21:36:29–21:37:10 之间
   处于 `mode DIRECT`（启用），没有 `Disabling NVTCapacitivePen` 记录。窗口内的零事件
   是**笔端/数字板没有上报**，不是被上层丢弃。
4. **笔画恢复点落在断链后 3.9s** —— 与 E4 实验测得的"断链 → 笔醒来 2–5s"
   （`docs/pen_wake_experiment_E0_E4_20260921.md`）吻合。**最自洽的解释是守护的断链把笔叫醒的**，
   而不是笔自己按 11 秒的固定延迟醒来。

### 12.3 由此暴露的配置缺陷（v0.1.21 修）

**失连保持原本 3s，而实测苏醒点是 3.9s ⇒ 余量只有 0.37s。**

```
21:36:44.58  DISCONNECT_PENCIL 生效
21:36:48.5   笔出第一个笔画        ← 断链后 3.9s
21:36:48.87  CONNECT_PENCIL        ← 只比笔醒来早 0.37s
```

3s 是 E4 区间(2–5s)的**下沿**。一旦某次偏移到 4.5s，重连就会落在笔醒来**之前** →
退回"连上了但触控死"，也就是用户最早报的**"必须重新吸附一次才好"**。
⇒ `WAKE_OFFLINE_HOLD_SECONDS = 5`（区间上沿）。

### 12.4 同时补上"循环跑完 ≠ 修好了"

`do_pen_wake_cycle` 返回 0 只代表"链路回来了 + 握手重放了"，**不代表笔能写**。
此前没有任何事后判据，所以"循环白跑一趟"会以完全正常的日志出现 —— 这是本项目
**第五次**同类静默失败（前四次见 §10 与 §11）。

新增 `verify_pen_awake()`：重连后看笔尖节点 `WAKE_VERIFY_SECONDS=12` 秒，分三态落日志：

| 日志 | 含义 |
|---|---|
| `cycle OK - pen emitted input Ns after undock` | 真的活了 |
| `WARN cycle FAILED - pen still silent 12s after cycle (Ns after undock); re-dock may be required` | **跑完但没用**，此前完全不可见 |
| `WARN cycle UNVERIFIABLE - pen input node unresolvable after cycle` | 判据本身坏了 |

⚠️ **只记日志，不改行为**（不重试、不回滚、不延长武装）。理由：没有证据表明第二轮
循环有用，而"验证窗口内静默"这个条件本身是歧义的 —— 用户把笔放一边不碰屏幕
同样是静默。先让失败**可见**，拿到真实发生率再决定要不要加重试（见 `TODO.md`）。

顺带把 `run_wake_guard_once` 里**最后一处无声 return** 也补了留痕：冷却跳过原本
是 `[ … ] && return 0`，日志上看不出"守护来过但被冷却挡住"，只会以为没触发。

### 12.5 标定数据：`WAKE_QUIET_SECONDS=6` 可能偏短（待观察）

同一份日志给出一个刺眼的数：**离座 → 首笔 = 11 秒**，而静默窗口只有 6 秒
⇒ 窗口内**必然**判成"睡死" ⇒ 守护退化成"每次离座都无条件断链重连"（代价约 7 秒
BLE 离线 + 握手重放）。

这是"离座 → 首笔"延迟的**唯一样本**，且被循环污染（循环在 6–8s 介入，
此后的延迟必然 ≥ 循环耗时）。**要判定窗口该不该放宽，需要一次对照组**：
`action.sh wake-guard-off` → 吸附等 10 分钟 → 取下即写，记录首个笔画出现的时刻。
v0.1.21 已在正常分支与 `cycle OK` 分支都打上 `${n}s after undock`，正是为标定这个值。

### 12.6 实机验证（v0.1.21）

| 分支 | 日志 |
|---|---|
| 循环成功 | `21:44:46 cleared … holding offline 5s` → `21:44:52 link restored` → `21:44:56 cycle OK - pen emitted input 18s after undock`（注入笔尖事件命中验证窗口） |
| 循环失败 | `21:45:53 holding offline 5s` → `21:46:01 link restored` → `21:46:14 WARN cycle FAILED - pen still silent 12s after cycle (30s after undock)` |
| 冷却留痕 | `21:46:40 wake guard: in cooldown (55s < 120s since last cycle); skipping this undock` |

随后 `ksud module install` + 重启，开机后核对：模块 `0.1.21`、守护开启、
`service.sh` md5 `35096ae29c37963d436a6951c240e5a9`（= release zip 内那份）、
Hook `4.7.0`、`system_server stylus hooks installed (startOtherServices=1 run=1)`、
Vector 作用域 8/8（含 `system`）、进程树 1 循环 + 1 主体 + 6 监视壳且**无孤儿**、两闩锁为 0。

### 12.7 排查坑（本段踩到的）

- **别用 `PEN_SERVICE_RESPAWN` 环境变量区分"重生循环"和"主体"** —— 主体和 6 个监视壳
  **都**继承了这个变量，判据是反的。正确特征看 `cmdline`：
  循环是 `busybox sh …/service.sh`，主体与监视壳是 `sh …/service.sh`。
  按错判据杀进程会把整个守护（含循环）杀空。
- **`dmesg | grep -i pen` 的假阳性**：`susp**en**d` 含 "pen"，本机笔相关内核日志实际为 0 条，
  grep 出来的全是 servicemanager 的 suspend 噪声。要判笔的内核侧行为**不能靠 dmesg**。

---

## 13. 方案 1 落地：penhidctl DBA 化，锁屏期唤醒通路打通（v0.1.22，2026-09-21 22:00–22:45）

> **一句话**：把自研 priv-app `PenHidCtl` 的服务标上 `directBootAware`，锁屏（user 0
> RUNNING_LOCKED）期 PMS 不再过滤它 —— 唤醒守护的断/连不再依赖原厂 CoreService，
> 息屏、开机未解锁都能做 link cycle。全部实机验证通过。

### 13.1 前提实验（改造前，22:02–22:05）

| 实验 | 状态 | 结果 |
|---|---|---|
| user 0 状态（21:36 解锁过、息屏后） | RUNNING_LOCKED | **这台 ROM 息屏会把 user 0 锁回**，v0.1.20「FBE 解一次恒解锁」假设被推翻 |
| 锁屏态 `am start-foreground-service` penhidctl（无 DBA） | rc=255 | `Error: Not found; no service started`，与 OEM CoreService 同样被 PMS 过滤 |
| `dumpsys package com.android.bluetooth` | DIRECT_BOOT_AWARE | 蓝牙栈本身锁屏可用 ⇒ 只要我们的组件能被解析，蓝牙操作就做得成 |
| 笔链路（锁屏态） | link_connected=1 | 「链路在、触控死」形态下蓝牙栈完全正常 |

### 13.2 PenHidCtl 4.1.5 的三处改动

1. **`PenHidService` 加 `android:directBootAware="true"`**。服务只碰蓝牙栈（系统服务、
   自身 DBA），不读任何 CE 数据，DBA 安全。
2. **执行回执**：结束前把 `<elapsedRealtime> <action> ok|fail <detail>` 写进自己 files
   目录（`penhid.result`；锁屏未解锁时落 `/data/user_de/0/...`，解锁过后落
   `/data/user/0/...`）。`am rc=0` 只证明"投递成功"，回执才证明"做了"。默认 fail，
   只有两条 Bluetooth 调用都反射成功才 ok —— 判不了 ≠ 成功。
3. **`connect()` / `disconnect()` 方法不存在**：这台 ROM 的 BluetoothHidHost 没有
   1 参的这两个隐藏方法（实测 "connect/disconnect method unavailable"）。
   **`setConnectionPolicy(FORBIDDEN)` 本身就会踢掉 HOGP 链路**（实测 state 2→0），
   **切回 ALLOWED 就是重连触发器**（实测 0→2）。原实现的 150/250ms 双段式全部
   简化为单 policy 调用，并把 policy 调用成功作为 ok 的唯一判据。

### 13.3 PMS 对 /system 应用按 mtime 跳过重扫的陷阱

模块 overlay 里的 APK 换成 4.1.4 后重启，**PMS 仍报 4.1.3**：packages.xml 缓存的
`timeStamp`（1970-01-11 21:51:52 = epoch 913912）与 overlay 呈现的新文件 mtime
**逐秒相同** ⇒ PMS 认为文件没变，跳过重扫。KSU 呈现给 /system 的 mtime 不随
模块目录文件的真实 mtime（2026 时钟）走，`touch` 模块目录文件也传导不过去。

**解法**：`pm install -r` 把新 APK 装成 **UPDATED_SYSTEM_APP**：
- PMS 立即可见，不依赖 /system 重扫；
- **保留 PRIVILEGED 与 privapp 白名单授权**（实测 `BLUETOOTH_PRIVILEGED granted=true`，
  `customize.sh` 里旧注释"会丢 privileged"对更新场景不成立，已改写）；
- APK 落在 /data/app，App 进程一定能打开 —— 顺带根治 docs §N1 的
  "overlay 对 App 进程 mount namespace 不可见 → penhidctl 100% 崩溃"隐患；
- 全新安装（PMS 不认识该包）时**不**走 pm install（会变成非特权 /data 应用），
  仍靠 overlay + 重启扫描 —— customize.sh 已按此分叉。
- 卸载：`uninstall.sh` 增加 `pm uninstall com.aclaniakea.penhidctl` 清掉更新层。

### 13.4 service.sh 的配套改动

1. **`run_hidctl` 升级为带回执的已验证调用**：先删回执 → `am`（rc!=0 直接报
   rejected）→ 轮询回执至多 12s（盖过服务自身 8s profile 超时的 fail 回执）→
   落 `HID <action> ok/FAILED (detail)`。skip 场景返回 1（原先返回 0 是又一个静默成功）。
2. **删除 v0.1.20 锁屏闸门**。它建立在"息屏不重锁"的错误假设上，实际把每次息屏
   期间的守护全部挡死（与 `unresolvable; skipping` 同类的第五种静默空转）。
   已知代价：锁屏期唤醒后的 haptic REFRESH 下游（inkdye 会话在非 DBA 的 OEM
   进程里）可能只能等解锁后恢复 —— 退化形态"笔画先回、震动后回"，远好于整段等解锁。
3. **回链窗口 8s → `WAKE_LINK_BACK_SECONDS=25`**：锁屏态实测回链 ~23s
   （22:32 样本：policy 切回 ALLOWED 后 23s 镜像才变 1；屏幕灭着时 BLE 重连慢得多）。
4. **断链确认改双判据**：镜像 !=1 **或** `real_bt_connected`（HOGP state=2）为假。
   锁屏态镜像滞后可 >6s（22:37 样本：已断链、镜像纹丝不动 → 误判"没断开"→ abort）。
5. **abort 必须恢复 ALLOWED**：上述误判路径会把笔留在"已断链 + FORBIDDEN"死态。
   abort 时补一次 `run_hidctl connect` 再报失败。
6. 闩锁清理移到确认分支之前：无论判成什么都清（是我们断的，不是用户）。

### 13.5 实机验证记录（全部锁屏态 / RUNNING_LOCKED）

| 时点 | 场景 | 结果 |
|---|---|---|
| 22:22 | 锁屏（本开机已解锁过）拉起 4.1.4 | `am rc=0`（对照：改造前 rc=255），进程存活，0 崩溃 |
| 22:25 | 完整 disconnect→hold6s→connect | HOGP 2→0→2，回链；回执 ok（4.1.4 旧分支逻辑仍标 fail，已修） |
| 22:28 | 4.1.5 同场景 | `disconnect ok` / `connect ok`，HOGP 2→0→2，0 崩溃 |
| 22:35 | **重启后、FBE 从未解锁、CE 未挂载** | `am rc=0`、回执 ok、HOGP 2→0→2、`link_connected=1`、0 崩溃 —— **最严苛窗口通过** |
| 22:36 | 开机后锁屏期 | 模块 0.1.22 + penhidctl 4.1.5 跨重启持久，笔链路自动恢复，`system_server stylus hooks installed` 判据在 |
| 22:42 | `action.sh wake` 完整循环（模块路径） | `link cycle start` → `link down confirmed after 0s` → hold 5s → `link restored after 0s; replaying haptic handshake` → `link_connected=1` |

### 13.6 已知边界

1. **锁屏期震动可能不回来**：inkdye 会话在非 DBA 的 ipemanager 进程里，解锁前
   `haptic REFRESH` 的下游可能无效。笔画（HID）不受影响。待真实"息屏后取笔"样本确认。
2. **`trusted=false` 期间（22:40 样本）**：离座后 Hall trust 标志为假，属正常离座形态，
   唤醒守护的 cooldown 判定正常工作。
3. WAKE_QUIET_SECONDS=6 的标定问题（§12.5）不变，仍等对照组数据。
