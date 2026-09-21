# TB522FU 手写笔「充电 / 睡眠」缺陷审计报告

日期：2026-09-21
范围：`Lenovo-TB522FU-PenPot` v0.1.8（module）+ PenBridge-Hook v4.6.0
主题：**笔的充电状态** 与 **笔的睡眠/链路状态**
前置：`libSuniaEngine`（涂鸦引擎）已修复，本轮不再讨论
方法：静态代码追踪（写入面 → 裁定函数 → 消费面）+ 设备端只读采样 + 1 组受控实验

设备：Lenovo TB522FU（OPD2409，SM8750P），ROM 为 ColorOS 移植版
便签：`com.coloros.note` 16.7.2 / 引擎已修版 md5 `db61d1ff…`

---

## 0. 结论速览

| # | 项 | 是否成立 | 触发条件 | 后果 | 严重度 | 本轮证据 |
|---|---|---|---|---|---|---|
| A | `PenBridgeReceiver` 写入充电镜面**未钳制负值哨兵** | ✅ 成立 | 收到 charge extra 为负值的事件，且 `physicalDocked!=0`、`oem_charge_valid!=1` | 镜面变 `-1`，`!=0` 消费者读成"充电中"；广播已发出无法回收 | **低-中**（有 charge-guard 兜底） | 代码 + 注入 `-1` 受控实验 |
| B | `guard_note_engine.sh` **开机路径空转**（自愈承诺落空） | ✅ 成立（**本轮最重要**） | 每次开机（post-fs-data 阶段 PM 未就绪） | 引擎钉版从不执行；HeyTap 再升级 → 涂鸦复崩 | **高** | 设备日志全为 `engine path not found -> skip`；手工跑立刻成功 |
| C | `pen-revive-guard` 的 `powered_down` 判据把**"满电静置"误判为"笔已掉电"** | ✅ 实测复现（性质取决于一项未决事实） | 吸附 + 驱动关 TX + `online=0` 持续 ≥90 s | 镜像翻成"未连接"、电量 `-1`，而蓝牙栈 `HOGP state=2` 仍真 | **中** | 10:29→10:32 完整时间线 |
| D | `tx_status=1` 无条件写 | ✅ 成立（设计上"只开不关"） | `panic.sh` / boot guard 熔断 / `uninstall.sh` | 笔不在线圈时也开一次载波（驱动 30s 空载自关）；dmesg 噪音 | 低 | 全仓仅 3 处写、全部写 1，无 1 处写 0 |
| E | `lenovo_pen_charging_state` 死写（无读者） | ✅ 成立 | 每次镜面变化 | 无害孤儿键 | 极低 | 全仓出现 1 次（仅写） |
| F | CPS GPIO 通路为死代码 | ✅ 成立 | 不会触发 | 无运行时影响；误启用会走语义不同的 `gpioset` | 低 | `monitor_cps_gpio` 调用被注释；`bin/` 只有 `penlog.sh` |
| G | 笔输入门控完全依赖 Hall | ✅ 成立（已被 debounce 缓解） | Hall 误判"吸附" | `disableInputDevice()` → 笔写不出字 | 中 | `zInputEnabled = (iDocked==0 && !zDisconnected)` |

**"主动修改笔充电"复核**：硬件层 `tx_status` 全仓**只有 3 处写、全部写 `1`**，**没有任何一处写 `0`** —— 即"只开不关"，不会挡住合法补电。v2 的强制关充已在 v3 删除。
**"主动修改笔睡眠"复核**：无任何"让笔休眠"指令。`/proc/pen_wakeup_*` 是**系统侧**唤醒通路常开；BLE GATT 写只发给 `LENOVO_HAPTIC_*`（震动），无 sleep / DFU。笔的关机是固件行为。

---

## A. 充电镜面：唯一一处绕过裁定函数的写入

### A.1 写入面全景（`ipe_pencil_charging_state`）

共 6 个写入者：

| 位置 | 写入值 | 是否过裁定 |
|---|---|---|
| `module/service.sh:380` | 常量 `0` | n/a |
| `module/service.sh:939`（`publish_hall_state`） | `read_hardware_charging` → 保证 0/1（函数末尾必有 `echo 0` 兜底） | ✅ |
| `module/charge-guard.sh:110`（`sync_ipe_state`） | `cps_wls_en` 真值 → 0/1 | ✅ |
| `hook/…/PenStateStore.java:128` | `effectiveCharging(...)`，且 `<0` 时钳成 0 | ✅ |
| `hook/…/HookUtils.java:286, 334` | 常量 `0` | n/a |
| **`hook/…/PenBridgeReceiver.java:328`** | **`penState.charging` 原样** | ❌ **未过裁定、未钳制** |

