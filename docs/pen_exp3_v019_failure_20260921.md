# 实验 3 失败分析：v0.1.9 修复未被触发（2026-09-21 11:27–11:38）

> **状态**：实验 3 **失败**（修复未触发）→ 实验 3c **通过**（救助后可写）→ **方向确认，待选实现方案**。
>
> **一句话结论**：v0.1.9 的修复逻辑本身没错（救助实验两次证明它有效），但它被挂在两个
> **同时失效的触发条件**后面 —— `powered_down=1`（本次未置位）与 `! real_bt_connected`
> （僵尸连接下恒假）。结果离座边沿**一个动作都没发**，笔保持休眠，书写无信号。
>
> **更深一层**：`powered_down=1` 作为"失败指纹"的假设被本次实验证伪 —— 失败根本不需要
> 90 秒静置，**47 秒就够**。
>
> **闭合（§13）**：救助后笔**全程未吸附**即可正常书写（744 条笔尖事件，含压力曲线与倾角）。
> ⇒ 笔不需回座唤醒，硬重连**有效且充分**；缺的只是触发条件。
> 同时新增三条约束，**证伪了「监听链路静默」类方案**（正常态静默 3.5 分钟也无碍），
> 并把 GATT 探测类方案降级为"须先实测"。

---

## 1. 用户操作与时间线

用户按真实失败路径操作（吸附 → 锁屏静置 → 解锁 → 拿笔直接写），结果**写不出**。

| 墙钟 | 事件 | 证据来源 |
|---|---|---|
| 11:27:14 | `sys.boot_completed=1`（v0.1.9 首次开机） | adb |
| 11:27:22.274 | `pen link state disagrees: HID Host profile=true live uhid link=false; reporting connected` | hook log |
| 11:27:43–44 | **ev5 最后一次笔尖事件**（一次短暂落笔，0.4 秒 / 305 行） | `cap_ev5.log` |
| 11:27:48 | **吸附**：`real Hall state docked=1 battery=100 charging=1` | pen-bridge.log |
| 11:27:49–11:28:20 | 用户触屏操作 141 次 | `cap_ev4.log` |
| **11:28:20–11:30:50** | **锁屏静置 2.5 分钟**（触屏事件完全空白） | `cap_ev4.log` |
| **11:30:25** | **满电停充**：`driver closed TX at full (battery=100)` | charge-guard.log |
| 11:30:50–11:31:10 | 解锁（触屏 230 次） | `cap_ev4.log` |
| **11:31:12.362** | **离座**：`real Hall state docked=0` | pen-bridge.log |
| 11:31:12.372 | `pen input gate -> true (docked=0 disconnect=false hid=true)` | hook log |
| 11:31:14 | `com.coloros.note` 启动 | hook log |
| 11:31:26.075 | `adaptive pencil 120 Hz released`（系统认定笔已不活跃） | hook log |
| **书写窗口** | **ev5 / ev8 / ev9 全部零事件** | 采集 |
| 11:33:50 | 救助实验：`DISCONNECT` → `HOGP 2→0`；`CONNECT` → `HOGP 0→2` | rescue_test.sh |
| 11:34:06.390 | 笔回包：`notify value=01 00 01 0d ...` | hook log |

**关键时间量**：停充 11:30:25 → 离座 11:31:12 = **47 秒**。

---

## 2. 直接原因：离座边沿「零动作」

`module/service.sh:1308-1330`（v0.1.9 的 F-C）：

```sh
elif [ "$state" = 0 ] && [ "$previous" = 1 ]; then
    am broadcast ... DISMISS_PENCIL_CAPSULE
    left_powered_down=$(settings get global lenovo_pen_powered_down)   # ← 本次 = 0
    if [ "$left_powered_down" = 1 ]; then
        ( request_pen_reconnect_hard ) &                              # ← 未进入
    elif ! real_bt_connected; then
        request_pen_connect                                           # ← 未进入
    fi
fi
```

两个条件**同时不成立**：

| 条件 | 期望 | 实际 | 原因 |
|---|---|---|---|
| `left_powered_down = 1` | 识别"满电静置后被拿走" | **0** | 47s < 90s 阈值（`POWERED_DOWN_AFTER_SEC`），revive-guard 未置位 |
| `! real_bt_connected` | 链路未连才重连 | **假** | 僵尸连接下栈自报 `HOGP=2`，判据为真 |

`pen-bridge.log` 直接印证 —— 11:31:12 打印 `real Hall state docked=0` 之后**再无任何输出**，
没有 `OEM CONNECT_PENCIL`、没有 `DISCONNECT`、没有 `request_pen_reconnect_hard` 的任何痕迹。

---

## 3. 为什么 `powered_down` 没置位

`pen-revive-guard.sh:213-225` 的置位条件是「在座上 + `tx=0` + `online=0` + **持续 ≥90 秒**」。
`pen-revive.log` 全文（本次）：

```
11:27:54  pen docked: tx=1 online=1 cap=100
11:30:25  tx off  0s/90s; waiting (online=0 cap=100)     ← 满电停充，开始计时
11:30:40  tx off 15s/90s; waiting
11:30:55  tx off 30s/90s; waiting
11:31:10  tx off 45s/90s; waiting                         ← 用户此刻拿起笔
11:31:25  pen detached; session ended, powered_down flag held   ← 标志保持 0
```

**阈值 90 秒，实际静置 47 秒 —— 差 43 秒。**

> ⚠️ **这不是"用户静置时间不够"，而是阈值设计假定了一个不成立的前提。**
> 90 秒阈值源自「区分『正常充满静置』与『笔已掉电』」，但本次证明：
> **笔在满电停充后 47 秒内就已进入休眠**（见 §5），失败在这一刻已经注定。

---

## 4. 为什么 `real_bt_connected()` 报「已连接」

`real_bt_connected()`（v0.1.9 的 F-A，已解耦 `powered_down`）现在是**纯栈判定**：

```sh
bt_stack_hogp_live() {   # 读 dumpsys，匹配 "<tail> .*hogp connection state=2"
    dumpsys bluetooth_manager | tr 'A-Z' 'a-z' \
        | grep -E "$tail .*hogp connection state=2" >/dev/null 2>&1
}
```

而本次**全程** `HOGP=2`：

- `cap_state.log` 163 个采样，`HOGP` 恒为 2（从 11:27:43 到 11:31:39）
- `lenovo_pen_link_connected` 恒为 1，`ipe_pencil_present` 恒为 1
- `dumpsys` 原文：`xx:xx:xx:xx:77:dd : selected transport=2 hid connection state=0 hogp connection state=2`
- `stack::gatt ... state: GATT_CH_OPEN, ACL holders gatt_if: hid (49), BatteryService (53)`，
  且 **11:27:21 之后再无任何断开事件**

**⇒ 蓝牙链路在栈层面从头到尾没有断开过。** 所以判据"正确地"返回真，
`elif ! real_bt_connected` 不成立。

> **F-A 解耦在这里是负向的**：旧代码因 `powered_down=1` 短路**意外地**让判据返回假，
> 从而发出了（普通）`connect`；解耦后这个副作用消失，判据恒真，**连试都不试**。

---

## 5. 笔到底发生了什么：面板通道静默

笔的书写信号走 **`NVTCapacitivePen`（`/dev/input/event5`）** —— 面板侧主动笔接收通道，
与蓝牙 HID（`event8/9`）是两套独立通路。

| 通道 | 事件文件 | 本次结果 |
|---|---|---|
| 面板数字笔（书写） | `cap_ev5.log` | 305 行，**全在 11:27:43–44**；11:31:12 之后 **0 行** |
| 触屏（手指） | `cap_ev4.log` | 371 行，最后一条 **11:31:10**（解锁动作） |
| 笔按键/手势（HOGP） | `cap_ev8/9.log` | ev8 = **0 字节**；ev9 = 5 行（11:27:45，开机初期） |

**⇒ 笔在离座后没有向面板发射任何信号。** 而同时蓝牙侧"连通着"（HOGP=2、GATT_CH_OPEN）。

**解释**：主动笔在满电停充后进入低功耗休眠 —— 停止面板发射，但主机侧 BLE 连接未被拆除
（笔休眠前未发 LL_TERMINATE），于是形成**判据不可见的失联**。

---

## 6. 救助实验：笔活着，修复方向正确

11:33:50 在失败现场直接跑 `rescue_test.sh`（零用户操作、不改代码）：

```
before   HOGP=2                                   ← 僵尸/静默态
DISCONNECT  → HOGP=0        （<2 秒）
CONNECT     → HOGP=0→2      （+6 秒）
```

**真实重建证据**（不是乐观置位）：

