# 笔重连实验 1：根因定位（2026-09-21）

场景：吸附充电 → 锁屏静置 → 取下笔 → 解锁 → **笔无法使用，必须重新吸附**。

本文只记录**实测证据**，不写推测。所有时间戳来自设备日志。

---

## 0. 装置与起手状态

脚本推送到设备 `/data/local/tmp/exp1/`：

| 脚本 | 作用 |
|---|---|
| `exp1_pen_action.sh connect\|disconnect` | 直接向 vendor CoreService 发 `CONNECT_PENCIL` / `DISCONNECT_PENCIL` |
| `exp1_state.sh` | 采样 HOGP/HID profile + 镜像 settings + 内核节点 |
| `capture_pen_input.sh` | 后台记录 `getevent`(event5/8/9) + 门控 settings + input device Enabled 标志 |
| `launch_capture.sh` | 分离式启动采集 |

动作下发命令（等价于 `module/service.sh:request_oem_pen_action()`）：

```
am startservice --user 0 -n com.oplus.ipemanager/.btadsorb.CoreService \
    -a com.oplus.ipemanager.action.CONNECT_PENCIL \
    --es device_mac_info DCEB4D0677DD --ez codex_auto_connect true
```

起手状态（10:47:41）：`docked=1` / `HOGP=2` / `HID=0` / `powered_down=1` / `link_connected=0` / `tx=off` / `online=0` / `capacity=100`。

---

## 1. A1 — 僵尸态下单独 CONNECT

`CONNECT_PENCIL` 被服务接受（`rc=0`），vendor 侧**真的发起了连接**：

```
10:48:02.013 OplusSurfaceFlinger: mIpeBluetoothConnected=0, mIpePresent=1
10:48:02.017 LenovoPenStatic  : sync connect=2 battery=100
10:48:02.022 OplusSurfaceFlinger: mIpeBluetoothConnected=2, mIpePresent=1
10:48:02.817 LenovoPenBridge  : IPe original BleManager.b GATT connect requested mac=DC:EB:4D:06:77:DD
10:48:02.170 mydevices[core]  : connectState=CONNECTED, online=true, battery=SINGLE:100
```

**但 `HOGP connection state` 全程保持 2，从未经历断开/重连周期。**

→ 在座时 `HOGP=2` 是**真链路**（笔在线圈上、供电、ACL 活着），vendor 认为"已连接"，所以 CONNECT 近似 no-op。**此步无法判定僵尸场景**。

---

## 2. A2 — DISCONNECT_PENCIL 强制拆链 ✅

```
10:48:32 >> firing DISCONNECT_PENCIL       (rc=0)
10:48:35    HOGP connection state=2
10:48:39    HOGP connection state=0        ← 约 7s 完成
```

**结论：僵尸/在线连接都可以被 `DISCONNECT_PENCIL` 强制拆除。**

---

## 3. A3 — CONNECT_PENCIL 重建 ✅

```
10:48:52 >> firing CONNECT_PENCIL           (rc=0)
10:48:57    HOGP connection state=2         ← 约 5s 完成
            lenovo_pen_link_connected=1 / ipe_pencil_present=1
10:49:13    lenovo_pen_link_connected=0 / ipe_pencil_present=0   ← 但 HOGP 仍是 2！
```

**结论：拆除后可以可靠重建。** 但注意 10:49:13 起，**真实链路活着（HOGP=2）而镜像却翻成"未连接"** —— 见下一节。

### 3.1 服务自评失败的原因（日志）

```
10:49:00/03/06  HID connect service requested ... explicit pen HID retry attempt=1..3
10:49:06        explicit pen reconnect window ended without HOGP
10:49:11        real BT state mirror connected=0 (was 1)
```

服务在重试 3 次后放弃、5s 后把镜像翻 0。而**同一时刻 `dumpsys` 明确显示 `HOGP=2`** —— 说明它检查的判据不是 HOGP 本身。

---

## 4. 铁证：`real_bt_connected()` 被 `powered_down` 短路

`module/service.sh:1049`：

```sh
real_bt_connected() {
    # 笔已自行关机的标志（pen-revive-guard.sh 维护）
    case "$(settings get global lenovo_pen_powered_down 2>/dev/null | tr -d '\r')" in
        1) return 1 ;;            # ← 直接判定「未连接」，根本不读蓝牙栈
    esac
    mac=$(resolve_pen_mac); is_pen_mac "$mac" || return 1
    tail=${mac#*:*:*:*:}
    dumpsys bluetooth_manager 2>/dev/null | tr 'A-Z' 'a-z' \
        | grep -E "$tail .*hogp connection state=2" >/dev/null 2>&1
}
```

调用点（`service.sh:1120/1266/1287/1389/1434`）用于写 `lenovo_pen_link_connected`、吸附边沿重连、HID 重试判定。

**因果实验（B）**：

| 时刻 | 动作 | `powered_down` | `HOGP` | `link_connected` |
|---|---|---|---|---|
| 10:49:39 | 基线 | 1 | 2 | 1 |
| 10:49:44 | 清 0 | **0** | 2 | 1 |
| 10:49:50 | 守卫重写 | **1** | 2 | 1 |
| 10:50:16 | 下一轮 30s 轮询 | 1 | **2** | **0** |

结论：**`lenovo_pen_link_connected` 实质是 `powered_down` 的函数（约 30s 滞后），与真实蓝牙链路无关。**
`powered_down` 被清掉后 **6 秒内**就被 `pen-revive-guard` 重新置回 1（`tx=0` + `online=0` 持续 ≥90s）。