裁定函数（`HookUtils.effectiveCharging`，第 394 行）：

```java
if (physicalDocked(context) == 0) return 0;   // Hall 未吸附 → 强制 0（安全闸）
int iOemCharging = oemCharging(context);
if (iOemCharging >= 0) return iOemCharging;   // OEM 带内值优先
if (i < 0) return -1;                         // ← -1 = "未知" 哨兵
return i == 0 ? 0 : 1;
```

### A.2 缺陷本体

`PenBridgeReceiver.publishCurrentHardwareState()`（第 38–48 行）在**同一个函数里自相矛盾**：

```java
PenState penState = PenStateStore.read(context);   // charging 可能是 -1
PenStateStore.write(context, penState);            // ← 这里把 -1 钳成 0 写进 settings  ✅
broadcastColorOs(context, penState, str, …);       // ← 这里又把未钳制的 -1 写回去  ❌
```

`broadcastColorOs` 第 328 行：

```java
Settings.Global.putInt(…, "ipe_pencil_charging_state", penState.charging);
```

且第 289 行还有一处连带影响：

```java
boolean zPresent = iPhysicalDocked == 1 && penState.charging != 0;   // -1 也会让"在位"=1
```

### A.3 触发条件（三个同时满足）

1. 一次进入 `broadcastColorOs` 的事件，其 `PenState.charging` 为 `-1`。来源有两条：
   - **入口一**：`PenBridgeReceiver:252` 的 `new PenState(…, z3 ? i : 0, …)`，其中 `i = iChargingExtra = chargingExtra(intent, penState.charging)`；若 intent 携带 `chargingState`/`charging`/`charge_state` 且值为**负数（如 `-1` = unknown）**，`intExtra` 会原样返回（`intExtra` 接受 `Number` / `Boolean` / `String`，`"-1"` 也能 parse 出 -1），随后三条归一化都不覆盖它：
     - `if (z && (iChargingExtra==0 || iChargingExtra==1))` → `-1` 不匹配，跳过；
     - `if (!z && iOemCharging >= 0)` → 仅当 `z==false` 且 OEM 有效才覆盖；
     - `if (physicalDocked(context) == 0)` → **只在"未吸附"时归零**，吸附/未知时不生效。
   - **入口二**：`publishCurrentHardwareState` 直接 `PenStateStore.read()`，只要镜面已经是 `-1` 就自我延续。
2. `physicalDocked != 0`（吸附，或开机首帧前 settings 缺省的 `-1`）—— 否则安全闸会强制 0。
3. `lenovo_pen_oem_charge_valid != 1`（OEM 带内充电字节失效）。**注意这是一个会自动打开的窗口**：`HookUtils.invalidateOemCharging()` 会在 `ACL_DISCONNECTED` 与"吸附态翻转"时把 `valid` 清 0，直到下一帧带内充电字节到达。

### A.4 后果

- `ipe_pencil_charging_state = -1` 落盘。
- 所有用 `!= 0` 判定"是否充电"的消费者把 `-1` 读成**充电中**：
  - `IpeManagerHooks:472-473`：Device Space 手写笔卡片的 `getCharge()` → `setResult(-1 != 0)` = **true**；
  - `IpeManagerHooks:499`：`if (effectiveCharging(...) != 0) return;` → 提前返回，**跳过了 `iArr[3] = 0` 的清零**，卡片充电标记不被清。
- 同一次 `broadcastColorOs` 已经把 `charging=-1`、`present=1` 作为**广播**发给 `com.oplus.ipemanager`；**charge-guard 只修 settings、不撤广播**，所以那一次 UI 提示无法回收。
- `WirelessSettingsHooks` 会另外兜一层（`physical_docked=="0" || oemFullOrIdle` 时把读值强制为 0），所以无线设置页受影响较小。

### A.5 受控实验（本机实测）

| 步骤 | 结果 |
|---|---|
| 基线 | `ipe_pencil_charging_state = 0` |
| `settings put global ipe_pencil_charging_state -1` | 读回 `-1` ✅ 注入成功 |
| 等 40 s（两个守护的 10 s / 15 s 轮询各跑 2–4 轮） | 读回 **`0`** → **已自愈** |
| 还原 | `0` |