```
11:34:03.847  stack::gatt GATT_CH_OPEN （ACL holders 全部换新：BatteryService(63), ipemanager(62)）
11:34:05.341  Lenovo OEM GATT completed read 0000fe41-... status=0
11:34:05.671  IPe OEM s0 read 2a19 value=64  → IPe BLE hardware battery sample=100
11:34:06.126  Lenovo OEM GATT write 0000000e-... status=0
11:34:06.390  IPe OEM s0 notify value=01 00 01 0d 00 00 00 00 1f b5 00 24 00 94 8d 00   ← 笔回包
11:34:07.838  adaptive pencil 120 Hz enabled
```

**⇒ 笔没有关机，能被蓝牙连接请求唤醒。** 这与 11:12 那次实验结论一致。

**⇒ v0.1.9 的修复动作本身是有效的 —— 它只是从未被执行。**

---

## 7. 顺带发现：uhid 重新枚举 + 旧节点失效

硬重连后，笔的 HID 输入设备编号发生变化：

```
重连前： 9: NVTCapacitivePen | 10: ...Mouse | 11: ...Consumer Control
重连后： 9: NVTCapacitivePen | 12: ...Mouse | 13: ...Consumer Control
```

两个后果：

1. **`live uhid link=false` 的成因**：hook 缓存的是旧设备节点，重新枚举后判据失效 ——
   `11:27:22.274` 与 `11:34:07.837` 两次都报 `disagrees ... live uhid link=false;
   reporting connected`，即**整个会话 uhid 侧从未被判为活**。
2. **采集脚本会漏事件**：本次采集固定盯 `event8/9`，而重连后笔设备已迁到 `event12/13`
   ⇒ 后续实验必须**按设备名动态发现节点**，不能写死节点号。

---

## 8. 与 v0.1.8 那次失败的对比（认知修正）

| | v0.1.8 复现（11:02 场景） | v0.1.9 复现（本次 11:31 场景） |
|---|---|---|
| 停充 → 离座 | 10:59:00 → 11:02:08 = **188 秒** | 11:30:25 → 11:31:12 = **47 秒** |
| `powered_down` | **1**（已过 90s 阈值） | **0**（未过阈值） |
| `real_bt_connected()` | 假（被 `powered_down` 短路） | 真（纯栈读，僵尸 HOGP=2） |
| 离座边沿动作 | 发**普通** `connect` → 被 `not disconnected. state=2` 拒绝 | **零动作** |
| HOGP | 2 | 2 |
| 结果 | 写不出 | 写不出 |

**两条重要修正：**

1. 我此前把上一次的 `connected=0`（11:00:50）解读为"物理链路断开的证据"。
   现在看它**很可能是 `powered_down=1` 短路后写进镜像的结果**，属于循环论证
   —— 两个场景的真实栈状态同样是 `HOGP=2`。
2. **`powered_down=1` 不能作为失败场景指纹**：它的置位需要 90 秒，而失败只需 47 秒。
   本次它保持 0，失败照常发生。

---

## 9. 根本矛盾

```
        笔休眠（停止面板发射）
                │
        主机侧 BLE 未拆链（无 LL_TERMINATE）
                │
   ┌────────────┴────────────┐
   │  HOGP state = 2（僵尸）  │  ← 栈认为"连着"
   └────────────┬────────────┘
                │
   real_bt_connected() = 真
                │
   "需要重连吗？" → 不需要
                │
        离座边沿零动作 → 笔一直睡着
```

**`HOGP state=2` 分不清「真连接」与「笔已休眠但链路未拆」。**
只要判据建立在这上面，重连决策就是掷硬币 —— 上一次靠 `powered_down` 的副作用蒙对，
这一次副作用消失，就全错。

---

## 10. 修复方向（候选，本次未实施）

> 用户明确要求"先不急着修改"。以下仅记录思路，供选定后再动手。

**D1【推荐】离座边沿引入「链路活性」判据，替代栈状态**

栈状态不可信，就实测活性。可选实现：

- **D1a 笔尖活性闭环**：离座后等 ~2 秒，若 `event5`（NVTCapacitivePen）无事件
  ⇒ 笔未发射 ⇒ 硬重连。代价小、判据诚实（直接测"用户能不能写"）。
  需按设备名动态发现节点（见 §7.2）。
- **D1b GATT 探测**：hook 侧发一次 GATT read，超时无响应 ⇒ 硬重连。
  由 Java 侧做（模块 shell 做不了 GATT），更精确但改动面大。

**D2 降低 `POWERED_DOWN_AFTER_SEC`**

90s → 例如 30s。**单独使用不够**：它只解决"指纹太晚"，不解决判据恒真；
且过短会把"短暂拿起放下"误判为掉电。**不宜作为主修复**。

**D3 离座边沿无条件硬重连**

最简单，但用户"拿起笔立刻写"会吃 ~6 秒延迟，**体验代价不可接受**，除非配合活性判据。

**D4 吸附/离座边沿监听笔的 BLE 数据流**

若笔在座上静置期间仍在发 notify（本次 11:30:14 后即无），可据此更早判定休眠。
→ **⚠️ 本节结论已被 §13.4(a) 证伪**：救助后笔正常工作时也静默 3.5 分钟，静默≠休眠。

**D5【新增】停充后周期性极短 TX 脉冲，保持笔的发射子系统清醒**

见 §13.5。治本方向，需先做零代码脉冲验证。

**倾向**：**D1a**（模块侧自足、判据诚实、与现有 Hall 监视循环天然契合）。
若 D1a 的 2 秒窗口不够，再叠加 D1b。
**本节候选的最终复核见 §13.5**（D1b 降级、D4 证伪、新增 D5）。

---

## 11. 证据清单

| 文件 | 内容 |
|---|---|
| `/tmp/exp3_final/cap_state.log` | 163 采样，`HOGP=2` / `link_connected=1` 全程恒定 |
| `/tmp/exp3_final/cap_ev5.log` | 笔尖事件，全在 11:27:43–44，之后归零 |
| `/tmp/exp3_final/cap_ev4.log` | 触屏事件，确认 11:28:20–11:30:50 锁屏窗口 |
| `/tmp/exp3_final/cap_hook.log` | 门控 `gate -> true`、`120 Hz released`、`uhid disagrees` |
| `/tmp/exp3_final/module_logs.txt` | pen-bridge / pen-revive / charge-guard 三份日志合并 |
| 设备 | `/data/adb/modules/tb522fu_pen_bridge/pen-revive.log`（置位计时铁证） |

**复现命令**

```sh
A=/opt/homebrew/bin/adb
ADB=$A bash scripts/deploy_vector.sh                 # 装 v0.1.9 并重启
$A shell 'sh /data/local/tmp/exp2/launch_v2.sh 1800' # 起采集
# 用户操作：吸附 → 锁屏静置 → 解锁 → 拿笔写
$A shell 'tail -3 /data/local/tmp/exp2/cap_state.log'
$A shell "su -c 'cat /data/adb/modules/tb522fu_pen_bridge/pen-revive.log'"
# 失败现场救助（零操作）
$A shell "su -c 'sh /data/local/tmp/exp2/rescue_test.sh'"
```

---

## 12. 待验证（2026-09-21 11:38 更新）

- [x] ~~救助后请用户试写~~ ⇒ **写得出**（11:38:19–11:38:26，744 条笔尖事件），且全程 `docked=0`。见 §13。
- [x] ~~笔在座上静置期间是否真的完全停止 notify~~ ⇒ 失败窗口内 hook 侧零 BLE 记录。
  **但 §13.4(a) 反证**：「链路静默」在**正常态**同样存在 ⇒ **不可作休眠判据**。
- [ ] `cap_ev8/9` 采集必须改为按设备名动态发现节点（§7.2），否则漏测。

---

## 13. 实验 3c：救助后可写（用户实测）—— 结论闭合

时间 2026-09-21 11:38:19–11:38:26｜模块 v0.1.9｜Hook v4.6.0

### 13.1 用户反馈

> 「写得出」—— 且笔**全程未吸附**，一直处于物理分离状态。

### 13.2 证据

**状态机只有三个变化点**（301 个 1 秒采样）：

```
11:27:43  link=1  HOGP=2   ← v0.1.9 重启后基线
11:33:52  link=0  HOGP=0   ← rescue DISCONNECT 生效（11:33:50 发起）
11:34:09  link=1  HOGP=2   ← rescue CONNECT 真实重建，此后 301 采样恒定
```

书写窗口（11:38:19–11:38:26）逐样本 `physical_docked=0`。

