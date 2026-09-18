# CPS8601 无线充驱动分析（2026-09-18）

> 目的：判定「充满截止 / 补充充电」到底是谁的行为 —— 原厂 ZUX 内核驱动，还是
> 移植进来的 ColorOS 用户态。结论会影响我们是否需要用 `charge-guard.sh` 去干涉。
>
> 全部结论来自实机只读侦察（`/proc/*/fd`、sysfs、`lsmod`、设备树、以及把
> `cps_wls_charger.ko` 本体拉到本机做 strings 反查），没有修改设备状态。

## 1. 分区与来源：内核/驱动是原厂，用户态是 ColorOS

| 层 | 实测 | 判定 |
|---|---|---|
| 内核 | `Linux version 6.6.82TB522FU (kleaf@build-host) #1 SMP PREEMPT Sun Dec 01 08:10:00 UTC 2024` | **原厂 Lenovo TB522FU**（localversion 带 `TB522FU`，2024-12-01 构建） |
| 编译器 | 内核 banner 与 .ko 的 clang 串完全一致（`Android (11368308, +pgo,+bolt,+lto,+mlgo …) clang version 18.0.0 … r510928`） | 同一条构建链 |
| 驱动 | `/vendor_dlkm/lib/modules/cps_wls_charger.ko`，`lsmod` 依赖 `qti_battery_charger,dhall_och1909` | **原厂**（被原厂电池/霍尔驱动依赖） |
| vendor 分区 | `/vendor/bin/hw/vendor.lenovo.hardware.battery-service`（root）、`lenovo.hardware.*` 一批 | **原厂 Lenovo** |
| odm 分区 | `/odm/bin/hw/vendor.oplus.hardware.charger-V11-service`（root）、`com.oplus.battery` | ColorOS(OPPO) |
| 系统指纹 | `OPPO/OPD2409/OP615CL1:16/AP3A.240617.008/...`，构建于 2026-07-02 | **ColorOS 移植自 OnePlus Pad(OPD2409)** |

`vendor_dlkm` 共 **308** 个模块，抽样 6 个（含 Lenovo 专属与 Qualcomm BSP）vermagic
**完全一致**：`6.6.77-android15-8-maybe-dirty-4k SMP preempt mod_unload modversions aarch64`
→ 整个分区是一次同源构建。其中带 `lenovo/dhall/och` 的模块有
`dhall_och1909.ko`、`lenovo_keyboard.ko`、`lenovo_sys_temp.ko`、`lenovo_thermal_control.ko`
→ **是原厂模块集**。

移植方唯一塞进 `vendor_dlkm` 的是 4 个 **网络** 模块（与本议题无关）：
`oplus_network_data_module.ko`、`oplus_network_linkpower_module.ko`、
`oplus_network_tuning.ko`、`oplus_network_vnet.ko`。

> 注：`/proc/version` 的 `6.6.82TB522FU` 与模块 vermagic 的 `6.6.77-…` 不一致，
> 但 **308 个模块全都这样**（含 Qualcomm 自己的），是这台设备的构建版本串差异，
> 不能用来区分模块来源。

## 2. 用户态有没有插手 CPS？——没有

对 `/proc/*/fd` 全量扫描「谁持有 CPS/电量节点」：

- 唯一命中：`pid=1177 /system/bin/hw/android.system.suspend-service`
  （AOSP 通用服务，只为休眠判断读 `power_supply/*/uevent`）
- `vendor.lenovo.hardware.battery-service`（vendor，root）与
  `vendor.oplus.hardware.charger-V11-service`（odm，root）**都没有打开** `tx_status`
  或 `cps_wls_tx` 任何节点

→ **没有任何充电 HAL 常开持有 CPS 节点**。`tx_status` 属于「写入即关」型接口，
所以 fd 侦察看不到写者，但也**排除了 ColorOS 侧有一个守护进程在直接管它**。

## 3. 驱动内部控制逻辑（从 .ko 字符串直接读出）

导出/内部函数：

```
cps_wls_chrg_probe / remove          cps_wls_irq_handler[_process]
cps_wls_parse_dt                     cps_wls_get_ask_info / get_ask_pkt_event
cps_wls_send_fsk_ack / send_fsk_packet   (带内 FSK/ASK 通信)
cps_handle_rechg_work                (补充充电工作)
cps_handle_update_firmware_work / cps_wls_program_firmware (内置固件升级 + CRC)
cps_wls_create_device_node           cps_wls_register_psy / send_uevent
cps_wls_chrg_get_property / set_property / property_is_writeable
```

