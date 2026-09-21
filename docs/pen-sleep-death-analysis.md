# 手写笔「吸附后失联、必须重新吸附」根因分析

> 日期：2026-09-21 ｜ 设备：Lenovo TB522FU / OPD2409 / Android 16
> 笔：Lenovo Tab Pen Pro 2（`DC:EB:4D:06:77:DD`）
> 相关代码：`module/service.sh`、`module/pen-revive-guard.sh`、`module/charge-guard.sh`

## 一句话结论

**这不是蓝牙断连，是笔自己关机了，而系统看不见。**

笔满电后通过无线充的带内 ASK 包命令 CPS 驱动停止送电；驱动照办关掉载波；
笔因为失去载波而按固件行为自行关机；但蓝牙栈的连接状态不会随之清零，
于是系统仍然显示「已连接」——我们把这个叫**僵尸连接**。

僵尸连接又吃掉了吸附边沿的重连逻辑，所以用户必须物理再吸一次。
**软件无法唤醒已经关机的笔**：唤醒靠的是磁场边沿，不是载波。

## 症状

笔磁吸到 dock 后，正常能用一小会儿（约 30~60 秒），随后失联。此时：

- 系统设置页 / 设备空间仍显示「已连接」
- 笔在触摸屏上无响应
- 把笔拿起、停一下、再吸附 → 恢复正常（可持续一小会儿，然后重复）

## 完整因果链

以 2026-09-21 09:27 那次实测为例（`pen-bridge.log` + 内核 `dmesg`）：

| 时刻 | 事件 | 证据 |
|---|---|---|
| 09:27:15 | 吸附 → Hall 边沿 → CPS 驱动开 TX（Qi 送电） | `ipe charging_state 0 -> 1` |
| ~09:27:16 | 笔上电，完成带内握手 | `online=1`，1 秒内 |
| 09:27:30 | 笔报 SOC=100 | `ask pkt data[2]:2, charging_status:2` |
| 09:27:45 | **笔下令停充** → 驱动关 TX | `ask pkt data[2]:1, charging_cmd:1, close tx` |
| 09:27:45 | 驱动执行，载波消失 | `cps_wls_boost:0`，`tx=0 online=0` |
| 数秒后 | 笔判定「已离座」→ 自行关机 | `service.sh` 注释：*The Lenovo pen powers itself off when it is not magnetically docked* |
| — | 蓝牙栈 HOGP state 滞留为 2 → 僵尸连接 | 历史日志中 `connected=0` 从未出现 |

**关键一句**：关 TX 不是驱动的判断，也不是任何脚本的动作，**是笔自己下的命令**。
`charging_cmd:1, close tx` 这行内核日志是决定性证据。

### 1 秒精度时序（确认「关 TX → 失联」同秒发生）

用 `scripts/pen_trace.sh` 以 1 秒精度抓取的一次完整吸附：

```
09:11:15  hall=0 tx=0 online=0 cap=0      笔离开线圈
09:11:16  hall=1                          拿起边沿
09:11:20  hall=0                          放回 → 吸附边沿
09:11:21  tx=1 online=1                   Hall 边沿后 1 秒内笔就响应了
09:11:47  tx=0 online=0                   TX 关与 online 归零【同一秒】
```

这条序列解决了一个此前的矛盾：15 秒轮询曾显示 `09:04:27 suspect lost` 早于
`09:04:30 driver closed TX`，看起来像「失联先于关 TX」。1 秒精度证明那是采样
误差——因果方向确实是**关 TX → 笔失联**，且同秒发生，没有可用的先行信号。

### 为什么重连逻辑不生效

`service.sh::monitor_hall_capsule` 的吸附（attach）边沿原本这样判断：

```sh
if ! real_bt_connected; then
    request_pen_connect
fi
```

`real_bt_connected()` 读的是蓝牙栈的 HOGP profile state。笔掉电后该状态滞留为
2，于是判据**恒为真**，`request_pen_connect` 永不执行。

而 `service.sh:1667` 的注释恰好写明了设计意图：

> *Only connect after the CPS power-on, which can only wake a docked pen;
> the dock-attach edge reconnects it later.*

**「吸附边沿之后会重连」这一步被僵尸连接吃掉了。** 这就是「必须再吸一次」的直接机制。

## 为什么软件送电唤不醒笔——五组实验全部证伪

发现根因后，第一版方案（`pen-revive-guard.sh` v1）的设想是：检测到失联就写
`tx_status=1` 强制送电，用软件模拟一次「重新吸附」。**这个设想是错的。**

