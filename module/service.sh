#!/system/bin/sh

MODDIR=${0%/*}

# ============================================================================
# 联想手写笔桥接 Root 服务 · service（开机完成后）阶段
# 实际修复与作用：
#   1) 开机按已绑定手写笔地址直接调用原厂 CoreService 的 CONNECT_PENCIL
#      BLE 连接（不以 Hall/CPS 为前提），并做有限次重试；
#   2) 设置页断开时调用 DISCONNECT_PENCIL，由配套 LSPosed Hook 执行
#      BluetoothGatt.disconnect()，使设备/HID 真实离开连接态；
#   3) 常驻监控真实 ACL/GATT/Hall/CPS 事件，驱动 ColorOS 设置页手写笔
#      卡片、磁吸胶囊、电量与存在状态（状态只跟随真实事件，不强行回放）；
#   4) 默认不监听屏幕状态、不做唤醒回放、不做任何充电/TX/GPIO/休眠控制：
#      官方 ZUXOS 取证（docs/pen_official_rom_verdict_20260921.md）证明
#      充满断电 → 笔深休眠 → 重新吸附唤醒是官方驱动+笔固件的完整行为链，
#      官方系统层没有任何"保持 TX / 唤醒笔"的逻辑，模块一律不介入。
#      v0.1.23 起连"深睡笔取下后自动唤醒"也一并删除（原第 6 项），理由与
#      当晚三层实测留档见本文件末尾的「深睡唤醒守护：已整段移除」一节。
# 仅适用于 SM8750P / sun 平台（TB522FU）；Hook 仍独立安装，模块内签名副本
# 仅用于 LSPosed 在 PackageManager 恢复 /data/app 前稳定读取。
# ============================================================================

LOGFILE="$MODDIR/pen-bridge.log"
UEVENT=/sys/devices/virtual/lenovo_penraw/lenovo_penraw/uevent
# TB522FU (sun/SM8750P): och1909 hall driver exposes four channels.
# Calibrated 2026-09-18: pen magnetic dock == hall3, attached reads 0,
# detached reads 1. hall1_1/hall1_2 idle 0, hall2 idle 1 (kbd cover side).
PEN1_HALL=/sys/devices/virtual/hall/och1909/hall3
# CPS8601 无线充节点。此前写死的是 pineapple/SM8650Q 的地址
# （…/qupv3_i2c_geni_se/98c000.i2c/i2c-2/2-0041），在 sun/SM8750P 上该路径
# 根本不存在，于是所有 CPS 读取静默空转、模块自己永远产不出新鲜样本
# （2026-09-18 实测：本机挂在 11-0041）。CPS8601 的 I2C 地址恒为 0x41，
# 用总线符号链接按地址解析即可同时兼容两块板子（v0.1.16 前的
# charge-guard.sh 首次验证过该路径形式；该守护已随 v0.1.17 裁撤）。
CPS_UEVENT=
CPS_TX_STATUS=
CPS_PS_ONLINE=

CPS_DISABLED="$MODDIR/disable"
HALL_STATE_FILE="$MODDIR/pen-hall.state"
CAPSULE_DEDUP_FILE="$MODDIR/pen-capsule.last"
CAPSULE_WORKER_FILE="$MODDIR/pen-capsule.worker"
CAPSULE_DEDUP_SECONDS=4
SERVICE_LOCK="$MODDIR/.service.lock"
OEM_CORE_SERVICE=com.oplus.ipemanager/.btadsorb.CoreService
OEM_CONNECT_ACTION=com.oplus.ipemanager.action.CONNECT_PENCIL
OEM_DISCONNECT_ACTION=com.oplus.ipemanager.action.DISCONNECT_PENCIL
PEN_CONNECT_DEDUP_FILE="$MODDIR/pen-connect.last"
PEN_BOOT_READY_FILE="$MODDIR/pen-boot-ready"
BRIDGE_PERMISSION_FILE="$MODDIR/pen-bridge-permissions.ready"
PEN_USER_DISCONNECT_KEY=lenovo_pen_user_disconnect_requested
BRIDGE_PKG=com.futureharmony.lenovopenbridge
BRIDGE_PKG_LEGACY=com.aclaniakea.lenovopenbridge

# --- inkdye（系统内置笔桥）默认禁用，由本模块接管；可在管理器里手动开启 ---
# 默认行为：开机把内置笔桥 `com.inkdye.lenovopentocoloros` 降级（disable-user），
# 使笔能力交由本模块（Root 服务 + Hook）接管。
# 如需回退到系统内置笔桥：在 KernelSU/Magisk 管理器「执行」按钮运行
# `action.sh enable`（写入 inkdye-enabled.state 并持续维持）。
# 切换入口：KSU 管理器「执行」或 `sh $MODDIR/action.sh enable|disable|toggle`。
INKDYE_PKG=com.inkdye.lenovopentocoloros
INKDYE_STATE="$MODDIR/inkdye-enabled.state"

# 迁移：旧语义的 inkdye-disabled.state（"用户曾选择禁用"）在新默认下已等价于默认值，清掉。
rm -f "$MODDIR/inkdye-disabled.state" 2>/dev/null
# ⚠️ 实际的 pm enable/disable 不在这里执行：service.sh 跑到这一段时
#    PackageManager 往往还没就绪，`pm disable-user` 会【静默失败】。
#    实测（2026-09-18）：这里打印了 "inkdye disabled by default"，但 dumpsys
#    仍是 enabled=0，`pm list packages -d` 也没有它 —— 即"日志说禁了，实际没禁"。
#    真正的落地点在下面 exec 重定向之后的 apply_inkdye_state（带重试 + 状态复核）。

# --- 日志节流参数（条数 + 字节双上限，裁剪逻辑见 bin/penlog.sh）-------------
LOG_MAX_BYTES=524288
LOG_MAX_LINES=400

# --- 不 fork 的等待（实现与原理见 lib/core.sh）-----------------------------
NAP_FIFO="$MODDIR/.nap.fifo"
NAP_READY=0

# Respawn guard: if the real service exits (crash, OOM, kill), bring it
# back after a short delay. KernelSU can invoke service.sh several times
# during boot (module refresh, boot-completed callbacks), so only one
# invocation may own the respawn loop and the monitors.
#
# The singleton lock is an exclusive flock on $SERVICE_LOCK held by the
# first invocation's file descriptor. The descriptor is inherited by the
# background respawn loop and by each service child, so exactly one loop
# (and one monitor set) stays alive for the whole session. Later KernelSU
# invocations fail the non-blocking lock and exit immediately. If the loop
# dies, the descriptor closes, the lock is released, and a later invocation
# takes over.
if [ -z "$PEN_SERVICE_RESPAWN" ]; then
    if [ -d "$SERVICE_LOCK" ]; then
        # Legacy mkdir lock from older builds; replace it with the flock file.
        rm -rf "$SERVICE_LOCK" 2>/dev/null
    fi
    exec 9>"$SERVICE_LOCK" 2>/dev/null || exit 0
    if ! /data/adb/ksu/bin/busybox flock -n 9 2>/dev/null; then
        # Either the lock is taken or busybox flock is unavailable; in both
        # cases exit without running a second monitor set.
        exit 0
    fi
    export PEN_SERVICE_RESPAWN=1
    (
        while [ ! -e "$CPS_DISABLED" ]; do
            sh "$0"
            sleep 8
        done
    ) &
    exit 0
fi

soc=$(getprop ro.soc.model)
platform=$(getprop ro.board.platform)
case "$soc/$platform" in
    *SM8750P*/*sun*) ;;
    *) echo "unsupported device: soc=$soc platform=$platform" >"$LOGFILE"; exit 0;;