**另有 5 个写入者**互相打架，加剧振荡：
`service.sh:1135`（shell 监视器）、`IpeManagerHooks.java:108/130`（写 0）、
`PenStateStore.java:121`、`HookUtils.java:272`。

---

## 5. `penhidctl`：10 次拉起 / 10 次崩溃 = 100%

```
E iakea.penhidctl: Failed to open APK '/system/priv-app/aclpenhid/PenHidCtl.apk': I/O error
E AndroidRuntime : NullPointerException: Attempt to invoke virtual method
                   'java.lang.String[] android.content.res.Resources.getStringArray(int)'
                   on a null object reference
（statsd: crashbox_file_path="" / crashbox_dynamic_feature="no_apk_feature"）
```

`run_hidctl` 是**唯一能程序化调用 `BluetoothHidHost`** 的通道，全废。
`request_pen_connect()` 也**从不先 disconnect**，只发 `CONNECT_PENCIL`。

---

## 6. 用户失败窗口的完整时序（pen-bridge.log）

```
10:29:53  real Hall docked=1 charging=1                  ← 吸附
10:30:35  charging=0（满电，驱动自关 TX）
10:32:14  powered_down=1（tx off 91s ≥ 90s）
10:32:33  real BT state mirror connected=0 (was 1)       ← 镜像翻「未连接」
   ...（锁屏静置）
10:40:37  亮屏 → 蓝牙栈 "no device waiting for screen on, skip"
10:40:39  real Hall docked=0 ... connected=0             ← 拿下笔的瞬间，系统记为「未连接」
10:40:39  OEM CONNECT_PENCIL requested
10:40:39  BluetoothHidHost.connect() → "Device ... not disconnected. state=2"  ❌
10:40:40  HID connect service requested（penhidctl 崩溃）
   ...（无任何链路恢复记录）
10:40:54  重新吸附 → 恢复
```

**注意**：10:40:39 的 `connected=0` 与"重新吸附才恢复"，在时序上完全对应第 4 节的短路机制。

---

## 7. 输入闸门分析

`SystemStylusHooks.java:1476`：

```java
boolean zDisconnected = HookUtils.disconnectRequested(context);   // lenovo_pen_disconnect_requested==1
boolean zInputEnabled = iDocked == 0 && !zDisconnected;
```

- 吸附 → 禁用 `NVTCapacitivePen`（防误触，设计内）
- 取下 → 恢复
- **`lenovo_pen_disconnect_requested=1` 也会禁用笔输入**

实测在座时：`NVTCapacitivePen(id9) Enabled:false`，`Lenovo Tab Pen Pro 2 Mouse/Consumer Control(id14/15) Enabled:true`。

`applyPenHall()`（Hall 边沿）会调用 `updateRefreshFromState()` → `setPenInputEnabled()`，所以拿笔时门控**有被驱动**；
但**尚无"回调时 enable 是否真的生效"的直接证据** —— 由第 8 节采集判定。

断链路径（`:1776`）只做 `releaseLong()/stopWriting()/setRefreshActive(false)`，注释明确 **writing 不依赖蓝牙**。

---

## 8. 已定位 / 未决

| 环 | 结论 | 证据强度 |
|---|---|---|
| L1 笔固件满电后掉电 | 物理行为，未直接观测 | 推断 |
| L2 僵尸连接挡住重连 | **可被 DISCONNECT 破除**，非硬阻塞 | ✅ A2 |
| L3 `penhidctl` 全崩 | **确证，100% 崩溃** | ✅ 10/10 |
| L4 `powered_down` 短路镜像 | **确证，且是"系统认为笔不存在"的直接原因** | ✅ B + 代码 |
| 输入闸门 | Hall 边沿有驱动；**生效性未证** | ⏳ 采集中 |

**未决的唯一判别**：笔尖信号是否到达面板（`/dev/input/event5`）。
- 有事件 → 面板收到笔 → 问题在**下游**（门控/分发/镜像）
- 全静 → 笔没发信号 → **笔侧电源/唤醒**（L1）

---

## 9. 采集装置与用户操作

后台采集 `/data/local/tmp/exp1/capture_pen_input.sh`（600s，至约 11:03）：

- `getevent -lt /dev/input/event5 /dev/input/event8 /dev/input/event9`
  （`event5`=NVTCapacitivePen 数字化仪；`event8/9`=笔的 BT HID Mouse/Consumer Control）
- 每 2s 记录门控 settings；每 10s 记录 input device `Enabled` 标志
- 旁路记录 `logcat -s LenovoPenBridge`

用户操作：取下笔 → 解锁 → 用笔尖在屏幕书写 20~30s →（若写不出也继续划）→
再按原流程完整复现一遍（放回座 → 锁屏 → 等 1~2 分钟 → 拿下 → 解锁 → 写）。

---

## 10. 修复方向（按实验结论重排优先级）

| 优先级 | 修复 | 依据 |
|---|---|---|
| **P0** | 解耦 `powered_down` 与 `real_bt_connected()`：镜像回归纯蓝牙栈判定 | 第 4 节铁证 |
| **P0** | 重连前**先 disconnect 再 connect**（破除 L2） | A2/A3 证明杠杆有效 |
| **P1** | `penhidctl` 改 `pm install` 到 `/data` + `pm grant`，绕开 overlay namespace | 第 5 节 |
| **P1** | 离座边沿即清 `powered_down` | 第 6 节时序 |
| P2 | 唯一化 `lenovo_pen_link_connected` 写入者 | 第 4 节 5 写者 |
| 待定 | 离座发短 TX 脉冲主动唤醒笔 | 取决于第 8 节采集结论 |