| 采集 | 值 | 判读 |
|---|---|---|
| `cap_ev5`（NVTCapacitivePen） | 1049 行：**744 行集中在 11:38:19–11:38:26**；另有 305 行在 11:27:42（开机残留）；中间 **630 秒全静默** | 新事件 = 本次书写 |
| 事件构成 | `ABS_X` 223 / `ABS_Y` 224（各 **164 个唯一值**，min 0，max 20763 / 30030）、`ABS_PRESSURE` 143（**113 值 / 105 唯一**，0→4265）、`ABS_TILT_X` 41 / `ABS_TILT_Y` 43、`BTN_DIGI` 7、`BTN_TOUCH` 6、`ABS_DISTANCE` 96 | **带压力曲线与倾角的连续笔迹**，非悬停 |
| `cap_ev8` / `cap_ev9` | 0 字节 / 5 行（全在 11:27:44，开机残留） | 本轮未按笔键 |

**模块侧**（`pen-bridge.log`）：

```
11:33:52  OEM DISCONNECT_PENCIL requested
11:34:07  OEM CONNECT_PENCIL requested
11:34:11  explicit pen reconnect confirmed by HOGP attempt=1   ← F-A 解耦后确认判据正常
11:34:29  real BT state mirror connected=1 (was 1)
```

⇒ **F-A（`bt_stack_hogp_live` 解耦）本次生效**：重连被 HOGP 实测确认，镜像不再假阴性。

**Hook 侧**：

```
11:34:04.807  metadata firmware=PARKER-V3 V1.26 serial=HVE70MYJ   ← 笔活着
11:34:05.677  IPe BLE hardware battery sample=100
11:34:06.390  notify value=01 00 01 0d 00 00 00 00 1f b5 00 24 00 94 8d 00  ← 笔回包
11:34:07.839  real HID connected: pen input gate restored
11:34:08.447  ColorOS pen state synced: connected=true battery=100
```

### 13.3 结论

| 问题 | 答案 |
|---|---|
| 笔是否需要回到充电座才能唤醒 | **不需要**（全程 `docked=0`） |
| 硬重连（DISCONNECT→CONNECT）是否有效且充分 | **有效且充分** —— 11:02 失败 / 11:12 成功 / 11:34 成功，唯一变量都是中间那次 DISCONNECT |
| v0.1.9 的修复是否无效 | **不是。动作有效，缺的是触发条件**（实验 3 已证） |
| 失败发生在哪一层 | 笔的**主动笔发射子系统**休眠；**蓝牙子系统全程存活** |

### 13.4 三条新约束（用于筛掉不可行方案）

**(a) 「链路静默」不能作为休眠判据。**
救助后笔在 `11:34:54` 上报一次 `fe41 = 0b 00`，此后**静默 3.5 分钟**（至 `11:38:23`），
而这段时间笔完全正常（用户 11:38 顺利书写）。
⇒ 「多久没收到 notify 就判定休眠」**不成立**。
（`0000fe41` 在代码中是 `LENOVO_DFU_NOTIFY`（`OemGattProtocolHooks.java:49`），
是**孤立的按需上报**，非周期性心跳。）

**(b) 休眠只影响面板发射，不影响 BLE 承载。**
失败窗口（11:31:12–11:33:50）内 `GATT_CH_OPEN`、`ACL holders gatt_if/hid` 在、`HOGP=2`，
但 `event5` 零事件。
⇒ 任何"探测 BLE 是否响应"的判据，**很可能得到"响应正常"**，不足以判定休眠
—— 除非实测证明休眠态下 GATT read 会超时。

**(c) 稳态下主控对笔零探询。**
失败窗口内 hook 没有任何针对笔的 read/notify 轮询（仅在连接建立时批量读一次）。
这既是"休眠不可见"的成因，也说明新增探询属于**架构级新增能力**。

### 13.5 候选方案复核（相对 §10）

| 编号 | 方案 | 本轮复核结论 |
|---|---|---|
| **D1a** | 离座后 N 秒内 `event5` 无事件 ⇒ 硬重连 | ✅ **仍主推**。直接测"能否写"，模块侧自足，不受 (a)(b) 影响 |
| D1b | hook 侧 GATT read 超时 ⇒ 硬重连 | ⚠️ **降级**。受 (b) 制约，须先实测休眠态 GATT 是否真的超时 |
| D2 | 降低 `POWERED_DOWN_AFTER_SEC` | ❌ 不可作主修复 |
| D3 | 离座无条件硬重连 | ⚠️ 可作 D1a 的简化版；代价是"拿起即写"吃 ~6 秒 |
| D4 | 监听 BLE 数据流静默 | ❌ **本轮证伪**（见 (a)） |
| **D5【新】** | 停充后周期性发出极短 TX 脉冲，保持笔的发射子系统清醒 | **治本方向**：休眠由 CPS 关 TX 引起，而"重新吸附"能唤醒笔 ⇒ 线圈激励是有效唤醒手段。须实测脉冲宽度/间隔与副作用（发热、寿命、误充电） |

### 13.6 实施：D1a（用户 2026-09-21 11:44 选定）

**决策**：走 **D1a** —— 用「笔尖节点的短窗口活性」替换掉两个已证伪的离座触发条件。

#### 13.6.1 为什么不是另外两条

- **D5（微脉冲）** 是治本方向，但需要先在休眠态验证「线圈激励能否唤醒发射子系统」，
  属硬件侧未知数，周期长。
- **D1b（GATT 探测）** 已被 §13.4(b) 降级：休眠不拆 BLE 承载，探测很可能得到「正常」。
- **D1a** 直接测「笔会不会发射」，是唯一不被 (a)(b) 两条约束影响的判据，且模块侧自足。

#### 13.6.2 代码改动（`module/service.sh`，v0.1.10）

| # | 内容 | 位置 |
|---|---|---|
| 1 | 新增 `PEN_TIP_DEVICE_NAME` / `INPUT_DEVICES_FILE` / `PEN_TIP_ALIVE_WINDOW=2` / `PEN_HARD_RECONNECT_DEDUP*` | 常量区，`pen_cps_responsive()` 之后 |
| 2 | 新增 `pen_tip_event_node()`：按**设备名**从 `/proc/bus/input/devices` 解析 evdev 节点 | 同上 |
| 3 | 新增 `pen_tip_alive_within()`：**三态**返回，严格区分「没事件」与「判不了」 | 同上 |
| 4 | 重写离座分支：栈真断 → 直接硬重连；栈报「在」（可能是僵尸）→ 用活性判据裁决 | `monitor_hall_capsule()` |
| 5 | `request_pen_reconnect_hard()` 增加 **10 秒去重** | 函数入口 |

**离座分支新逻辑**（整个过程放后台子 shell —— 活性窗口要 2 秒，不能阻塞 Hall 循环）：

```sh
if ! bt_stack_hogp_live; then
    ( request_pen_reconnect_hard ) &          # 链路真断：不等窗口
else
    (
        pen_tip_alive_within "$PEN_TIP_ALIVE_WINDOW"
        pen_tip_state=$?
        case "$pen_tip_state" in
            1) ... request_pen_reconnect_hard ;;   # 窗口内零事件 -> 疑似休眠
            0) ... link kept ;;                    # 有事件 -> 笔在发射
            *) ... liveness unknown（保守不动）;;
        esac
    ) &
fi
```

**三态设计的关键理由**：把「工具故障」误当成「休眠」会让**每次离座都无谓重连**（6 秒不可用）。
所以 `getevent` 只有返回 **124**（超时）才判为静默；节点打不开、工具异常一律走 `2`（不动）。

#### 13.6.3 实现前的设备实测（三个必须确认的事实）

| 实测项 | 结果 | 意义 |
|---|---|---|
| `NVTCapacitivePen` 的 evdev 节点 | **`/dev/input/event5`**（`Handlers=event5 cpufreq`） | ⚠️ **`dumpsys input` 里的设备 id（`9: NVTCapacitivePen`）不是 evdev 节点号** —— 两者是不同命名空间。本机该设备实际挂在 **event5** |
| `timeout 2 getevent -c 1 <node>` 无事件时 | **exit=124**，耗时 2.046s | 超时语义干净，`-c` 参数被该 ROM 接受 |
| 节点解析函数输出 | **`/dev/input/event5`** | 按名字解析正确 |
| 活性窗口连跑 3 轮 | 全部 `rc=1` / 耗时 2s，**无 `rc=2`** | 正常环境下不会降级 |

#### 13.6.4 `rc=0` 路径的验证限制（**遗留风险**）

有事件时 `getevent -c 1` 应返回 0 —— 这条路径**未能自主验证**：

- 尝试用 `sendevent /dev/input/event5 0 0 0` 注入一条**空 EV_SYN**（无坐标/无按键/无压力，
  理论零 UI 副作用）来触发它。`sendevent` 返回 0（写入成功），**但 getevent 仍然超时**。
- ⇒ 该内核（6.6.82）下 **`evdev_write()` 注入的事件不会广播给其他 evdev 客户端**。
  这是 `sendevent` 的语义限制，**不是 `getevent` 的问题**（§13.2 的 744 条真实笔迹事件
  已经证明 getevent 能从同一节点读到真实事件）。