esac

# ============================================================================
# 加载库：函数定义全部在 lib/ 下，本文件只负责配置与调用顺序。
#
# ⚠️ 两条必须遵守的约束：
#   1) source 必须早于任何 `monitor_* &`。子 shell 在 fork 那一刻复制父进程
#      的符号表，晚 source 会让监视循环静默 command not found —— 不报错、
#      不退出，只是循环体里什么都没发生。
#   2) 新增 lib 文件必须同步加进 module/tools/build_root.py 的 INCLUDE 白名单。
#      漏加不会报错：手工 cp 到设备时功能正常，但 zip 里永久缺该文件，
#      下次重装模块后静默失效。
# ============================================================================
for _lib in core pen_id pen_hw pen_link pen_ui boot monitors; do
    if [ ! -f "$MODDIR/lib/$_lib.sh" ]; then
        echo "FATAL missing module lib: $_lib.sh" >&2
        exit 1
    fi
    . "$MODDIR/lib/$_lib.sh"
done
unset _lib

# --- 日志装配 ---------------------------------------------------------------
# 条数 + 字节双上限的裁剪实现统一在 bin/penlog.sh；老版本安装（zip 里还没有
# 该文件）时退回「超字节上限才整 file 清空」的旧行为，至少不把日志写爆。
if [ -f "$MODDIR/bin/penlog.sh" ]; then
    . "$MODDIR/bin/penlog.sh"