| 实验 | 手段 | 结果 |
|---|---|---|
| **B** | 写 `tx_status=1` | TX 开满 30 秒，`online` 恒 0 |
| **C** | 广播 `com.oplus.ipemanager.action.CONNECT_PENCIL` | CoreService **确实响应**（TX 开、上报 `charging=1`），`online` 恒 0 |
| **D** | TX 常开 120 秒 | 驱动约 30 秒后按「空载超时」自己关，`online` 恒 0 |
| **E** | 关 TX 后 50 秒重开 | 连开 23 秒，`online` 恒 0 |
| **F** | 10Hz 载波常驻（驱动一关就顶回） | 中断不到 1 秒，笔照样失联 |

实验 C 最有说服力：那不是「瞎写寄存器」，而是**打通了 OEM 原生重连通路**，
CoreService 老老实实执行了——笔照样零响应。

实验 F 说明**载波连一瞬都不能断**：笔对「离座」的判定是即时的。

### 机制解释：内核模块依赖

```
cps_wls_charger  53248  2  qti_battery_charger, dhall_och1909
dhall_och1909    24576  0
```

`dhall_och1909`（Hall 芯片驱动）**直接依赖 CPS 驱动**。Hall 边沿走的是**内核内
函数调用**，触发的是完整的「笔来了」流程（含带内握手），对笔而言等价于 Qi 标准的
**Digital Ping**。而写 `tx_status=1` 只置一个使能位，只产生 **Analog Ping**。

笔只认前者。所以这条路在原理上就是堵死的，不是调参数能绕过的。

### 几个被顺带推翻的假设

- **`online` 会翻转，且不在 TX 关闭后单独翻转**。曾判断它「永不翻转」（依据
  `docs/cps-charger-driver-analysis.md:93`），判错了。但它在 TX 关闭时与 TX
  同秒归零，因此**没有独立的区分度**——不能用它区分「正常充满静置」与「笔已掉电」。
- **驱动有空载超时关 TX**。用户态写 1 开的 TX，驱动约 30 秒后自己关。
- **`lenovo_pen_hardware_battery_last_at` 不是活性判据**。它由本模块的
  `monitor_battery_cache` 在 `connected=1` 时周期刷新——僵尸连接期间会被我们
  自己喂成常青，反映的是「缓存刷新时间」而非「笔的通信时间」。

## 澄清：不是本模块引入的

用户怀疑「之前加过笔充满电就让它睡眠的代码」。查证结果：

| 提交 | 时间 | 内容 |
|---|---|---|
| `059e018` | 2026-09-18 11:39 | charge-guard **v2**，含 `tx_set 0` 兜底断电（字面意义的「充满就断电」） |
| `d7e8609` | 2026-09-18 15:36 | charge-guard **v3**，`stop forcing tx_status off`，**只存在了 4 小时** |

当前设备与工作区跑的都是 v3 纯观察者。全项目搜索：写 `tx_status` 的四处
（`panic.sh` / `post-fs-data.sh` / `uninstall.sh`）**全部只写 1，没有一处写 0**。

**决定性考古证据**：`docs/p0-recon-20260918.md`（移植**第一天**的重建记录）
第 79–82 行：

```
| 11:30:18    | ipe_batt 99→100（充电未停） | 100 | 0 | 0 | 1 | Trickle | 100 |
| 11:34:19    | TX 侧断开（充满断电瞬间）   | 100 | 0 | 0→1 | 1→0 | Unknown | 0 |
| 11:34–11:57 | 静止（23 分钟无变化）     | 100 | 0 | 1 | 0 | Unknown | 0 |
```

「充满 → online 归零 → 长时间静止」**在项目第一天就被记录到了**，当时留了一个
未结的疑点（无法区分「充满自动断电」与「用户取笔」）。本次内核 ASK 包日志正好
把受控实验补上了：**是笔下令断电，然后自己关机。**

顺带一个容易记混的：v0.1.6 加过的 `kick_docked_pen_wake` 名字里有「休眠」，
代码却是**防**休眠（`echo 1 > tx_status` 送电）。这段已从工作区删除。

## 已实施的修复

既然唤醒无解，就修能修的：**让状态诚实，并恢复被僵尸连接吃掉的重连。**

### 1. `module/pen-revive-guard.sh` → v2（链路状态守护）

**删除全部 `tx_status` 写入**（脉冲方案已证伪，继续写只是空载发热）。改为：

- 判据：Hall 说笔在吸附位，但 CPS 侧 TX 已关闭 ≥ `POWERED_DOWN_AFTER_SEC`（默认 90s）
  → 判定笔已自行关机
- 动作：置位 `settings lenovo_pen_powered_down=1`
- 解除条件**只有一个**：笔重新上电（`tx=1` 或 `online=1`）
- **笔离开吸附位时不解除标志**——笔离座本就自行关机，保持标志是诚实的；若在此
  清标志，用户「拿起→放回」时 attach 边沿会看到标志=0，重连请求又会被僵尸连接吃掉