- **因此**：`rc=0` 依赖 `getevent -c <n>` 的标准行为（读到 n 个即 exit(0)）。
  部署后需在真实书写时做一次端到端确认 —— 若判据误判，症状是「每次拿笔都要等约 6 秒」，
  日志会出现 `pen tip silent ... assuming sleeping emitter` 而实际写得出来。

#### 13.6.5 收益与剩余风险

- **收益**：离座边沿从「零动作」变成「按笔尖实际活性决策」。命中失败场景时延迟 ≈ 2 秒（窗口）
  + 6 秒（重连）≈ 8 秒，之后可写；而现状是**永久不可写**。
- **误判方向**：用户拿笔但暂不靠近屏幕 → 会无谓硬重连一次（6 秒）。代价可接受，
  且用户真正开始写时链路已经好了。
- **`powered_down` 已完全退出该决策**（它在实验中既太慢又恒真），但**未删除** ——
  `real_bt_connected()` 在 `docked=1` 时仍用它，吸附边沿逻辑不变。

#### 13.6.6 未实施（保留待办）

- **D5 零代码脉冲实验**：在休眠态下发一次 `tx_status=1` 短脉冲，观察 `event5` 是否恢复发射。
  若成立，可把「离座后补救」升级为「离座前预防」，消除那 8 秒。
- **D1b GATT 探测实验**：仍需实测休眠态 read 是否超时，以确认 §13.4(b) 的推断。

---

## 14. D1a 部署后的通路审计：OEM CoreService 在「未解锁窗口」不可达（v0.1.11 → v0.1.13）

### 14.1 起因

v0.1.10 部署重启后，模块日志连续三次 `OEM CONNECT_PENCIL request failed`
（11:49:43 / 11:50:13 / 11:50:43），而基线本身是正常的
（`physical_docked=0 powered_down=0 link_connected=1 HOGP=2`）。
D1a 的硬重连依赖 `am startservice … com.oplus.ipemanager/.btadsorb.CoreService`，
必须先确认这条通路在什么条件下可用。

### 14.2 A/B 实测：唯一变量是「本次开机是否解锁过」

| 用户状态 | `cmd package query-services -n …/CoreService` | `am startservice … CONNECT_PENCIL` | IPeManager 进程 |
|---|---|---|---|
| `RUNNING_LOCKED` | `No services found` | `Error: Not found; no service started`（rc=255） | 未运行 |
| `RUNNING_UNLOCKED` | `1 services found` | rc=0 | 随即被拉起 |

- 锁定侧两次独立复现：wall 20:0x 与设备钟 12:09:52（重启后未解锁，包已登记）；
- 解锁侧两次独立复现：wall 20:05、以及 12:03 那次启动（模块日志 12:03:39 `requested`）。

### 14.3 机制（三条独立成因，日志里要能区分）

1. **未解锁 → PMS 过滤组件**。`com.oplus.ipemanager` 的 `privateFlags` 含
   `PARTIALLY_DIRECT_BOOT_AWARE`，但 `CoreService` 自身不是 direct-boot-aware；
   用户未解锁时 PMS 在 `resolveService` 阶段把它滤掉，AMS 按「服务不存在」处理。
   **不是** ROM 的 am 缺陷。
2. **模块自己的 `com.aclaniakea.penhidctl` 同样没有 direct-boot-aware 标记**
   （`dumpsys package` 的 flags 里搜不到 `DIRECT_BOOT_AWARE`）⇒ 该窗口内
   **OEM 与 HID 两条通路同时不可达**。
3. **调用身份**：以 **shell(uid 2000)** 身份调用会被 `Requires permission
   com.oplus.permission.safe.IOT` 拒绝；模块以 **root(uid 0)** 运行不受此限
   —— 这解释了为什么早期用 `adb shell am startservice` 手测总是失败。

### 14.4 影响范围（**极易被高估**，故写清边界）

`RUNNING_LOCKED` 只存在于**开机 → 该次开机首次解锁**之间。设备只要被解锁过一次，
此后即使关屏、停在锁屏界面，用户仍是 `RUNNING_UNLOCKED`。

- 用户复现路径（吸附 → 锁屏 → 2 分钟 → 解锁 → 取笔书写）**全程处在解锁态**，
  **D1a 主路径不受影响**；
- 唯一会撞上的是「开机后首次解锁前」这一小段 —— 实测 11:49 与 12:09 两次启动，
  模块的 OEM 会话恢复全程失败（见 §14.7）；
- 12:03 那次启动可作对照：解锁定位于 **12:03:38.5**，同一条调用 **0.5 秒后**
  （12:03:39）即成功并完整重建 GATT 会话（services/characteristics、固件
  PARKER-V3 V1.26、电量 0x64）。

### 14.5 实施（v0.1.11 → v0.1.13）

| 版本 | 内容 | 关键取舍 |
|---|---|---|
| v0.1.11 | 引入「OEM 通路不可达就挂起」状态：pending 标记 + pid 文件 + 后台等待者 | 首版用 `user_unlocked` 当闸门 |
| v0.1.12 | 闸门改为**先尝试、真的投递失败才延后**；单实例改 `mkdir` 原子锁；等 unlock 而非等「下笔」 | 不用代理判据：代理一旦与真实可达性不符，会在通路本来可用时拒绝重连，比不修更糟 |
| v0.1.13 | `request_oem_pen_action` 语义修正（三种失败一律 `return 1`）；缺陷 B 预算修正 | — |

**v0.1.12 的两处自我纠正**（都由测试逼出来，记下来避免重犯）：

1. 等待者最初拿「笔尖活性」当恢复触发器 —— **错**。休眠的笔尖节点本就零事件
   （那正是 D1a 判据本身），等待者会永远等不到触发。改为等解锁。
2. 单实例最初靠 `/proc/<pid>/cmdline` 里出现 `service.sh` 来排除 PID 回收
   —— **桩测试当场抓到失效**（换个脚本名去重就整体失灵）。改为 `mkdir` 原子锁，
   并在持有者死亡时接管。

新增/改动（`module/service.sh`）：

| # | 内容 | 位置 |
|---|---|---|
| 1 | `PEN_PENDING_RECONNECT` / `_PID` / `_LOCK` / `_TTL=1800` | 常量区 |
| 2 | `user_unlocked()`：仅用于把失败原因写进日志，**不参与决策** | `pen_tip_alive_within()` 之后 |
| 3 | `wait_writable_then_hard_reconnect()`：5 秒步进等解锁，成功即退出 | 同上 |
| 4 | `spawn_deferred_hard_reconnect()`：`mkdir` 锁 + 死亡接管 | 同上 |
| 5 | 硬重连入口：**首次 OEM DISCONNECT 兼作可达性探针**，失败才挂起 | `request_pen_reconnect_hard()` |
| 6 | 去重时间戳改为「投递成功后才写」，延后不消耗去重窗口 | 同上 |
| 7 | `request_oem_pen_action()` 返回真实投递结果（1=未投出去） | 函数尾部 |

**为什么探针就是那一步 DISCONNECT**：必须先确认可达再动手，否则会先断链、
再发现连不上，把笔留在一个比僵尸连接更糟的状态。可达性用真实投递结果回答，
不用任何代理判据。

### 14.6 桩测试（设备上运行，函数体从 `service.sh` 原样抽取）

`/data/local/tmp/pbtest_run.sh`：5 个用例，覆盖 TTL 放弃、恢复触发、单实例去重、延迟恢复、死亡接管。

| 用例 | 断言 | 结果 |
|---|---|---|
| T1 通道不通 + 未解锁 | 到 TTL 后放弃并清 pending/lock | ✅ `gave up after 10s` |
| T2 已解锁 + 通道可用 | 首轮触发、清 pending、等待者退出 | ✅ |
| T3 连续两次 spawn | 只有一个等待者（`same_waiter=yes`，只 1 条 gave up） | ✅（改锁后） |
| T4 先不通、2 秒后解锁 | 恢复后下一轮即触发 | ✅ |
| T5 持有者已死 | 后来者接管锁并恢复工作 | ✅ `taking over the lock` |

### 14.7 顺带修复：缺陷 B（开机 OEM 会话恢复的预算被烧光）

**现象**：开机到首次解锁之间 OEM 通路必然不可达，旧实现每次失败都
`oem_recovery_attempts++`，三次预算（间隔 30 秒）在 90 秒内烧光，
随后 `oem_recovery_exhausted=1`，只能「等到下一次真实蓝牙边沿」才恢复
（实测 12:09 那次启动：attempt 1/3、2/3 全是 `unlocked=0`）。

**修复**：预算只记「真投出去」的次数；投递不出去的另计 `oem_recovery_blocked`
（封顶 40 次 ≈ 20 分钟），真实蓝牙边沿时一并清零。

**实测**（v0.1.13，重启后仍未解锁）：