fi
if ! type penlog_trim >/dev/null 2>&1; then
    penlog_trim() {
        _sz=$(wc -c <"$1" 2>/dev/null)
        case "$_sz" in ''|*[!0-9]*) return 0 ;; esac
        [ "$_sz" -lt "${3:-524288}" ] && return 0
        : >"$1" 2>/dev/null
    }
fi

# 本服务把整个输出重定向进日志，而模块目录在 /data/adb 下没有任何外部轮转。
# ⚠️ 下面的滚动必须在 exec >> 之前做：exec 用的是 O_APPEND，一旦 fd 已持
#    有该文件，`mv` 会把后续所有写入送进那个已经没人看得见的旧 inode
#    （详见 bin/penlog.sh 顶部）。运行中的裁剪由 trim_log_if_large 兜底。
if [ -f "$LOGFILE" ]; then
    # 上一轮日志先裁一遍再留档：长开机（>1 天）时 .1 也不会变成巨型文件。
    penlog_trim "$LOGFILE" "$LOG_MAX_LINES" "$LOG_MAX_BYTES"
    mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null
fi
# 上一次服务实例可能被 kill 在胶囊重试循环中间，留下 worker 标记文件；
# 不清掉会让本次开机所有磁吸边沿都不再尝试广播胶囊。
rm -f "$CAPSULE_WORKER_FILE" 2>/dev/null

exec >>"$LOGFILE" 2>&1
log "service start"

# --- 运行期初始化 -----------------------------------------------------------
if nap_init; then
    log "nap: 使用内建 read -t（不 fork）"
else
    log "nap: 自检未通过，回退外部 sleep"
fi

resolve_cps_nodes

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep_sec 2
done
normalize_disconnect_latch
# OEM GATT readiness belongs to the current IPeManager process/session.  A
# persisted value from the previous boot makes system_server broadcast haptic
# commands to a dynamic receiver that no longer exists, suppressing the direct
# GATT fallback.  The Hook writes the live owner PID after service discovery.
put_global lenovo_pen_oem_control_ready 0
put_global lenovo_pen_oem_control_pid 0
put_global lenovo_pen_oem_haptic_forward 1
log "stale OEM haptic transport session cleared"
reset_pen_state_mirror

# --- 启动期后台动作（各自带状态复核与重试，见 lib/boot.sh）------------------
clear_boot_fail_counter &
apply_inkdye_state &
apply_system_stability_fixes &
grant_bridge_bluetooth_permissions
unstop_bridge
# The LSPosed Hook is a separate package now. This Root service deliberately
# does not call pm install and never copies an APK into /data/app.
log "stable LSPosed Pen Hook payload expected"

# --- 启动监视器 -------------------------------------------------------------
# 五条常驻循环全部由 lib/monitors.sh 定义，这里以 & 后台启动。fork 那一刻
# 子 shell 复制父进程的符号表 —— 所有 lib 已在上面 source 完毕，符号表完整，
# 不会出现 "request_pen_connect: not found" 这类静默失效。
# 启动顺序保持在 boot_pen_connect 之前：监视器先接管 Hall/链路边沿，boot
# 重连窗口期间的真实状态变化才会被发布出去。
monitor_hall_capsule &
monitor_battery_cache &
monitor_charging_cache &
monitor_real_bt_state &

monitor_hid_latch &

# --- 启动尾部 ---------------------------------------------------------------
enable_oppo_pen_receivers
touch "$PEN_BOOT_READY_FILE"
emit_driver_uevents
boot_pen_connect

# Keep all monitor children alive after the boot retry window. Exiting the
# shell here can orphan/kill the CPS, Hall and HID reconciliation loops on
# some KernelSU/Magisk launchers, which leaves only the already-open BLE link.
while [ ! -e "$CPS_DISABLED" ]; do
    sleep_sec 30
done