**自愈来自 `charge-guard.sh`**：`sync_ipe_state()` 在**吸附态**每一轮（15 s）都无条件把镜面对齐到 `cps_wls_en`；`-1 != 0` 于是被改写为 0。

因此 **A 的实际暴露窗口 ≤ 约 15 s**，且只在吸附态被兜住。两处残留风险：

1. **未吸附时无人兜**：`charge-guard` 在 `docked != 1` 时直接 `continue`，不跑 sync。不过此时 `effectiveCharging` 的安全闸会返回 0，消费者看不出问题。
2. **`tx == -1`（tx 节点不可读）时无人兜**：`case "$tx" in 0) … ;; 1) … ;; esac` 对 `-1` 无分支，sync 被跳过。

---

## B. `guard_note_engine.sh` 开机路径空转（自愈承诺落空）

### B.1 现象

设备端 `/data/local/tmp/note_engine_guard.log` 的历史记录**全部**是：

```
01-09 22:38:10 [com.coloros.note] === guard start ===
01-09 22:38:10 [com.coloros.note] engine path not found (app not installed / not extracted yet) -> skip
…（十余条，直到）
01-11 09:57:21 [com.coloros.note] engine path not found (app not installed / not extracted yet) -> skip
```

- 时间戳停在 `01-11 09:57`、文件 mtime = `1970-01-11 09:57:21` → 说明这些运行发生在 **RTC 同步前的极早期**（即 `post-fs-data` 阶段）。
- 手工执行（PM 就绪后）立刻正常：

```
09-21 10:33:55 [com.coloros.note] app versionCode=160070002 engine=/data/app/~~NPan…/lib/arm64/libSuniaEngine.so
09-21 10:33:55 [com.coloros.note] engine md5=db61d1ffdd8c25062920d0a25c697af3
09-21 10:33:55 [com.coloros.note] OK: 引擎已是已修 16.7.2，无需动作
```

### B.2 根因

唯一调用点是 `module/post-fs-data.sh:112`：

```sh
if [ -f "$MODDIR/guard_note_engine.sh" ]; then
    chmod 0755 "$MODDIR/guard_note_engine.sh" 2>/dev/null
    run_bounded 30 "$MODDIR/guard_note_engine.sh"
fi
```

`post-fs-data` 阶段 `/data/app` 与 PackageManager 尚未就绪，守卫第 43 行的

```sh
ENGINE_PATH="$(pm path "$NOTE_PKG" …)"
[ -z "$ENGINE_PATH" ] || [ ! -f "$ENGINE_PATH" ] && { log "engine path not found …"; exit 0; }
```

必然失败 → **每次开机都静默 exit 0**。

### B.3 后果

- **"HeyTap 再升级也能自愈"这个防复发承诺，实际从未生效。**
- 当前引擎之所以是已修版 `db61d1ff…`（mtime `2026-09-18 23:00`）是**手工推送**的结果，**不是守卫的功劳**。
- 一旦 HeyTap Market 再次把便签更新到新版本，引擎不会被修回 → 涂鸦重新崩溃 / 不渲染。这正是 09-12 那次事故的复刻路径。
- 附带：守卫同时承担的"未知版本时写 WARN 标记 + 禁用 HeyTap"也没执行过（`/data/local/tmp/note_engine_guard.UNKNOWN` = ABSENT）。

---

## C. 睡眠侧：`powered_down` 判据把"满电静置"当成"笔已掉电"

### C.1 判据（`pen-revive-guard.sh` v2）

```
docked == 1  &&  tx(cps_wls_en) == 0  &&  online == 0   持续 ≥ POWERED_DOWN_AFTER_SEC(90s)
    ⇒ settings lenovo_pen_powered_down = 1
```

解除条件**只有一条**：`tx == 1 || online == 1`（笔重新上电）。
**离座时故意不清标志**（第 182–193 行注释明说）。

### C.2 本机实测时间线（2026-09-21，全部来自设备日志）