```
[12:12:12] OEM com.oplus.ipemanager.action.CONNECT_PENCIL request failed mac=DC:EB:4D:06:77:DD unlocked=0
[12:12:12] OEM haptic recovery not deliverable; channel unreachable, budget kept (1/40)
[12:12:42] OEM com.oplus.ipemanager.action.CONNECT_PENCIL request failed mac=DC:EB:4D:06:77:DD unlocked=0
[12:12:42] OEM haptic recovery not deliverable; channel unreachable, budget kept (2/40)
```

### 14.8 仍未验证（待用户操作）

| # | 项 | 期望 |
|---|---|---|
| 6 | **真实复现**：吸附 → 满电静置 → 锁屏 ≥2 分钟 → 解锁 → 拿笔直接写 | 日志出现 `pen tip silent for 2s after undock; assuming sleeping emitter` → 硬重连 → 书写成功且**无需重新吸附** |
| 6b | 反例：吸附 → 拿开 → 立刻写 | `pen tip alive after undock; link kept`，不重连 |
| 6c | `rc=0` 路径端到端（§13.6.4 遗留风险） | 真实书写时 `getevent -c 1` 返回 0 |
| 6d | 缺陷 B 交付侧 | 首次解锁后 ≤30 秒出现 `live HOGP missing OEM haptic session; recovery requested attempt=1/3` |

**当前设备状态**：模块 v0.1.13 已部署（zip MD5 `374988ef8baa6ff60396e36dff5197ee`，
设备侧核对一致），采集 `/data/local/tmp/pen_capture_v0113.log`。

### 14.9 保留待办（未实施）

- **D5 零代码脉冲实验**（治本，见 §13.6.6）；
- **D1b GATT 探测**（已降级）；
- **D1c 备选**：给 `com.aclaniakea.penhidctl` 的 `<service>` 加
  `android:directBootAware="true"`，让 HID 半条通路在锁屏态也可用。构建链存在
  （`penhidctl/tools/build_penhid.py`），但**未验证 HID 级重连能否唤醒休眠笔**，
  且它只解决 HID 半边（OEM GATT 会话仍要等解锁），故暂不做；
- **D1d 备选**：把硬重连下沉到 Hook（system_server 侧调用 HID profile），
  完全不依赖包解析。改动最大，留待前述方案都被证伪时再上。

---

## 15. 用户复查「还是不行」→ 定位 D1a 被静默吞掉（v0.1.14 → v0.1.15）

### 15.1 复现路径与现场

用户按实验 6 操作：12:18:27 吸附（`docked=1 charging=1`）→ 12:21:40 停充
（`charging=0`，笔满电后发射子系统掉电）→ **12:21:57 离座** → 书写失败 →
12:25:13 重新吸附。

模块日志在 `real Hall state docked=0` 之后**整段静默 80 秒以上**：既没有
`pen tip silent ... assuming sleeping emitter`，也没有 `pen tip alive ... link kept`，
更没有任何 `hard pen reconnect` 记录。也就是说 **D1a 的整条决策链根本没有产生
任何可观测行为** —— 与 11:31 那次失败（§13.4 记录）表现一致，但这次的原因不同。

### 15.2 根因：`$(pen_tip_event_node)` 子壳永久卡在 procfs 的 poll 上

进程树（12:22–12:24 采样）：

```
3039 (service.sh, PPID=1)
└─ 3040 ──┬─ 7222 / 7285 / 7287 / 7288 / 7290 / 7310   ← 六个监视循环，全部健康
          └─ …
7285 ── 31695 ── 31696                                  ← 离座分支派出的探针链
```

证据（全部由 root 直接读取，非推断）：

| 观察点 | 值 | 含义 |
|---|---|---|
| `31695` 的 syscall | `63`(read) `fd=0x3` | 停在 `$(...)` 捕获管道的读端 |
| `31695` 内核栈 | `pipe_read → vfs_read → ksys_read` | 等子进程输出，永不返回 |
| `31696` 的 syscall | `73`(ppoll) `nfds=0x1` `tmo_p=0x0` | **无限超时**的 poll |
| `31696` 的 pollfd | `{fd:0, events:POLLIN(1), revents:1}` | 轮询的是 **fd 0** |
| `31696` `/proc/<pid>/fd/0` | `/proc/bus/input/devices` | 正是 `while read` 的循环重定向 |
| `31696` `fdinfo/0` | **`pos: 1`，6 次采样不变** | 只读走 1 个字节就冻住 |
| `31696` `syscall` 连续 6 次 | 一字不变 | 不是循环，是真死锁 |

另外六个监视循环全部停在 `do_sys_poll`（nap fifo 的 `read -t`），说明**模块主体
健康，只有这一个子壳死锁** —— 这正是 `( ... ) &` 最危险的失败形态：**静默**。

### 15.3 对照实验：同一个构造在别处都能正常跑完

| 环境 | 结果 |
|---|---|
| root shell：连续两次 1 字节读同一 fd | 正常（`byte1=I`, `byte2=:`） |
| root shell：`while read` 逐行 | 正常，106 行 |
| root shell：`awk` 直接读 | 正常 |
| `nsenter -t 31696 -m`（同挂载命名空间） | 正常，106 行 |
| 仿模块 fd 环境（fd0=/dev/null、fd8=fifo、fd9=lock、stdout=日志）新进程 | **正常跑完** |
| 模块内实际进程 | **死锁** |

⇒ 这是**与运行时状态相关的偶发阻塞**，不能靠「复现不出来」排除。结论是设计层面的：
**D1a 的活性判据不允许建立在一个可能无限阻塞的读上**，而且它的失败必须是**可观测**的。

### 15.4 顺带发现：硬重连会把笔断掉却拒绝重建（**危险**）

12:33:03（v0.1.14 已生效）实测：

```
[12:33:03] pen tip liveness unknown (rc=2); hard reconnecting as fail-safe
[12:33:03] OEM DISCONNECT_PENCIL requested …
[12:33:03] hard pen reconnect: breaking possible zombie ACL first
[12:33:05] pen connect skipped: user disconnect choice is active     ← 没连回去！
[12:33:05] hard pen reconnect issued (zombie break waited 1s)
```

机制：Hook 的 `IpeManagerHooks` 在 `CoreService.onStartCommand` 里处理
`DISCONNECT_PENCIL` 时会把 **两个**闩锁一起置 1
（`lenovo_pen_disconnect_requested` 与 `lenovo_pen_user_disconnect_requested`），
而 `request_pen_reconnect_hard` 只清了前者 ⇒ `request_pen_connect` 被第二条闩锁
挡掉。**后果比僵尸连接更糟：链被我们亲手断掉又不重建。** 这次只因为用户 5 秒后
又把笔吸回座上（吸附边沿清闩锁）才没暴露。

### 15.5 实施

| 版本 | 改动 |
|---|---|
| v0.1.14 | ①`pen_tip_event_node()` 改扫 sysfs（`/sys/class/input/input*/name` + `inputN/eventM`），**彻底不碰 `/proc/bus/input/devices`**；②新增探针看门狗 `pen_tip_probe_reap()`，监视循环每秒记账，超 `PEN_TIP_PROBE_MAX=15` 秒即收尸并强制重连；③`rc=2`（无法判定）由「不动」改为「重连」（失败保险向）；④离座分支两条通路都登记 PID+起始秒 |
| v0.1.15 | ①`request_pen_reconnect_hard` **同时清两个闩锁**；②`pen_tip_alive_within` 增加「为什么判不了」的诊断日志；③节点扫不到时等 1 秒重试一次（离座瞬间笔的 HID 输入设备正在重建） |

`PEN_TIP_PROBE_MAX` 取 15 而非「窗口+5」：同一记账槽也用于「栈链路已断」的快速
通路，一次完整硬重连最长含 6 秒 HOGP 落下等待，上限贴太近会把**正在进行**的重连
误判成卡死；多出来的那一次由 10 秒去重兜住。

### 15.6 验证

设备上运行（函数体从 `service.sh` 原样抽取）：

| 用例 | 结果 |
|---|---|
| 节点解析 | `/dev/input/event5`（`input8 = NVTCapacitivePen`），**修正了初版少一层 `input/` 的缺陷**（曾输出 `/dev/event5`） |
| 静默探测 | `rc=1`，耗时恰 2s |
| 有事件探测 | `rc=0`（`getevent` 语义，历史已验证） |
| 看门狗·健康探针 | 不杀、槽位清空 |
| 看门狗·卡死探针 | `WARN pen tip probe wedged 21s … killing it and forcing hard reconnect`，强制重连被调用 |
| 看门狗·未到上限 | 不动作、槽位保留 |
| 强制缺节点 | 1 秒重试 + 诊断行 + `rc=2` |