关键日志串（即它的状态判定点）：

```
[%s][%s][%s] hall_status : %d
[%s][%s][%s] ask pkt data[2]:%d, charging_cmd:%d, close tx      <-- 笔命令停充 → 驱动关 TX
[%s][%s][%s] error int flag, close tx                            <-- 异常 → 驱动关 TX
[%s][%s][%s] ask pkt data[2]:%d, charging_status:%d
[%s][%s][%s] ask pkt data[3]:%d, pen_type:%d
[%s][%s][%s] ask pkt data[4]:%d, charging_soc:%d
[%s][%s][%s] header 0x85 pen_type:%d, mac_addr:%02x:…, charing_soc:%d
[%s][%s][%s] wls send:%s, pos:%s, mac:%s, level:%s, attach:%s,
              charging_state:%s, charge_type:%s, pen_type:%s, qi_status:%d
[cps8601] get/set cps_wls_boost:%d, cps_wls_en:%d
cps_boost_mode:%d, cps_wls_en:%d          (tx_status 节点内容格式)
```

寄存器表 `CPS_TX_REG_*`（`BC_COMMAND/BC_HEADER`、`EPT_CODE/EPT_RSN`、`PPP_*`、
`ADC_VIN/ADC_IPA`）说明它实现了完整的 Qi/WPC TX 侧（EPT=End Power Transfer）。

**设备树里没有任何充电策略参数** —— `cps-wls-charger-l@41` 只有
`compatible / cps_wls_boost / cps_wls_en / cps_wls_int / interrupts / pinctrl`，
`/sys/module/cps_wls_charger/parameters/` 为空。

→ **策略全部硬编码在驱动内部**：驱动自己读霍尔判断吸附，通过带内通信从笔拿到
`charging_soc / charging_status / charging_cmd`，笔要求停就 `close tx`；另有
`cps_handle_rechg_work` 负责补充充电。**不需要任何用户态配合。**

## 4. 由此修正的两个 sysfs 语义（重要）

| 节点 | 语义 | 不能当什么用 |
|---|---|---|
| `tx_status` 里的 `cps_wls_en` | **是否在向笔送电**（1/0）。唯一可信的"正在充电"判据 | — |
| `cps_wls_tx/online` | **笔是否在线圈上**（吸附=1，离开=0；见 `charge_log_20260918.csv` 11:34 边沿） | ❌ 不是"正在充电"：`cps_wls_en:0`（已断电）时它仍是 1 |
| `cps_wls_tx/capacity` | 笔的 SOC（吸附时有值，离开归 0），来自驱动的带内解析 | 可作交叉校验，但不比带内值更权威 |
| `cps_wls_tx/charge_type` | 按吸附状态给的档位名（吸附=Trickle，离开=Unknown） | ❌ 不代表"正在充电" |
| `cps_wls_tx/status` | 驱动透出的**厂商自定义**枚举（实测 897197359 / 897202006），非 `POWER_SUPPLY_STATUS_*` | ❌ 别按标准枚举解读 |

## 5. 结论与待验证

**结论**：`/vendor_dlkm` 整个分区（含 CPS8601 驱动）是**原厂 Lenovo TB522FU 构建**，
ColorOS 只提供 system/product/odm 与 4 个网络模块，**没有介入 CPS**。驱动的
充满截止与补充充电逻辑完备且自持。

→ 因此**不存在"ColorOS 造成的过充"**。

**尚未直接观测到**：原厂驱动在「笔保持吸附、电量已达 100%」时，是否会**自己**把
`cps_wls_en` 关掉。历史日志里两次关 TX 都是 `charge-guard.sh` 在吸附后 t+3s/t+6s
抢先动手，把驱动自己会不会关这件事盖住了。`charge_log_20260918.csv` 也只录到
「拔笔」造成的 `online 1→0`，同样不能证明充满自关。

**待补的决定性实验**：`touch disable-charge-guard` 停掉我们的干涉，让笔重新吸附，
连续采样 `tx_status` 数分钟，看 `cps_wls_en` 是否自行 1→0。

- 若**会**自关 → 应移除 `charge-guard.sh` 的强制写 0（保留通知与
  `ipe_pencil_charging_state` 回写）。守护用 BLE 电量当判据、驱动用带内
  `charging_soc`，两者不一致时守护会挡住合法补电。
- 若**不会** → 守护必须保留，并需要进一步查明原厂链路缺了什么（例如
  `lenovo_penraw` / pen framework 侧的上报是否在移植后断开）。