| 时刻 | 事件 | 来源 |
|---|---|---|
| 10:22:27 | 开机，`idle: not docked tx=0 online=0 cap=0`；`powered_down=0` | pen-revive.log |
| 10:22:29 | `real Hall state docked=0 battery=100 charging=0 connected=1` | pen-bridge.log |
| 10:29:43 | **吸附**：`pen docked: tx=0 online=0` → 进入 90 s 计时 | pen-revive.log |
| 10:29:45 | `docked=1 battery=100 charging=1 connected=1`（驱动为吸附边沿开 TX） | pen-bridge.log |
| 10:30:35 | `charging=0` —— **驱动自行关 TX**（100% 满电，吸附约 50 s 后） | pen-bridge.log |
| 10:32:14 | `tx off 91s >= 90s` → **`lenovo_pen_powered_down=1 (was 0)`** | pen-revive.log |
| 10:32:33 | **`real BT state mirror connected=0 (was 1)`** | pen-bridge.log |
| 之后 | `lenovo_pen_link_connected=0`、`ipe_pencil_present=0`、`ipe_pencil_battery_level=-1`、`lenovo_pen_charging_state=0` | settings 采样 |

**同时刻蓝牙栈仍然是"连着"的**：

```
 XX:XX:XX:XX:77:DD : Selected transport=2 HID connection state=0 HOGP connection state=2
```

### C.3 机制

`real_bt_connected()`（`service.sh:1049`）第一件事：

```sh
case "$(settings get global lenovo_pen_powered_down)" in
    1) return 1 ;;      # shell 语义：return 1 = 假 = "未连接"
esac
```

→ `monitor_real_bt_state` 里 `if real_bt_connected; then connected=1; else connected=0; fi` → 发布 **connected=0**，与真实蓝牙栈无关。

### C.4 判定：这是"物理不可区分"的必然结果，但存在假阴性面

脚本自己的注释就承认：`tx=0 && online=0` 时"可能是正常充满静置，也可能是笔已经掉电 —— 两者在物理上是同一个过程，只能用时间区分"。

因此：

- **若"笔满电吸附后确实自行关机"成立** → 标志=1 是正确的（镜像诚实），且它带来的"吸附边沿必发 `request_pen_connect`"（配合 `pen_cps_responsive`）确实修掉了"吸上去是死的、必须再吸一次"。
- **若笔其实还活着** → 这是**假阴性**：UI 报"未连接 / 电量 -1"而笔可用，且因为离座不清标志，该错误状态会一直持续到笔下次上电（TX/online 回 1）。

**这一条是本轮唯一需要用实物确认的未决事实**，判别实验见 §E。

### C.5 一个设计上的耦合建议

`powered_down` 同时驱动了两件事：

1. **重连门控**（`monitor_hall_capsule` 的 `! real_bt_connected || ! pen_cps_responsive`）—— 这是它真正要修的问题；
2. **连接镜像**（`monitor_real_bt_state` 的 `connected`）—— 这一半依赖尚待证实的物理假设。

**建议解耦**：门控继续用 `powered_down`；镜像改为只由真实蓝牙事件驱动（或仅在 `powered_down` 时附带标注"疑似掉电"而不是直接翻成未连接）。

---

## D. 其余复核项

### D.1 `tx_status=1` 无条件写（3 处）

```sh
module/panic.sh:47        echo 1 > /sys/bus/i2c/devices/11-0041/tx_status
module/post-fs-data.sh:37 echo 1 > /sys/bus/i2c/devices/11-0041/tx_status
module/uninstall.sh:37    echo 1 > /sys/bus/i2c/devices/11-0041/tx_status
```

- **触发**：一键救援（`panic.sh`）/ boot guard 连续失败熔断恢复 / 卸载模块还原。
- **后果**：笔不在线圈上时也开一次 TX。驱动按空载超时自行关闭（本项目 D 组实验：TX 常开 120 s，驱动 30 s 后自己关）。代价是约 30 s 的空载载波（线圈微发热 + 功耗 + dmesg 噪音）；**不会**污染充电镜面（未吸附时 `charge-guard` 不 sync，且 `publish_hall_state` 只在 hall 边沿跑）。
- **注**：吸附边沿本来就会让驱动自己开 TX，这三处写属于冗余。可选择性移除，或至少加 `hall==docked` 前置判断。

### D.2 `lenovo_pen_charging_state` 死写

全仓（排除 `.git/`、`releases/`）**出现 1 次**：`charge-guard.sh:111` 的 `settings put global lenovo_pen_charging_state "$1"`，**零读者**。
→ 无害孤儿键，建议删除该行。

### D.3 CPS GPIO 死代码

- `service.sh:1529 start_cps_gpio()` / `1586 stop_cps_gpio()` / `1602 monitor_cps_gpio()` 均**只在彼此之间调用**；
- 唯一对外调用点 `1647: # monitor_cps_gpio &` **已被注释**；
- `start_cps_gpio` 内部引用的 `CPS_HELPER` **在仓库中已不存在**（0 匹配），真正会执行的是 `/system/bin/gpioset gpiochip0 10=1 108=1`；
- `module/bin/` 只有 `penlog.sh`。
→ 整簇为死代码。**若被误启用**，`gpioset` 直驱 GPIO 与内核驱动的载波语义不同（v1 实验 F 组已证伪），风险大于收益。建议整簇删除。