不用 `online` 做判据（与 TX 同秒归零，无区分度）；不用 HOGP 做判据（僵尸连接
恰恰就是它不翻转造成的）。

### 2. `module/service.sh`

**a) `real_bt_connected()` 增加一道判据**：

```sh
case "$(settings get global lenovo_pen_powered_down 2>/dev/null | tr -d '\r')" in
    1) return 1 ;;
esac
```

让 CPS 侧的确定性事实压过蓝牙栈的滞后状态。这一处改动同时修好三件事：
`monitor_real_bt_state` 会把镜像诚实写成 0（不再假装已连接）；
`monitor_hall_capsule` 的吸附边沿能正常触发重连；`request_pen_connect_bounded`
不再因僵尸判据误判「已连接」。

**b) 新增 `pen_cps_responsive()` 并用于吸附边沿**：

```sh
if ! real_bt_connected || ! pen_cps_responsive; then
    request_pen_connect
fi
```

吸附边沿是全新的物理会话。笔没在线圈上真实响应时一律重连，不管蓝牙栈怎么说。
`request_pen_connect` 自带 8 秒去重，Hall 抖动不会变成重连风暴。

### 3. 文件名保留 `pen-revive-guard.sh`

名字里的 "revive" 已名不副实，但改名要同步动 `build_root.py` 的 `INCLUDE`
白名单、`service.sh` 挂载点、以及设备上的残留文件——收益不抵风险。职责变更已在
脚本头部注明。

## 未验证方向（设备离线期间无法验证，留作下一步）

### Hall 驱动 rebind —— 唯一有希望「软件化重新吸附」的路

`dhall_och1909` 与 CPS 驱动通过内核内调用相连（devlink 可见
`consumer:platform:soc:hall_detect@0 -> i2c:11-0041`）。Hall 芯片节点是只读的，
但**平台设备的驱动绑定是可操作的**：

```sh
# 1) 找到 hall 驱动名
ls /sys/bus/platform/drivers/ | grep -i hall
# 2) 确认设备名
ls /sys/bus/platform/devices/ | grep -i hall
# 3) unbind + bind（会让 Hall 驱动重新 probe，可能触发完整的「笔来了」通知链）
echo soc:hall_detect@0 > /sys/bus/platform/drivers/<hall_drv>/unbind
sleep 1
echo soc:hall_detect@0 > /sys/bus/platform/drivers/<hall_drv>/bind
```

**风险**：rebind 失败会让 Hall 吸附检测整体失效（笔的吸附/弹窗/充电判定全废），
重启可恢复。**未做任何验证前不要剪进自动守护**——尤其不要在设备不可控时试。

### 别的思路（评估过，均不可行）

- **拦笔的 `charging_cmd`**：那是驱动与笔之间的带内 ASK 交互，用户态和 sysfs
  都够不着，需要改 CPS 驱动。
- **改 CPS 驱动参数**：`11-0041` 只有 `tx_status` 可写，其余全只读；
  `cps_wls_charger` 模块无可用 parameters。
- **让笔不关机**：笔的电源策略在固件里。

## 诊断命令备忘

设备侧高频探针（1 秒精度，`scripts/pen_trace.sh`）：

```sh
export ANDROID_SERIAL=192.168.31.213:5555
adb shell "su -c 'OUT=/data/adb/modules/tb522fu_pen_bridge/pen-trace.log DURATION=900 \
    nohup sh /data/local/tmp/pen_trace.sh >/dev/null 2>&1 &'"
adb shell "su -c 'cat /data/adb/modules/tb522fu_pen_bridge/pen-trace.log'"
```

看内核 ASK 包交互（关 TX 的直接证据）：

```sh
adb shell "su -c 'dmesg | grep -vE \"get_property|get cps_wls_boost\" \
    | grep -iE \"cps8601|cps_wls|close tx|charging_cmd|ask pkt|hall_status\" | tail -60'"
```

看僵尸连接：

```sh
adb shell "su -c 'dumpsys bluetooth_manager | grep -iE \"DC:EB:4D|LE:Y\"'"
adb shell "su -c 'settings get global lenovo_pen_powered_down; settings get global lenovo_pen_link_connected'"
```

守护日志：

```sh
adb shell "su -c 'cat /data/adb/modules/tb522fu_pen_bridge/pen-revive.log'"
```

## 开关

```sh
touch $MODDIR/pen-revive.dryrun     # 只记录检测结果，不改 settings
touch $MODDIR/disable-pen-revive    # 整个守护停摆
rm    $MODDIR/pen-revive.dryrun     # 恢复生效（无需重启，每轮循环重读）
```
