# 2a/2b 分析：唤醒 dock 深睡笔的软件通道（2026-09-21）

> 目的：评估「取下 dock 上已深睡的笔后，能否由软件唤醒」。**纯分析，无代码改动。**
> 背景：官方 ROM 五层取证已定案「充满断电 → 笔深睡 → 仅重吸附（线圈重激励）可唤醒」。

## 结论速览

| 方向 | 判定 | 一句话 |
|---|---|---|
| 2a nvt `pen_wakeup_*` 节点 | **原设想不成立** | 语义是"笔/手势唤醒**平板**"，不是"平板唤醒笔"；官方驱动无"呼叫笔"命令 |
| 2b BLE/GATT 下行通道 | **通道确认存在，待实验验证** | HID 层无下行，但 GATT vendor 服务有完整主机→笔命令帧（inkdye 的震动就在用它）；深睡时是否可达 = 核心未知数 |

---

## 2a：nvt_36xxx.ko 逆向（官方字节，563 个符号，完整符号表）

材料：`/tmp/official_super/kos/nvt_36xxx_official.ko`（官方 vendor_dlkm 提取，与设备 md5 一致）。

### 节点语义（反汇编实锤）

**`/proc/pen_wakeup_mode`**（`nvt_pen_wakeup_store` @0xb4d4）
- `echo 0|1 > …` → 存 `ts->pen_wakeup_flag`（ts 结构 +0x203）
- → `nvt_cmd_store(buf={0x7D, val}, 2)`：向触控 IC 发 **XCMD 0x7D**
- 读回 = flag 值（`nvt_pen_wakeup_show`）

**`/proc/pen_wakeup_switch`**（`nvt_pen_wakeup_switch_store` @0xb644）
- 存 `ts->+0x204` + 归一化 bool 到导出全局 `nvt_pen_wakeup_flag`（EXPORT_SYMBOL，本组 .ko 内无跨模块消费者）
- **不发任何 IC 命令** —— 纯软件门控

**消费者：`nvt_ts_wakeup_gesture_report`（@0x1384）**
- 解析 IC 事件包（事件 ID 0x1e / 0x1f）→ `input_event` 上报
- 受 `nvt_pen_wakeup_flag` / gesture_flag 门控
- **语义 = 息屏时笔/手势唤醒平板**（配合 `irq_set_irq_wake`）

**`nvt_hall_check_func`（@0x5150，250ms 周期工作队列，边沿去抖 ≥1001ms）**
- hall 读 0 → 向 IC 写 2 字节寄存器命令 **0x1150**
- hall 读非 0 → 若 `(gesture_flag | pen_wakeup_flag) != 0`：写 **0x1350** + `irq_set_irq_wake(irq, 1)`
- 这是吸附/离座边沿 → IC 的官方通知路径

### IC 命令族清单（`nvt_cmd_store` 调用者，全部 proc 可写）

| 命令 | 函数 | 用途 |
|---|---|---|
| 0xB1/0xB2 | glove_mode_set | 手套模式开/关 |
| 0x7D | pen_wakeup_set | 笔唤醒平板开关 |
| 0x47B | pen_type_set | 笔类型 |
| — | haptics_set | **触屏 IC 震动命令**（inkdye 的 `LenovoTouchscreenHaptics` 经 binder→vendor 服务最终到这） |
| — | support_pen / edge_reject / game_mode / high_report_rate / report_threshold / game_edge | 各类触控策略 |
| 0x1150 / 0x1350 | hall_check_func | 吸附/离座边沿寄存器命令（非 XCMD 通道） |
| — | set_pen_inband_mode_1 / set_pen_normal_mode | 仅 pen_diff 调试读数时切换，非唤醒 |

### 2a 判定

**驱动层不存在"平板/IC 主动唤醒深睡笔"的命令。** pen_wakeup_* 是反方向（笔唤醒平板）。
但拿到两个有用挂点：① hall 边沿→IC 命令（0x1150/0x1350）可以复刻/扩展；② **haptics IC 命令**说明面板 IC 有下行触笔的能力（深睡时是否被笔固件接收 = 未知）。

---

## 2b：BLE/HOGP 通道（关键材料 = inkdye APK jadx 反编译）

### HID 层：无下行

笔 HID 描述符（uhid `0005:17EF:622E`，debugfs rdesc 实读）只有 2 个 INPUT report：
- 0x01：鼠标（3 按键 + 相对 XY + 滚轮）——演示/空中模式
- 0x02：Consumer page（演示翻页按键）
- **无 OUTPUT、无 FEATURE report** → HID 层无主机→笔数据路径