**端到端**：v0.1.14 部署重启后 12:33:00 的离座边沿**第一次留下了完整日志链**
（`pen tip liveness unknown (rc=2); hard reconnecting as fail-safe` → 硬重连成功），
核心目标（不再静默）达成。

**当前设备状态**：模块 v0.1.15 已部署（zip MD5 `0ca48f512dbe20038a8e6131f597b9c2`，
设备侧核对一致），采集 `/data/local/tmp/pen_capture_v0111.log`，
基线 `docked=1 link=1 HOGP=2`。

### 15.7 待用户：实验 6 重做

1. 确认设备**已解锁**；
2. 笔吸附到充电位；
3. 锁屏，等 **≥2 分钟**（让发射子系统真的掉电）；
4. 解锁，取下笔，**直接书写**（不要重新吸附）。

期望日志：`pen tip silent for 2s after undock; assuming sleeping emitter`
（或 `tip probe: …` 诊断行）→ `hard pen reconnect: breaking possible zombie ACL first`
→ `hard pen reconnect issued` → 1–8 秒内可写。

反例（实验 6b）：正常态拿开笔立刻写，期望 `pen tip alive after undock; link kept`、
不重连。若出现 `WARN pen tip probe wedged`，说明还有未被 sysfs 改造覆盖的阻塞点，
把该行连同前后 20 行日志一并回传即可定位。

### 15.8 保留待办（新增）

- **D6 备选**：若 `getevent` 在本 ROM 上被证实会偶发返回非 0/124（v0.1.15 的诊断行
  会直接给出 `rc=N`），改为「读 evdev 设备节点计数」或「`dumpsys input` 的笔事件
  计数」等替代活性源；
- **D7 待查**：离座瞬间 Hook 会重建笔的 HID 输入设备（sysfs 里新冒出
  `Lenovo Tab Pen Pro 2 Mouse` / `Consumer Control`）。v0.1.15 的 1 秒重试是针对
  这个窗口的权宜手段，若实测仍常报 `no … evdev node in sysfs`，需要把判据改为
  「等设备稳定后再探」而不是「立刻探」。

---

## 16. 用户复测「一切都正常」→ 笔确已恢复；D1a 唯一残留是 13 秒重连窗口（v0.1.16）

### 16.1 用户反馈与仪器布设

用户报告实验 6 重做后 **「一切都正常」**。此时设备侧多路仪器（13:18:27 启动，
900 秒窗口）仍在采集，于是直接读数据自证，不依赖主观描述。

仪器覆盖面（`/data/local/tmp/pen_instrument.sh`）：

| 通道 | 节点 | 归属 | 本轮行数 |
| --- | --- | --- | --- |
| 笔尖 | `/dev/input/event5` | NVTCapacitivePen | **4674** |
| 触屏 | `/dev/input/event4` | NVTCapacitiveTouchScreen | 673 |
| 笔 HID ① | `/dev/input/event8` | Lenovo Tab Pen Pro 2 Mouse | 0 |
| 笔 HID ② | `/dev/input/event9` | Lenovo Tab Pen Pro 2 Consumer Control | 55 |
| 时间线 | —— | 每 5 秒记 docked/powered_down/link/hall3/BT/hidraw/各文件行数 | 99 轮 |

**结论先行：笔尖通道产生了 4674 行完整的主动笔协议事件**，与第 2 节以来
「BLE 活但屏幕零事件」的旧现场**完全相反**。笔在物理层、驱动层、输入层全部正常。

### 16.2 证据：笔尖事件是真实书写，不是噪声

`tip_events.log` 的内容是标准的 NVTCapacitivePen 报文，含坐标、距离、倾角、
按键与同步帧：

```
[    2960.809767] EV_ABS       ABS_X                000047fd
[    2960.809767] EV_ABS       ABS_Y                00005050
[    2960.809767] EV_ABS       ABS_DISTANCE         00000001
[    2960.809767] EV_KEY       BTN_DIGI             DOWN
[    2960.809767] EV_SYN       SYN_REPORT           00000000
[    2960.825518] EV_ABS       ABS_TILT_X           00000001
[    2989.979729] EV_ABS       ABS_DISTANCE         00000000
[    2989.979729] EV_KEY       BTN_DIGI             UP
```

`BTN_DIGI` 的 DOWN/UP 成对出现且**严格配对**，共 **5 段**：

| 段 | DOWN（内核时基） | UP | 时长 |
| --- | --- | --- | --- |
| 1 | 2960.809767 | 2961.268089 | 0.46 s |
| 2 | 2961.393238 | 2963.017166 | 1.62 s |
| 3 | 2963.618004 | 2965.393050 | 1.78 s |
| 4 | 2987.434894 | 2988.088798 | 0.65 s |
| 5 | 2988.534747 | 2989.979729 | 1.45 s |

段 3 与段 4 之间空了 **22.0 秒**（2965.39 → 2987.43）。这段空档里
`hid9_events.log`（笔 HID ②）恰好有 12 组 `MSC_SCAN 000c0619` / `000c0620`
交替的按键报文（2963.26…2976.43），时间落在空档内。**这与请用户做的三段测试
（① 笔尖画 ② 按笔键 ③ 手指划）逐项吻合**：段 1–3 是"画"，hid9 是"按笔键"，
段 4–5 是"再画"，`touch_events.log` 的 673 行是"手指划"。

### 16.3 事件时刻锚定（内核时基 → 墙钟）

采样点：`/proc/uptime = 3159.16` 对应墙钟 `13:27:28`（同批命令，±1 s）。
据此换算，再用时间线的 `tip=` 计数交叉验证：

| 事件 | 内核时基 | 换算墙钟 | 时间线交叉验证 |
| --- | --- | --- | --- |
| 触屏 `KEY_WAKEUP`（用户唤醒屏幕） | 2957.317067 | ≈ 13:24:06 | —— |
| 笔事件流起点 | 2960.809767 | ≈ 13:24:09 | 13:24:04 时 `tip=0`，13:24:09 时 `tip=2301` |
| 笔键（hid9） | 2973.265175–2976.431259 | ≈ 13:24:22–13:24:25 | —— |
| 笔事件流终点 | 2989.979729 | ≈ 13:24:39 | 13:24:44 起 `tip` 稳定在 4674 不再增长 |

**校验**：换算窗口（13:24:09 → 13:24:39，跨度 29.2 s）与时间线独立观测到的
计数跃升区间（13:24:04 → 13:24:39）一致，两条互不依赖的证据链互证。

### 16.4 13:12 那次「还是写不出」的真正原因：13 秒重连窗口

13:12 的 exp6 现场（§15.7 那次）模块**做对了每一件事**：

```
[13:12:25] real Hall state docked=0 ... connected=0     ← 用户取下笔
[13:12:27] tip probe: getevent rc=143 on /dev/input/event5
[13:12:27] pen tip liveness unknown (rc=2); hard reconnecting as fail-safe
[13:12:28] hard pen reconnect: breaking possible zombie ACL first
[13:12:28] HID disconnect service requested mac=DC:EB:4D:06:77:DD
[13:12:30] OEM ... CONNECT_PENCIL requested mac=DC:EB:4D:06:77:DD
[13:12:31] HID connect service requested mac=DC:EB:4D:06:77:DD
[13:12:31] hard pen reconnect issued (zombie break waited 1s)
[13:12:38] real BT state mirror connected=1 (was 1)      ← 链路回来
```

**从「取下笔」到「链路可用」间隔 13:12:25 → 13:12:38 = 13 秒。**
用户是在取下笔后**立刻**试写的，正好落在这 13 秒里 —— 于是体感是"还是写不出"。
而 13:12:38 之后笔一直是健康的：`pen-bridge.log` 从 13:12:38 起**再无任何输出**
直到本轮取证，说明 13:24 那次成功书写**全程没有任何模块介入**，
是纯物理书写。

> 这一条把「失败」重新定性了：不是模块没修好，而是**修复本身有一个 13 秒的
> 生效延迟**，而用户恰好在这个窗口内验收。

### 16.5 `rc=143` 的真相：退出码竞态（D6 关闭）

§15.5 的 D6 备选赌的是"`getevent` 在本 ROM 上会偶发返回非 0/124"。**本轮证实了，
但成因不是 getevent，而是 `timeout` 自身的竞态**：

| 采样 | 命令 | 笔尖是否在发射 | 返回值 |
| --- | --- | --- | --- |
| 13:12:27（模块记录） | `timeout 2 getevent -c 1 /dev/input/event5` | 否（刚离座） | **143** |
| 13:29 本轮实测 | 同一条命令、同一节点 | 否 | **124** |
| 13:29 本轮实测 | `timeout 1 sleep 5`（纯度探针） | —— | **124** |