### D.4 笔输入门控完全依赖 Hall

```java
boolean zInputEnabled = iDocked == 0 && !zDisconnected;   // SystemStylusHooks:1476
```

- `iDocked` 优先取 Java 侧 debounce 过的 `lastPenHall`，否则回落 `lenovo_pen_physical_docked`。
- **不依赖蓝牙**（设计正确：NVTCapacitivePen 走物理电容信号，不需要 BT）。
- **触发/后果**：Hall 误判"吸附" → `InputManager.disableInputDevice()` → **笔写不出字**（历史提交已踩过）；反向误判只是让笔在座上仍可写（次要）。
- **缓解**：Java 与 shell 各有一套独立 debounce（Java `lastPenHall` / shell `monitor_hall_capsule` 的 2 次采样）。两套可能分歧：**充电裁定用的是 shell 侧的 `lenovo_pen_physical_docked`，输入门控用的是 Java 侧 `lastPenHall`** —— 自洽但不对称，排查时要注意。

### D.5 复核为"设计正确、无缺陷"的两处（避免重复排查）

- **`real_bt_connected()` 的 `return 1`**：shell 中是非零退出码 = **假** = "未连接"，与注释一致，能正确压过僵尸 HOGP state=2。✅
- **`physical_docked` 不会被写成 `-1`**：`publish_hall_state` 的 3 个调用点（`service.sh:1005 / 1035 / 1251`）**全部**先经 `case "$docked" in 0|1)` 白名单；Java 侧 `setPhysicalDocked` 只写 0/1。`read_hall_state` 虽然会返回 `-1`，但被白名单挡住了。✅

---

## E. 待用户确认的判别实验（决定 C 的定性）

`C` 是"假阴性"还是"正确报忧"，取决于一个纯物理事实：**笔在满电吸附、驱动关 TX 之后，是否真的自行关机。**

判别步骤（需要手，3 分钟）：

1. 当前设备已处于目标状态：`lenovo_pen_powered_down=1`，笔吸附在座、100%。
2. **直接把笔从座上拿起，在屏幕上写字。**
   - **写得出字** → 笔还活着 → `powered_down` 是**假阴性**（判据需修）。
   - **写不出字** → 笔确实已关机 → 判据**正确**，问题回到"如何让笔不掉电"（固件行为，软件不可替代）。
3. 补充观察（可选）：`adb logcat -s LenovoPenBridge | grep 'pen input gate'` 看门控是否随拿起翻成 `true`。

辅助判据（不需要手，但区分度弱）：
- `dumpsys bluetooth_manager | grep '<MAC tail> .*hogp connection state'` —— 本项目已证该状态在笔掉电后**会滞留为 2**，所以它**不能**单独作为存活证据。
- `cat /sys/class/power_supply/cps_wls_tx/online` —— 已证与 TX 同秒归零，无额外区分度。

若要自动化，建议**主动 GATT 探活**：在 `powered_down` 判定前后各发一次 `LENOVO_HAPTIC_*` 写；写成功/收到响应即视为存活并清标志（现成的 `PenHapticGatt` 通道）。

---

## F. 建议的最小修复（按性价比排序）

### F1【高】让引擎守卫真正生效

`module/post-fs-data.sh`：去掉早跑调用（保留文件所在位置不变，仅移交时机）

```diff
-if [ -f "$MODDIR/guard_note_engine.sh" ]; then
-    chmod 0755 "$MODDIR/guard_note_engine.sh" 2>/dev/null
-    run_bounded 30 "$MODDIR/guard_note_engine.sh"
-fi
```

`module/service.sh`：等 boot 完成后跑，并重试

```sh
# --- note engine pin guard (post-fs-data runs before PackageManager is up) ---
(
    for _i in $(seq 1 60); do
        [ "$(getprop sys.boot_completed)" = 1 ] && break
        sleep 2
    done
    sleep 5
    for _r in 1 2 3; do
        [ -x "$MODDIR/guard_note_engine.sh" ] || break
        sh "$MODDIR/guard_note_engine.sh" && break
        sleep 10
    done
) &
```

`module/guard_note_engine.sh`：`pm path` 失败时回落到 glob（防御性）