# ============================================================================
# 深睡唤醒守护：已整段移除（v0.1.23，2026-09-21 深夜）
# ----------------------------------------------------------------------------
# 【原功能】深睡笔从吸附位取下后，本模块主动把笔的 BLE 链路做一次完整的
#   link-down -> link-up，试图把笔固件从深休眠里拉回来（实验定案见
#   docs/pen_wake_experiment_E0_E4_20260921.md §3/§3b 的 E4c-fix）。
#   已随本次减法删除的符号：
#     do_pen_wake_cycle / run_wake_guard_once / monitor_wake_guard /
#     verify_pen_awake / pen_undock_age / resolve_pen_input_node /
#     pen_input_silent / trigger_haptic_refresh / run_hidctl /
#     hidctl_result_file / grant_hidctl_bluetooth_permissions /
#     hide_hidctl_launcher / retry_hidctl_setup，
#     连同 priv-app PenHidCtl（com.aclaniakea.penhidctl）整体下线。
#
# 【为什么删】三条一起否决了这条路：
#   1. **原厂固件本身就没有这个能力。** 官方 ZUXOS 行为链是「充满断电 →
#      笔深休眠 → 重新吸附唤醒」（docs/pen_official_rom_verdict_20260921.md），
#      我们是在给一个不存在的能力补补丁。用户处置 = 重新吸附一次，这正是
#      原厂自身的语义。
#   2. **唯一有效手段会连累整机蓝牙。** 见下方第 3 层。
#   3. **代价与收益不成比例。** 为一次「忘记吸附」的兜底，长期维护一条跨
#      root 守护 / priv-app / Xposed Hook 三层的闭环，得不偿失。
#
# 【2026-09-21 当晚的三层实测 · 结论留档，避免以后重复踩】
#
#   第 1 层 · Profile / GATT（App 层唯一能碰得到的 API）—— 两种手段都无效
#     · run_hidctl disconnect（PenHidCtl，setConnectionPolicy(FORBIDDEN)）
#       23:04 实测：HOGP 2 -> 0，但 ACL handle 仍是 0x0002，**没有 teardown**。
#     · OEM DISCONNECT_PENCIL（含 Hook 的 invokeOriginalGattDisconnect）
#       23:16 实测：日志只有 `LE HID Closed` 与 `ACL Ignore connection from`，
#       ACL 依然没断。
#     ⇒ 根因：笔的 ACL 上挂着 5 个持有者 —— hid(49) / BatteryService(59) /
#       com.oplus.exsystemservice(61) / com.oplus.ipemanager(62) 等。前两个是
#       com.android.bluetooth 内部的系统 profile，App 层无从释放，ACL 因此永
#       不断开。笔固件感知不到「链路没了」，自然不醒。
#
#   第 2 层 · 单个 ACL 链路（需要 HCI_Disconnect）—— 本机硬件不可达
#     · 理论上这才是「只重连笔」的正解，代价最小。
#     · 本机做不到：这台是 QTI 用户态 H4 架构，HAL
#       （android.hardware.bluetooth@aidl-service-qti）独占 /dev/ttyHS0，
#       kernel 蓝牙子系统没有注册 hci 设备 —— 实测无 /dev/hci*、无
#       AF_BLUETOOTH HCI socket、无 hcitool/hciconfig/btmon，设备上也没有
#       python 可造 raw socket。强写 ttyHS0 会打乱 HAL 的 H4 帧同步，比整机
#       重启蓝牙更糟，故未实施。
#     ⇒ **本机在硬件层面就没有「单独断开一条 ACL」的通道。**
#
#   第 3 层 · 整片蓝牙适配器（svc bluetooth disable / enable）—— 有效但全局
#     · 23:20 实测：**成功**。ACL handle 0x0002 -> 0x0001（真断真建），笔尖
#       input 事件恢复，用户确认书写 / 震动 / 按键全部回来。
#     · 代价：连累平板上**所有**蓝牙设备一起闪断。当晚平板上只有笔在线，
#       代价恰好为零；一旦连着耳机或键盘就要一起重连，而 LE 设备（如 LE 键
#       盘）在适配器重启后并不保证自动回来。
#     ⇒ 它是全局 API，拿不到「只重连笔」的效果。
#
# 【保留下来的是什么】正常链路管理没有删：吸附边沿的 request_pen_connect、
#   用户在设置页点断开的 request_pen_disconnect / monitor_hid_latch、以及状
#   态镜像、磁吸胶囊、电量与充电监控，全部照旧工作。
# ============================================================================