工具链已核实：`/system/bin/timeout -> toybox`（0.8.12），`/system/bin/getevent ->
toolbox`。**二进制与节点都没变，同一种物理事实却给出两个退出码** —— 只能是
`timeout` 发 SIGTERM 与子进程自退之间的赛跑：子进程退出状态先被谁结算，
父进程就报谁（124 = timeout 自己超时；143 = 128+SIGTERM，子进程的信号退出被上报）。

两害：

1. 143 落进旧代码的 `*)` 分支，判成「无法判定」并多打一条误导性日志；
2. 更危险的是**决策会被退出码悄悄改写** —— 若某个工具版本的超时码变成 137/1，
   「笔有没有发射」这个物理事实就不再是唯一判据。

**D6 关闭，处置方式改为「读输出而非退出码」。**

### 16.6 实施（v0.1.16）

`pen_tip_alive_within()` 的判定从退出码改为**输出判据**：

```sh
pen_tip_out=$(timeout "$pen_tip_window" getevent -l -c 1 "$pen_tip_node" 2>&1)
if [ -z "$pen_tip_out" ]; then
    return 1                      # 窗口内零输出 = 零事件
fi
case "$pen_tip_out" in
    *EV_*) return 0 ;;            # 有事件行 = 笔在发射
    *)     return 2 ;;            # 有输出但没有事件行 = 工具异常
esac
```

要点：

1. 加 `-l`：让输出带事件名（`EV_ABS ABS_X ...`），只有该格式才有可比对的 `EV_`
   锚点；不带 `-l` 时 getevent 打的是十六进制单行，无从识别。
2. 保留 `2>&1`：把 getevent 自己的报错收进变量，好把「静默」与「工具报错」分开。
3. `return 1`（零事件）与 `return 2`（工具异常）的语义边界不变，**离座分支对
   两者都发起硬重连**的既有策略也不变 —— 本次只消除判据的不确定性，不改变
   外层决策。

版本：`module.prop` → `version=0.1.16` / `versionCode=116`。

### 16.7 验证

**语法**：`sh -n module/service.sh` 通过，旧变量 `pen_tip_rc` 无残留。

**节点映射**（设备实测，sysfs 扫名 → evdev 节点）：

| name | node |
| --- | --- |
| NVTCapacitivePen | `/dev/input/event5` |
| NVTCapacitiveTouchScreen | `/dev/input/event4` |
| Lenovo Tab Pen Pro 2 Mouse | `/dev/input/event8` |
| Lenovo Tab Pen Pro 2 Consumer Control | `/dev/input/event9` |

**判据三态**（`/data/local/tmp/verify_v16.sh`，把新逻辑原样搬出，2 秒窗口 × 8 轮）：

```
[13:29:28] round 1/8
  pen(ev5)   -> SILENT   (新判据 return 1)
  touch(ev4) -> SILENT   (新判据 return 1)
...（8 轮全同，13:30:00 结束）
```

静默分支（return 1）验证通过，且**无一轮误报 ODD**（若有工具噪声会立即显形）。
存活分支（return 0）需要物理落笔才能在窗口内触发，留待下一次真实使用由模块
日志自证（会打印 `pen tip alive after undock; link kept`）。

**守护链**：`service.sh`(3168/3171/7229/7292/7293/7295/7298/7319)、
`charge-guard.sh`(7311)、`pen-revive-guard.sh`(7318) 全部存活。

**全局状态**：`docked=0 powered_down=1 link_connected=1
disconnect_requested=0 user_disconnect_requested=0` —— 两个断开闩锁均为 0，
v0.1.15 的双闩锁修复状态健康。

**本地/设备一致性**：改动前 `md5(module/service.sh)` = 设备侧
`md5(/data/adb/modules/tb522fu_pen_bridge/service.sh)` =
`18d36e6ffe8cb452de907323a30ce73d`（104140 字节），确认改的是设备正在跑的那一份。

### 16.8 残留与下一步

**唯一残留：离座后约 13 秒的不可写窗口。** 它的性质已经变了——

- 旧描述：「取下笔写不出」（像是功能坏了）
- 新描述：「取下笔后要等约 13 秒」（重连的本征延迟）

这 13 秒被拆解为：探针窗口 2 秒 + zombie ACL 破链等待 1–2 秒 + HID 断开/重连
投递 + 笔自身的 BLE 唤醒（13:12:28 → 13:12:38 中约 7 秒是笔侧重建）。
**软件能压缩的只有前几秒，笔侧的唤醒时间压不掉。**

待办：

- **D8**：实测「离座瞬间笔尖必然零事件」是否为恒真。若恒真，则 D1a 的探针在
  离座路径上等价于无条件重连（**判据空转**），真正需要的判据是「笔是否已唤醒」
  而不是「笔是否在发射」。可用吸附态下的笔尖活动做对照来区分。
- **D9**：给 13 秒窗口加可观测性 —— 在硬重连完成点打印耗时
  （`hard pen reconnect converged in Ns`），把"能不能写"从体感变成读数。
- **D5（原治本方向，优先级上升）**：停充后微脉冲防休眠。若笔在离座时**仍是活的**，
  离座分支会走 `pen tip alive after undock; link kept`，根本不需要重连，13 秒
  窗口直接消失。这是唯一能把延迟归零的方向。

### 16.9 部署与端到端验证：`ALIVE` 分支首次实测触发

**部署方式（热重载，无需重启设备）**。`service.sh` 自身带有 respawn 循环
（`while [ ! -e "$CPS_DISABLED" ]; do sh "$0"; sleep 8; done &`），因此可以在不重启的
前提下换掉正在运行的代码：

1. `mv`（rename）**原子替换** `service.sh` + `module.prop`。**必须用 `mv` 而不是
   `cp` 覆盖** —— shell 是**流式读取**脚本的，`cp` 会截断一个正在被读取的文件，
   让运行中的实例读到错位内容甚至执行垃圾；rename 则保留旧 inode 给已打开的 fd，
   运行实例完全不受影响。
2. 停掉旧工作实例及其全部子进程，**保留 ppid=1 的 respawn 循环**。
   进程树（部署前）：`3168(respawn, ppid=1) → 3171(工作) → {7229,7292,7293,7295,7298,7319
   子壳, 7311 charge-guard, 7318 pen-revive-guard}`。
3. respawn 循环在 8 秒内自动以新文件重建 —— 实测 14:09:05 起来完整启动序列，
   新工作进程 7310，guard 7536/7545，全部就位。

备份留在 `/data/local/tmp/service.sh.v0115.bak` + `module.prop.v0115.bak`（可回滚）。
新版 md5 `7f03a4a1c5acf4931bd24b532475114c`；zip `tb522fu-pen-bridge-v0.1.16.zip`
md5 `d4aec284c5ccd97df28492406789a429`。

**`ALIVE` 分支（`return 0`）此前从未被触发过**（v0.1.10 以来全程走 fail-safe 重连）。
本轮首次触发：

```
[2026-09-21 14:11:39] real Hall state docked=0 battery=100 trusted=false charging=0 connected=0
[2026-09-21 14:11:39] pen tip alive after undock; link kept        <-- 新判据 return 0
```

仪器侧同时记录（5 秒粒度时间线 + `event5` 抓取）：

| 时刻 | docked | powered_down | link | tip 行数 | 说明 |
| --- | --- | --- | --- | --- | --- |
| 14:11:28 | 1 | 1 | 0 | 0 | 笔在座上 |
| 14:11:33 | 1 | 1 | 0 | 0 | —— |
| 14:11:39 | 1→0 | 1 | 0 | **268** | 探针窗口内捕获到笔尖事件 |
| 14:11:44 | 0 | 1 | **1** | **1194** | 已取下、链路已在，书写持续 |
| 14:11:49 | 1 | 1 | 1 | 1194 | 用户放回座上 |

`v16_tip.log` 内容为真实书写：`ABS_X/Y` + `ABS_TILT_X/Y` + `ABS_DISTANCE` +
`BTN_DIGI` DOWN(5809.507213) → UP(5811.431099)，约 1.9 秒一段。
换算（`uptime 5825.77 ↔ 14:11:54`）：首事件 ≈ **14:11:38**，与探针判定同秒。

**这条证据修正了 §16.8 里 D8 的假设。** 原先推断「离座瞬间笔尖必然零事件 ⇒ 判据
空转」被证伪：**判据能真实区分两种时序**——

- 用户**拿起笔就开始写**（笔尖仍在发射）⇒ `ALIVE` ⇒ **保持链路，零延迟可写**；
- 用户**取下后搁置**（笔尖已停）⇒ `SILENT` ⇒ 硬重连（~13 秒）。

也就是说，13 秒窗口**不是普遍现象**，只在「笔已停发后才落笔」的时序下出现；
而「取下即写」这一最常用路径**已经是零延迟**。