```diff
 ENGINE_PATH="$(pm path "$NOTE_PKG" 2>/dev/null | head -n1 | sed 's/^package://; s#base.apk#lib/arm64/libSuniaEngine.so#')"
+[ -n "$ENGINE_PATH" ] && [ -f "$ENGINE_PATH" ] || \
+    ENGINE_PATH="$(ls -1 /data/app/*/com.coloros.note*/lib/arm64/libSuniaEngine.so 2>/dev/null | head -n1)"
+
 if [ -z "$ENGINE_PATH" ] || [ ! -f "$ENGINE_PATH" ]; then
```

### F2【中】把充电镜面的负值哨兵钳死

`hook/source/sources/…/PenBridgeReceiver.java`

```diff
-        try {
-            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", penState.charging);
-        } catch (Throwable unused3) {
-        }
-        HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", penState.charging);
+        // effectiveCharging() 会返回 -1 表示"未知"；settings / IPe 侧只认 0/1，
+        // 负数会被 `!= 0` 的消费者误读成"充电中"。这里统一钳到 0。
+        int safeCharging = HookUtils.effectiveCharging(context, penState.charging);
+        if (safeCharging < 0) {
+            safeCharging = 0;
+        }
+        try {
+            Settings.Global.putInt(context.getContentResolver(), "ipe_pencil_charging_state", safeCharging);
+        } catch (Throwable unused3) {
+        }
+        HookUtils.setIpePreferenceInt(context, "pencil_sp_charging_state", safeCharging);
```

同时建议把 `broadcastColorOs` 里三处 `putExtra("charging*", penState.charging)` 与

```diff
-boolean zPresent = iPhysicalDocked == 1 && penState.charging != 0;
+boolean zPresent = iPhysicalDocked == 1 && penState.charging > 0;
```

一并改为使用钳制后的值（否则广播侧仍会带 `-1` / 虚假 `present=1` 出门）。

### F3【中】解耦 `powered_down` 的两种用途

（先做 §E 判别实验，确认物理事实后再决定方向）

- 若判别为**假阴性**：`monitor_real_bt_state` 恢复为纯蓝牙驱动，`powered_down` 只保留给 `monitor_hall_capsule` 的重连门控。
- 若判别为**真掉电**：给标志补一条额外解除条件（如"一次成功的 GATT 探活"），避免离座后长期停留。

### F4【低】清理
- 删 `charge-guard.sh:111`（死键）。
- 删 `service.sh` 的 `start_cps_gpio` / `stop_cps_gpio` / `monitor_cps_gpio` 整簇。
- `tx_status=1` 三处加 `hall==docked` 前置判断，或直接移除（驱动会自己开）。

---

## G. 本轮验证方法与可复现命令

```sh
# 关键 settings 快照
for k in ipe_pencil_charging_state lenovo_pen_physical_docked \
         lenovo_pen_oem_charge_valid lenovo_pen_oem_charge_state \
         ipe_pencil_battery_level lenovo_pen_powered_down \
         lenovo_pen_link_connected ipe_pencil_present; do
  printf "%-34s = %s\n" "$k" "$(adb shell "settings get global $k" | tr -d '\r')"
done

# 内核节点（toybox ps -A 不显示脚本 cmdline，看存活要用 /proc/<pid>/cmdline）
adb shell "su -c 'cat /sys/devices/virtual/hall/och1909/hall3'"
adb shell "su -c 'cat /sys/bus/i2c/devices/11-0041/tx_status'"
adb shell "su -c 'cat /sys/class/power_supply/cps_wls_tx/online'"

# 守护日志
adb shell "su -c 'tail -30 /data/adb/modules/tb522fu_pen_bridge/pen-revive.log'"
adb shell "su -c 'tail -30 /data/adb/modules/tb522fu_pen_bridge/charge-guard.log'"
adb shell "su -c 'tail -20 /data/adb/modules/tb522fu_pen_bridge/pen-bridge.log'"

# 引擎守卫（注意：开机路径会 skip，手工跑才有效）
adb shell "su -c 'sh /data/adb/modules/tb522fu_pen_bridge/guard_note_engine.sh'"
adb shell "su -c 'tail -5 /data/local/tmp/note_engine_guard.log'"
```

**踩坑提醒**：本机 toybox `ps -A` 只显示进程名（`sh`），不显示 `sh /path/x.sh`，所以 `ps -A | grep charge-guard` 会**假阴性**——必须读 `/proc/<pid>/cmdline`。
