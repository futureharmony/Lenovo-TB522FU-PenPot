# 笔唤醒实验记录（2026-09-21 晚，E0–E4）

目标：验证 dock 上深睡的笔能否被"取下"后软件唤醒。
方法：自研 GATT 探针 APK（`/tmp/penprobe/`，广播/前台服务驱动，adb 全程可控），
笔 MAC `DC:EB:4D:06:77:DD`（Lenovo Tab Pen Pro 2，PARKER-V3 V1.26）。

## 0. 探针能力（已验证）

- 显式组件广播 `am start-foreground-service -n com.exp.penprobe/.ProbeService -a penprobe.<OP>`
  （shell 被 ColorOS 拦，必须 `su -c`）。
- 操作：`CONNECT` / `WRITE(uuid,hex)` / `READ(uuid)` / `LIST` / `DISCONNECT` / `STOP`。
- WRITE 自动重连：gatt 不可用时自动 connect，4s 后重试。
- 日志 tag `PenProbe`；服务常驻前台（connectedDevice 类型）。
- 陷阱记录：
  - `pm install` 要先 push 再 `pm install`（`adb install` 增量模式会卡死）。
  - `pm grant` 需 root（shell 无 GRANT_RUNTIME_PERMISSIONS）。
  - 改 Manifest 后必须重跑 `aapt2 link`（否则装的是旧 manifest）。
  - 广播间隙进程会被回收 → 必须前台服务。
  - 新版 `writeCharacteristic(c,val,writeType)` 在 ColorOS 上可能抛
    `SecurityException(BLUETOOTH_PRIVILEGED)` 或 rc=201(=GATT_WRITE_FAILED)，
    需多策略降级；连接失效时 rc=201 也出现（先 auto-connect 再写）。

## 1. 笔 GATT 全图（实测 dump，固件 V1.26）

| 服务 | 特征值 | 属性 | inkdye 用途 |
|---|---|---|---|
| `00000000-000f-11e1…` HAPTIC | `0002` REQ_INF | W | 请求笔信息 `[01]` |
| | `0006` CON | W/N | 连续摩擦 `[wave,level,friction,tool]` |
| | `0008` IMP | W | 单次震动 `[wave,level,repLo,repHi,cutLo,cutHi]` |
| | `000a` INF_NOTIFY | N | 笔信息回包 |
| | `000e` SWITCH | W/R | 震动开关 |
| `00000000-020f-11e1…`（inkdye 未用） | `0002`/`0004` W、`0006` N | | 待探测 |
| `00000000-699b-404a…` 6DOF | 0001/0005/0006 | R/N | 传感器数据 |
| `fe40` Parker sensor | fe41(W/N/R) fe42(N) | | 充电通知/6DOF 开关 |
| `1812` HID | 2a4c Control Point | W/N | **被本地栈拒写（rc=201）** |
| `fe59` Nordic DFU | 8ec90003 | W | 引导加载 |
| `5848…`/`165b…` 未知 vendor | 可写+Notify | | 待探测 |
| ColorOS 1809xxxx 服务 | — | — | **本固件不存在**（inkdye 常量为其他代笔） |

## 2. 实验结果

### E0 对照（清醒笔，座外）：✅ 通过
CLICK `[010503000000]`→`01050A000000` 写 IMP，GATT status=0，**用户确认笔震动**。
⇒ 下行通道完整：手机 → BLE SoC → 主 MCU → 马达。

### 深睡状态建立
17:53:17 驱动 close tx（`cps_wls_en:0`，满电即断），息屏，BLE 链路保持（link=1）。

### E1（失电 ~5–7 分钟，息屏，深睡）：✅ 震动
IMP 强帧 5 次 → GATT 成功，**用户确认震动**。
⇒ 深睡时主 MCU 仍可达，命令总线活着。

### E1b（取笔后失效状态，写不出）：❌ 不唤醒触控
CLICK 8 帧 + CON 摩擦 + REQ_INF + SWITCH[01] + 020f 服务探测（全部 GATT 成功送达）
→ **触控仍不恢复**。REQ_INF 无 NOTIFY 回包（清醒/深睡均无——需查通知使能）。
⇒ haptic/命令总线 ≠ 触控管线；已知命令面均无法唤醒触控。

### E3 意外发现（重大）：BT 重启 = 唤醒
重启蓝牙（`svc bluetooth disable/enable`）→ HOGP 重连 → **笔触控恢复（用户确认能写）**。
⇒ 存在可复现的纯软件唤醒路径（无需重新吸附）。
注：HCI snoop 抓取未成——ColorOS `cached_hci` 只缓存 cmd/event 不含 ACL 载荷；
`persist.bluetooth.btsnooplogmode=full` 已设置，待验证。

### 核心假设（待 E4 验证）
**唤醒触发器 = 新连接建立本身**（BLE SoC 收到新 encrypted connection → 通知主 MCU 开触控），
而非某条 GATT 命令。BT 重启唤醒 = HOGP 重连的副作用。
- 若 E4（失败状态下仅建立新 GATT 连接，不写任何命令）能唤醒 ⇒ 假设成立，
  模块可在检测到"离座+无发射"时自动 GATT 重连即可唤醒笔。