**另一条由此显形的物理事实**：`powered_down=1` 全程为 1（充电 TX 已关、笔进入低功耗），
但笔尖依然在发射。⇒ **`powered_down` 描述的是充电/BLE 侧状态，与笔尖电容发射是
两套独立系统**；书写不依赖 BLE，也就意味着不能在「笔写不出」时把 `powered_down`
当作病灶来推理。

---

## 17. 长时吸附后的「写不出」与 D1a 无关：笔深休眠，只有充电/TX 事件能唤醒

> 场景（用户原话）：**息屏 + 吸附充电很久 → 拿下笔 → 解除锁屏 → 写不出。**
> v0.1.16 已部署，D1a 判据与硬重连链路全部正常工作，但书写依旧失败。

### 17.1 模块侧全部正确（SILENT 路径端到端走通）

```
14:40:02  real Hall state docked=0 battery=100 trusted=true charging=0 connected=0 mac=DCEB4D0677DD
14:40:04  pen tip silent for 2s after undock; assuming sleeping emitter     ← 判据真阳性
14:40:04  OEM com.oplus.ipemanager.action.DISCONNECT_PENCIL requested
14:40:04  hard pen reconnect: breaking possible zombie ACL first
14:40:04  HID disconnect service requested mac=DC:EB:4D:06:77:DD
14:40:06  OEM com.oplus.ipemanager.action.CONNECT_PENCIL requested
14:40:07  hard pen reconnect issued (zombie break waited 1s)
14:40:23  real BT state mirror connected=1 (was 1)                          ← BLE 链路已恢复
```

**探针逐秒复核，两次判 `SILENT` 全部真阳性**（无一次误判）：

| 判 `SILENT` 时刻 | 探针窗口 | 笔尖事件实际出现的时刻 |
| --- | --- | --- |
| 14:17:54 | 14:17:52–54 | T=6187 → **14:17:55** |
| 14:20:12 | 14:20:10–12 | T=6326.62 → **14:20:14.6** |

且 14:17:55 / 14:18:01 / 14:20:14 三次 `SILENT → 硬重连` **之后用户都写成功了**
（2236 行、`BTN_DIGI` 严格配对）⇒ **硬重连本身无害**，D1a 不是病因。

### 17.2 决定性证据：框架全敞开，面板零上报

| 检查项 | 实测 | 判定 |
| --- | --- | --- |
| `ev5`（NVTCapacitivePen）上报 | 14:40:02 → 14:41:55 **连续 113 秒 0 行** | ❌ 面板无事件 |
| 仪器 fd | `21690: 4 -> /dev/input/event5`，映射 `NVTCapacitivePen = event5` **未变** | 排除陈旧 fd |
| `getevent` 读取层 | 直读 **evdev 原始层**，完全绕过 Android 输入框架 | 故障在框架**之下** |
| Hook 门控 | `14:40:01.848 pen input gate -> true (docked=0 disconnect=false hid=true)`，直到 `14:46:07.209` 才翻 false —— **失败全程为 true（已启用）**，中间 6 分钟无翻转 | 排除 Hook |
| Hook 门控作用域 | `pen input devices disabled/enabled **count=2**` = 2 个 BT HID 笔设备；**不含 ev5** | Hook 碰不到面板通道 |
| `input8/inhibited` | **0**（无 EVIOCGRAB） | 排除独占抓取 |
| `input8/phys` / `modalias` | `input/pen` / `b105...k140,141,14A,14B,14C,ra0,1,18,19,1A,1B` 标准笔数字化器能力集 | 排除设备能力异常 |
| 两个断开闩锁 | 均为 `0`；`link_connected=1` | 排除闩锁残留 |

⇒ **模块能动的（BLE）全做到了，框架侧也全敞开，`ev5` 依然零上报。**
病因在**面板主动笔数字化器 / 笔尖电场发射**这一层。

### 17.3 真正的自变量：**吸附时长**（不是 `powered_down`）

把每次书写尝试按「充停后的吸附时长」对齐，规律立刻显形：

| 取下时刻 | 吸附时长（充电已停） | 笔尖事件 | 结果 |
| --- | --- | --- | --- |
| 14:11:37 | 2.5 min | 1194 | ✅ |
| 14:17:55 | 6.2 min | 77 | ✅ |
| 14:20:14 | 1.9 min | 2236 | ✅ |
| **14:40:20** | **17 min** | **0** | ❌ |
| 14:46:13 | **6 秒**（刚吸附过） | 2548 | ✅ |

**成功 1.9 / 2.5 / 6.2 min；失败 17 min；刚吸附过则秒级恢复。**
阈值落在 **6.2 min 与 17 min 之间**。

**同时排除 `powered_down` 作病灶**：14:11:37 与 14:17:55 两次成功书写时
`lenovo_pen_powered_down` 都是 `1`（`pen-revive-guard.sh` 判据为「tx off ≥ 90s」，
属上报镜像）。⇒ **`powered_down=1` 与「写不出」不构成因果。**

### 17.4 物理机制（与 CPS 驱动分析对齐）

`docs/cps-charger-driver-analysis.md` §3 从 `.ko` 字符串读出：

```
ask pkt data[2]:%d, charging_cmd:%d, close tx      ← 笔通过带内通信命令停充 → 驱动关 TX
```

⇒ **是笔自己喊「已充满」让驱动关掉 TX 的**（驱动只负责转达）。此后
`pen-revive.log` 记录 TX **连续关闭 1129 秒（~19 分钟）无任何再充活动**：

```
14:31:10  pen powered down (tx off 602s >= 90s, online=0 cap=100); publishing honest link state
...
14:39:57  pen powered down (tx off 1129s >= 90s, online=0 cap=100); publishing honest link state
14:40:12  pen detached; session ended (tx=0 online=0 cap=0), powered_down flag held
```

**笔在这段「无线圈活动」的静置中进入深度休眠，离座后不再发射电场；重新吸附
（线圈重新激励）能立刻唤醒它。**

这解释了之前所有看似矛盾的现象：

- 「取下即写」永远是好的 —— 用户未经过解锁的十几秒，笔还没睡；
- 「息屏 + 长久吸附」必然坏 —— 静置时间远超阈值；
- 硬重连（BLE）永远救不回来 —— BLE 与笔尖发射是两套独立系统（§16.9 已证）。

### 17.5 由此定案的结论

1. **v0.1.16 已把 D1a 修到位**：判据真阳性、硬重连成功、ALIVE 分支零延迟。
   「锁屏 ≥2 分钟就写不出」那个旧缺陷**已消失**。
2. **残留缺陷是另一层**：笔在充停后 6–17 分钟深休眠，**模块用 BLE 无法唤醒**。
   这正是 `TODO.md` 里 **D5**（「停充后周期性极短 TX 脉冲维持笔的发射子系统清醒」）
   的靶心 —— 从"待验证方向"升为**唯一能把该场景归零的方向**。
3. **可行的 TX 杠杆（比厂商私有 `tx_status` 更安全）**：

   | 节点 | 权限 | 当前值 |
   | --- | --- | --- |
   | `/sys/class/power_supply/cps_wls_tx/online` | **`-rw-r--r--`** | 1 |
   | `/sys/class/power_supply/wls_tx/cmd` | **`-rw-rw-r--`** | 0 |
   | `/sys/bus/i2c/devices/11-0041/tx_status` | 私有（`cps_boost_mode:%d, cps_wls_en:%d`） | `cps_boost_mode:0, cps_wls_en:0` |

   注意 `tx_status` 是 v2 踩过坑的私有接口（当年强制写 `0` 出过问题），
   优先评估 `power_supply` 标准接口。

### 17.6 待验证（下一步）

- **D5-a（决定性问题）**：吸附静置期间周期性给一次极短 TX 脉冲，是否能让笔
  在离座时保持清醒？实验：吸附 + 息屏 + 每 60s 脉冲一次 × 20 分钟 → 取下即写。
  若成功 ⇒ 目标达成，且可知「脉冲」是否必须由 TX 实现。
- **D5-b**：`cps_wls_tx/online` 与 `wls_tx/cmd` 的写入语义与安全性（先只读观察，
  再极小步试写，全程有 re-dock 兜底）。
- **D11（低成本备选）**：笔键按压是否能把深休眠的笔唤醒？离座后按 3 次笔键再写。
  本轮 `14:45:40-42` 曾在「按笔键」步骤观察到 `nvt pen_diff` 由 0 抬到 762
  （而书写步骤的 14:45:33-39 全程为 0），提示**笔键可能是一次有效唤醒**。
- **D12**：`/proc/nvt_pen_diff` 的语义校验。该节点是快照型（读返回 `EAGAIN`，
  带重试才成），且失败态曾读到 1191/762、成功态读到 655 —— **存在缓存嫌疑，
  在未做「笔远离屏幕→贴近屏幕」的对照校验前，不得单独作为判据**。
  （本轮它的另一个可靠信号是**读取成败**：手指一碰屏幕、IC 醒来，读才成功。）