### GATT 层：完整 vendor 命令面（inkdye `LenovoPenHapticConstants/Gatt`）

| 服务/特征 | UUID | 方向 | 帧格式（inkdye 实际使用） |
|---|---|---|---|
| **HAPTIC_SERVICE** | `00000000-000f-11e1-9ab4-0002a5d5c51b` | | |
| ├ IMP（单次震动） | 00000008-000f-… | **主机→笔** | `[waveId, level(0-5), repeatLo, repeatHi, cutOffLo, cutOffHi]` |
| ├ CON（连续摩擦） | 00000006-000f-… | **主机→笔** | `[waveId, level, startFriction(0/1), toolType(stylus=1/eraser=0)]` |
| ├ SWITCH | 0000000e-000f-… | 主机→笔 | 震动总开关 |
| ├ REQ_INF | 00000002-000f-… | 主机→笔 | `[1]` 请求笔信息 |
| └ INF_NOTIFY | 0000000a-000f-… | 笔→主机 | 信息上行 |
| COLOROS_PENCIL_INTERFACE_SERVICE | `18092dbc-2a69-11ec-…`（char `18093046-…`） | 主机→笔 | `[0x2C,01,1,1]`=查充电态；`[0x2C,08,1,1]`=查电量 |
| COLOROS_NOTIFY | 18093398-…（battery 180937bc / charge 1809396a） | 笔→主机 | 通知 |
| PARKER_SENSOR | 0000fe40-cc7a-…（charge fe41） | 笔→主机 | 传感器 |
| PARKER_6DOF | `00000000-699b-404a-a48e-6254941b956b`（data char 00000001） | 笔→主机 | 6DOF 数据 |
| 标准电池 | 180F（2A19/2A1A） | 笔→主机 | 电量 |

waveform ID 表：0=stop, 1=click, 2=connected, 3=haptic_enabled, 4=brush_change, 32=ballpen, 33=pencil, 34=chisel_marker, 35=eraser, 36=lenovo_brush。

### 关键推论

1. **主机→笔的实时命令通道已被 inkdye 在用**（书写震动就是它播的）——这不是猜测的通道，是生产路径。
2. 与日志互证：v0.1.17 修复震动时日志里的 "OEM haptic session" 恢复循环，恢复的就是**这条 GATT haptic 通道**。
3. 之前实测：长时吸附后取笔，**HOGP 能重连**（蓝牙 SoC 一直活着在监听）而触控 MCU 不发射 → BLE SoC 与触控 MCU 是分离的电源域。
4. **核心未知数**：深睡状态下，写 IMP/CON/REQ_INF/0x2C 帧到达的是 BLE SoC（可能仅本地处理）还是能转发唤醒触控 MCU？

### 2b 后续实验设计（待批准，不在本次执行）

| 实验 | 做法 | 判据 |
|---|---|---|
| E1 命中测试 | 笔深睡后（息屏吸附>17min），手机端写 `[1, 5, 0,0, 0,0]` 到 IMP：若笔震动 → BLE SoC 活且处理帧 | 笔是否震动 |
| E2 信息请求 | 深睡笔 + 写 REQ_INF `[1]`，看 INF_NOTIFY 是否回包 | 有回包 = GATT 协议栈活着 |
| E3 0x2C 命令空间探查 | HCI snoop 抓 inkdye/官方生态全部流量，枚举 0x2C 后的 cmd 字节；或小心 fuzz 0x00-0x1F | 找到 wake/power 类命令字 |
| E4 官方流量抓包 | 开发者选项 BTSnoop，官方场景下全流程录制（吸附/充满/断电/取下） | 官方是否有我们没见过的下行帧 |

若 E1/E2 证明深睡时 GATT 仍可达 → 2b 路线成立，继续找唤醒命令；
若不可达 → 2b 死路，回到方向 1（阻止入睡）或 3（体验兜底）。

## 附：工具与坑

- macOS Apple LLVM objdump 直接支持 aarch64 ELF：`objdump -d --start-address=… nvt.ko`；.ko 带完整 symtab，`nm` 定位函数边界后按地址切窗反汇编。
- 重定位查跨模块引用：`objdump -r x.ko | grep -B3 <symbol>`。
- 设备端读 HID rdesc：`mount -t debugfs none /sys/kernel/debug`（重启失效，只读操作）。
- jadx 已装（brew），inkdye 反编译产物在 `/tmp/inkdye/jadx_out/`（重启即失）。