- 若 E4 失败 ⇒ 触发器在 HOGP（HID profile）层，需 bounce HOGP 而非普通 GATT。

## 3. E4 结果（18:33–18:41，决定性）

| 实验 | 操作 | 触控恢复 | 结论 |
|---|---|---|---|
| E4 | 纯新 GATT 连接（不写命令） | ❌ | 连接事件本身不是触发器 |
| E4b | 新连接 + CLICK 写入 | ❌ | 连接+任意命令流量不是触发器 |
| E4c（无效） | DISCONNECT_PENCIL→CONNECT_PENCIL | ❌ | **作废**：模块日志证明 CONNECT 时"already has a real link"，断链从未发生 |
| E4c-fix | 真 DISCONNECT（确认 link=0）→ 失连 5s → CONNECT（link=1） | ✅ **用户确认能写** | **触发器 = BLE 链路完全断开后重建** |
| （旁证） | `svc bluetooth disable/enable`（18:06） | ✅ | 同机制（HOGP 断开重建），但代价大 |

**最终结论**：深睡笔的唤醒触发器 = **自身 BLE 链路经历一次完整的 link-down → link-up**。
不需要 GATT 命令、不需要线圈、不需要重启蓝牙。之前所有失败（探针连接、haptic 写入）
都是因为笔的系统 HOGP 链路从未断过——探针只是第二连接。

**可落地方案（模块 v0.2 候选）**：
```
唤醒守护（可选，默认关闭）：
  条件：离座边沿 + lenovo_pen_link_connected=1 + N 秒 ev5 零事件（或手动触发）
  动作：DISCONNECT_PENCIL → 轮询 link_connected=0（≤3s 实测）
        → 失连保持 2–5s → CONNECT_PENCIL → link=1（≤3s 实测）
  成本：全程 ~10s，仅动笔的链路，不影响其他蓝牙设备
  实测：DISCONNECT 后 3s 内 link=0；CONNECT 后 3s 内 link=1 且笔唤醒
```
另需修掉 E4c 暴露的坑：`CONNECT_PENCIL` 在已有链路时会静默跳过
（"explicit pen connect already has a real link"）——唤醒流程必须以
`link_connected=0` 为前置确认，否则会复现"假重连"。

## 3b. 后续：唤醒后书写震动丢失（19:00–19:15）

**现象**：E4c-fix 唤醒成功后，书写无震动反馈。

**排查链**：
1. 探针进程残留（auto-reconnect 占着一条 GATT）→ 已杀。
2. 系统侧 haptic 管线**完好**：18:53:46 logcat 实证 Hook(system) 检测到落笔 →
   `PenHapticGatt.startWriting` → 蓝牙进程 CON `20050101` 写笔 `status=0`——链路通、笔 ACK，但笔不播。
3. 对照 inkdye 连接握手（`LenovoPenHapticGatt$callback$1.onServicesDiscovered`，hapticReady 时）：
   `LenovoTouchscreenHaptics.enable()` → +160ms `setHapticSwitch(true)`（SWITCH=`01`）
   → +260ms `setParker6DofSwitch(true)`（6DOF CCCD）→ +420ms `requestPenInfo()`（REQ_INF=`01`）
   → +460ms flushPendingContinuous → +800ms **`playImpact(WAVE_FORM_ID_CONNECTED=2)`**。
4. **结论：唤醒（link cycle）重置了笔端震动会话，系统侧无人重放握手。** 实验期已写过
   SWITCH/REQ_INF，缺的正是收尾的 CONNECTED 波形。

**修复重放**（19:11，经探针，全部 status=0）：
`SWITCH(000e)=01` → `REQ_INF(0002)=01`（笔端 28ms 回 INF_NOTIFY `01 00 01 0d …`，与清醒态一致）
→ `IMP(0008)=020501000000`（CONNECTED 波形）→ `CON(0006)=20050101`→`00000000`（模拟落笔）。

**✅ 实测确认（19:19 用户反馈"震动了"）**：重放握手后书写震动恢复，无需重启蓝牙。
至此完整定案：
- **唤醒触发器** = 笔自身 BLE 链路完整 link-down → link-up（§3 E4c-fix）。
- **唤醒副作用** = link cycle 重置笔端震动会话，系统侧无人重放握手 → 书写无震动。
- **修复** = 重连后重放 inkdye 握手：`SWITCH=01 → REQ_INF=01 → IMP(0008)=020501000000 → CON=20050101`。

**唤醒守护实现时必须把本握手序列并入唤醒动作（CONNECT 成功后追加，不可省略）。**

## 4. 实验环境遗留
- 探针 APK `com.exp.penprobe` 保留在设备（前台服务仅手动启动；源码 `/tmp/penprobe/`，
  重启即失，需保留请另存仓库）。
- `persist.bluetooth.btsnooplogmode` 已恢复默认；`bluetooth_hci_log_enabled=1` 保留。
- ColorOS `cached_hci` 只缓存 HCI cmd/event（无 ACL 载荷），API 级 Hook 是抓 ATT 写值的
  更优路径（未实施）。
