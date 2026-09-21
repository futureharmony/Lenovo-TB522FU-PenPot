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
#   4) PenHidCtl（priv-app HID 控制器）开机授予蓝牙运行时权限，只调后台
#      PenHidService，无启动器入口；
#   5) 默认不监听屏幕状态、不做唤醒回放、不做任何充电/TX/GPIO/休眠控制：
#      官方 ZUXOS 取证（docs/pen_official_rom_verdict_20260921.md）证明
#      充满断电 → 笔深休眠 → 重新吸附唤醒是官方驱动+笔固件的完整行为链，
#      官方系统层没有任何"保持 TX / 唤醒笔"的逻辑，模块一律不介入；
#   6) 例外：唤醒守护（pen-wake-guard.state 存在才生效；customize.sh 安装时
#      默认创建该文件 = 默认开启，`action.sh wake-guard-off` 持久关闭）——
#      深睡笔取下后自动断链重连唤醒（实验定案见
#      docs/pen_wake_experiment_E0_E4_20260921.md §3/§3b），重连后必须
#      广播 haptic REFRESH 让 Hook 重放 inkdye 握手，否则书写无震动。
# 仅适用于 SM8650Q / pineapple 平台；Hook 仍独立安装，模块内签名副本
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
resolve_cps_nodes() {
    for dir in /sys/bus/i2c/devices/*-0041; do
        [ -d "$dir" ] || continue
        [ -r "$dir/tx_status" ] || continue
        CPS_TX_STATUS="$dir/tx_status"
        [ -r "$dir/uevent" ] && CPS_UEVENT="$dir/uevent"
        break
    done
    [ -r /sys/class/power_supply/cps_wls_tx/online ] && \
        CPS_PS_ONLINE=/sys/class/power_supply/cps_wls_tx/online
}
resolve_cps_nodes
CPS_DISABLED="$MODDIR/disable"
HALL_STATE_FILE="$MODDIR/pen-hall.state"
CAPSULE_DEDUP_FILE="$MODDIR/pen-capsule.last"
CAPSULE_WORKER_FILE="$MODDIR/pen-capsule.worker"
CAPSULE_DEDUP_SECONDS=4
SERVICE_LOCK="$MODDIR/.service.lock"
HIDCTL_SERVICE=com.aclaniakea.penhidctl/.PenHidService
OEM_CORE_SERVICE=com.oplus.ipemanager/.btadsorb.CoreService
OEM_CONNECT_ACTION=com.oplus.ipemanager.action.CONNECT_PENCIL
OEM_DISCONNECT_ACTION=com.oplus.ipemanager.action.DISCONNECT_PENCIL
PEN_CONNECT_DEDUP_FILE="$MODDIR/pen-connect.last"
PEN_BOOT_READY_FILE="$MODDIR/pen-boot-ready"
HIDCTL_PERMISSION_FILE="$MODDIR/pen-hid-permissions.ready"
BRIDGE_PERMISSION_FILE="$MODDIR/pen-bridge-permissions.ready"
HIDCTL_LAUNCHER_FILE="$MODDIR/pen-hid-launcher.hidden"
PEN_USER_DISCONNECT_KEY=lenovo_pen_user_disconnect_requested
BRIDGE_PKG=com.futureharmony.lenovopenbridge
BRIDGE_PKG_LEGACY=com.aclaniakea.lenovopenbridge

# --- 唤醒守护（可选，默认关闭）：状态文件与参数 -----------------------------
# 开关 = pen-wake-guard.state（action.sh wake-guard on/off 维护）；
# 武装 = pen-wake-arm.state（离座边沿写入**离座时刻的 epoch**，一轮唤醒尝试后消费掉；
#        内容既用于日志，也是"离座 -> 首个笔画"延迟的唯一时间基准）；
# 冷却 = pen-wake.last（上次唤醒时间戳）；手动触发 = pen-wake-now。
# ⚠️ 这些定义必须在 monitor_wake_guard fork 之前完成（文件顶部），
#    否则子进程拿到空变量、[ -e "" ] 永假 —— 2026-09-21 实测踩过。
WAKE_GUARD_ENABLED_FILE="$MODDIR/pen-wake-guard.state"
WAKE_GUARD_ARM_FILE="$MODDIR/pen-wake-arm.state"
WAKE_GUARD_LAST_FILE="$MODDIR/pen-wake.last"
WAKE_GUARD_NOW_FILE="$MODDIR/pen-wake-now"
WAKE_QUIET_SECONDS=6
WAKE_COOLDOWN_SECONDS=120
# 断链后保持离线的秒数。E4 实验测得"断链 -> 笔醒来"落在 2–5s，所以这个值
# 必须 ≥ 区间上沿，**不能取下沿**。
# 2026-09-21 21:36 生产样本（用户报"有震动无笔画"那次）：断链 21:36:44.58 ->
# 首个笔画 21:36:48.5 = **3.9s**。当时配置是 3s，实际重连落在 21:36:48.87，
# 只比笔醒来早 0.37s —— 余量小到一次偏移就会退回"连上了但触控死"。
WAKE_OFFLINE_HOLD_SECONDS=5
# 断链后等待链路回来的窗口（秒）。解锁态 2–5s 内回链，8s 原本够用；
# 锁屏态实测 ~23s（屏幕灭着时 BLE 重连慢得多），所以放宽到 25s。
WAKE_LINK_BACK_SECONDS=25
# 唤醒循环之后的验证窗口（秒）。**只记日志、不改行为**：用来回答"这一轮到底
# 成没成"。此前循环跑完就返回，无法区分"修好了"和"白跑一趟"，而"判断不出却
# 当成功"这类静默失败在本项目已踩过四次（见
# docs/pen_wake_guard_silent_noop_20260921.md 的教训段）。
WAKE_VERIFY_SECONDS=12
# 笔尖数字笔节点的 sysfs 精确名。TB522FU 上是 NVTCapacitivePen：触控驱动注册的
# 虚拟节点（Sysfs=/devices/virtual/input/input8），常驻，**不**随笔的 uhid HID
# 链路一起注销/重建，所以离座瞬间也扫得到。改用名不匹配只影响兜底路径。
PEN_TIP_DEVICE_NAME=NVTCapacitivePen
PEN_INPUT_NODE=

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

# --- 启动失败自保：清除 post-fs-data 的失败计数 -----------------------------
# post-fs-data.sh 每次开机先 +1 记账，连续失败超阈值就熔断（见该文件）。
# 只有系统【真正】到达 sys.boot_completed=1 才清零——否则一个能跑到
# late_start 但随后崩掉的坏镜像会被误判为“启动成功”，计数被白白清空。
# 后台等待，不阻塞本服务启动。
(
    _n=0
    while [ "$_n" -lt 180 ]; do
        [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
        sleep 2
        _n=$((_n + 1))
    done
    if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
        rm -f /data/adb/tb522fu_pen_bridge.bootfail 2>/dev/null
    fi
) &

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

# 本服务把整个输出重定向进日志，模块目录在 /data/adb 下又没有任何外部轮转，
# 此前是无限追加。开机先滚一次，保留上一轮现场；运行中由下面的
# trim_log_if_large 兜底 —— 实际裁剪逻辑统一在 bin/penlog.sh（条数 + 字节双上限）。
# ⚠️ exec >> 用的是 O_APPEND，所以裁剪必须【同 inode 就地回写】，绝不能 mv
#    （详见 bin/penlog.sh 顶部说明：mv 会把日志写进已 unlink 的旧 inode）。
LOG_MAX_BYTES=524288
LOG_MAX_LINES=400
if [ -f "$MODDIR/bin/penlog.sh" ]; then
    . "$MODDIR/bin/penlog.sh"
fi
# 降级兜底：老版本安装（zip 里还没有 bin/penlog.sh）时至少不要把日志写爆，
# 退回"超字节上限才整file清空"的旧行为。
if ! type penlog_trim >/dev/null 2>&1; then
    penlog_trim() {
        _sz=$(wc -c <"$1" 2>/dev/null)
        case "$_sz" in ''|*[!0-9]*) return 0 ;; esac
        [ "$_sz" -lt "${3:-524288}" ] && return 0
        : >"$1" 2>/dev/null
    }
fi
if [ -f "$LOGFILE" ]; then
    # 上一轮日志先裁一遍再留档：长开机（>1 天）时 .1 也不会变成巨型文件。
    penlog_trim "$LOGFILE" "$LOG_MAX_LINES" "$LOG_MAX_BYTES"
    mv -f "$LOGFILE" "$LOGFILE.1" 2>/dev/null
fi
# 上一次服务实例可能被 kill 在胶囊重试循环中间，留下 worker 标记文件；
# 不清掉会让本次开机所有磁吸边沿都不再尝试广播胶囊。
rm -f "$CAPSULE_WORKER_FILE" 2>/dev/null

trim_log_if_large() {
    # 条数（400 行）+ 字节（512 KB）双上限，超限保留最近行而不是整file清空：
    # 控制台只显示尾部片段，清空等于把现场丢干净，下次报障无据可查。
    penlog_trim "$LOGFILE" "$LOG_MAX_LINES" "$LOG_MAX_BYTES"
}

exec >>"$LOGFILE" 2>&1
echo "[$(date '+%F %T')] service start"

# --- inkdye 落地：等 PackageManager 就绪后重试，直到状态复核通过 --------------
# 早期 service.sh 阶段 pm enable/disable 会静默失败，因此这里后台重试（不阻塞
# 主流程），并用 `pm list packages -d` 复核实际状态，避免"日志说禁了其实没禁"。
inkdye_is_disabled() {
    pm list packages -d --user 0 2>/dev/null | grep -q "^package:${INKDYE_PKG}$"
}
apply_inkdye_state() {
    i=0
    if [ -f "$INKDYE_STATE" ]; then
        # 用户显式选择启用 → 维持系统内置笔桥
        while [ "$i" -lt 40 ]; do
            pm enable --user 0 "$INKDYE_PKG" >/dev/null 2>&1
            if ! inkdye_is_disabled; then
                echo "[$(date '+%F %T')] inkdye kept ENABLED (user choice via action.sh)"
                return 0
            fi
            i=$((i + 1)); sleep 3
        done
        echo "[$(date '+%F %T')] WARN inkdye enable not confirmed after $i tries"
    else
        # 默认：禁用系统内置笔桥
        while [ "$i" -lt 40 ]; do
            pm disable-user --user 0 "$INKDYE_PKG" >/dev/null 2>&1
            if inkdye_is_disabled; then
                echo "[$(date '+%F %T')] inkdye disabled by default (enable via action.sh)"
                return 0
            fi
            i=$((i + 1)); sleep 3
        done
        echo "[$(date '+%F %T')] WARN inkdye disable not confirmed after $i tries"
    fi
}
apply_inkdye_state &

# --- 防整机重启：禁用/停止移植ROM缺失专有驱动的高危服务 ---
apply_system_stability_fixes() {
    stop vendor.urcc-hal-aidl 2>/dev/null
    setprop ctl.stop vendor.urcc-hal-aidl 2>/dev/null
    i=0
    while [ "$i" -lt 40 ]; do
        pm disable com.oplus.gesture >/dev/null 2>&1
        if pm list packages -d 2>/dev/null | grep -q "^package:com.oplus.gesture$"; then
            echo "[$(date '+%F %T')] com.oplus.gesture disabled for boot stability"
            break
        fi
        i=$((i + 1)); sleep 3
    done
}
apply_system_stability_fixes &

# 不 fork 的等待。
#
# 每次调用外部 sleep 都是一次 fork+exec，而本服务有 6 处 1 秒轮询、3 处 2 秒
# 轮询，外加若干子 shell。实测代价：笔桥接运行时全系统 26 forks/s，停掉它只剩
# 8 forks/s——**它一个模块占了全系统进程创建的 69%**，而且笔没在使用时照跑不误
# （10 个常驻 shell、合计 RSS 7.7 MB、持续约 1% CPU）。
#
# read 是 shell 内建命令。把一个只读不写的 fifo 以读写方式常开在 fd 8 上，
# `read -t N -u 8` 就会阻塞到超时再返回，全程不创建进程。fifo 必须用 <> 打开，
# 否则没有写端时 read 会立刻拿到 EOF 而不是等待。
#
# fd 用 8：9 已被上面的 SERVICE_LOCK 占用。
# 自检失败（shell 不支持 read -t、fifo 建不出来、或立刻返回而不是等待）时
# 原样回退到外部 sleep，行为与改动前完全一致。
NAP_FIFO="$MODDIR/.nap.fifo"
NAP_READY=0

nap_init() {
    [ -p "$NAP_FIFO" ] || {
        rm -f "$NAP_FIFO" 2>/dev/null
        mknod "$NAP_FIFO" p 2>/dev/null || mkfifo "$NAP_FIFO" 2>/dev/null
    }
    [ -p "$NAP_FIFO" ] || return 1
    exec 8<>"$NAP_FIFO" 2>/dev/null || return 1
    # 自检：请求等 0.3 秒，必须真的耗掉 >=200ms。若 shell 不支持 -t 或直接
    # 拿到 EOF 就会立刻返回，那种情况下用它会把轮询变成忙等，必须回退。
    _nap_t0=$(date +%s%N 2>/dev/null) || return 1
    read -t 0.3 -u 8 _nap_discard 2>/dev/null
    _nap_t1=$(date +%s%N 2>/dev/null) || return 1
    [ $(( (_nap_t1 - _nap_t0) / 1000000 )) -ge 200 ] || return 1
    NAP_READY=1
    return 0
}

sleep_sec() {
    if [ "$NAP_READY" = 1 ]; then
        read -t "$1" -u 8 _nap_discard 2>/dev/null
        return 0
    fi
    # Use the root runtime's BusyBox first.  On this ROM /system/bin/toybox can
    # appear executable while its mount namespace is still settling; invoking it
    # then fails and a polling loop can spin at 100% CPU.
    for sleep_backend in \
            /data/adb/ksu/bin/busybox \
            /data/adb/magisk/busybox \
            /system/bin/toybox; do
        [ -x "$sleep_backend" ] || continue
        if "$sleep_backend" sleep "$1" >/dev/null 2>&1; then
            return 0
        fi
    done
    echo "[$(date '+%F %T')] no working sleep backend; stopping service to avoid a busy loop"
    exit 0
}

if nap_init; then
    echo "[$(date '+%F %T')] nap: 使用内建 read -t（不 fork）"
else
    echo "[$(date '+%F %T')] nap: 自检未通过，回退外部 sleep"
fi


# v1.0.61 wrote a connected snapshot immediately after requesting GATT.  That
# snapshot could survive a module update and make the next boot look connected
# even when Bluetooth had no live link.  Start this revision from a neutral
# mirror; the OEM ACL/GATT and real Hall/CPS events repopulate it afterward.
# Keep the explicit user disconnect choice and the last valid battery sample
# intact. The operational latch is normalized separately at boot: an ACL
# disconnect emitted during a normal reboot must not block the next boot.
# Bluetooth recovery below is intentionally independent of the physical Hall
# pair; Hall is only used by the CPS and magnetic-capsule paths.
reset_pen_state_mirror() {
    for key in \
            lenovo_pen_link_connected \
            ipe_pencil_connect_state \
            ipe_pencil_connection_state \
            PENCIL_CONNECT_STATE \
            pencil_connect_state; do
        settings put global "$key" 0 >/dev/null 2>&1
    done
    settings put global lenovo_pen_refresh_active 0 >/dev/null 2>&1
    settings put global settings_enable_oppo_pencil 0 >/dev/null 2>&1
    settings put global ipe_pencil_present 0 >/dev/null 2>&1
    settings put global ipe_pencil_charging_state 0 >/dev/null 2>&1
    settings put global lenovo_pen_physical_docked 0 >/dev/null 2>&1
    settings put global lenovo_pen_hardware_battery_valid 0 >/dev/null 2>&1
    settings put global lenovo_pen_oem_charge_valid 0 >/dev/null 2>&1
    echo "[$(date '+%F %T')] stale v1.0.61 connection mirror cleared"
}

normalize_disconnect_latch() {
    user_requested=$(settings get global "$PEN_USER_DISCONNECT_KEY" 2>/dev/null | tr -d '\r')
    case "$user_requested" in
        1|0) ;;
        *) user_requested=0 ;;
    esac
    # lenovo_pen_disconnect_requested is a runtime guard. Do not carry a
    # natural ACL shutdown from the previous boot into the next boot; only an
    # explicit settings-page Disconnect is persistent across reboot.
    settings put global "$PEN_USER_DISCONNECT_KEY" "$user_requested" >/dev/null 2>&1
    settings put global lenovo_pen_disconnect_requested "$user_requested" >/dev/null 2>&1
    echo "[$(date '+%F %T')] disconnect latch normalized user=$user_requested"
}

until [ "$(getprop sys.boot_completed)" = 1 ]; do
    sleep_sec 2
done
normalize_disconnect_latch
# OEM GATT readiness belongs to the current IPeManager process/session.  A
# persisted value from the previous boot makes system_server broadcast haptic
# commands to a dynamic receiver that no longer exists, suppressing the direct
# GATT fallback.  The Hook writes the live owner PID after service discovery.
settings put global lenovo_pen_oem_control_ready 0 >/dev/null 2>&1
settings put global lenovo_pen_oem_control_pid 0 >/dev/null 2>&1
settings put global lenovo_pen_oem_haptic_forward 1 >/dev/null 2>&1
echo "[$(date '+%F %T')] stale OEM haptic transport session cleared"
reset_pen_state_mirror

# Priv-app allowlists do not grant Android 12+ Bluetooth runtime permissions.
# Root only calls PenHidCtl's explicit service component. The APK has no
# launcher intent, so authorize that service before issuing any HID command.
grant_hidctl_bluetooth_permissions() {
    [ -f "$HIDCTL_PERMISSION_FILE" ] && return 0
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID permission grant skipped: helper APK unavailable"
        return 0
    fi
    failed=0
    for permission in \
            android.permission.BLUETOOTH_CONNECT \
            android.permission.BLUETOOTH_SCAN; do
        if ! pm grant --user 0 com.aclaniakea.penhidctl "$permission" >/dev/null 2>&1; then
            failed=1
            echo "[$(date '+%F %T')] HID permission grant failed permission=$permission"
        fi
    done
    if [ "$failed" = 0 ]; then
        : >"$HIDCTL_PERMISSION_FILE"
        echo "[$(date '+%F %T')] HID Bluetooth runtime permissions granted"
    fi
}

# The haptic transport deliberately lives in the standalone bridge process so
# a vendor Bluetooth failure cannot restart system_server. Package updates can
# revoke nearby-device runtime grants, so make the isolation path self-healing.
grant_bridge_bluetooth_permissions() {
    [ -f "$BRIDGE_PERMISSION_FILE" ] && return 0
    target_pkg=""
    if [ -n "$(pm path "$BRIDGE_PKG" 2>/dev/null)" ]; then
        target_pkg="$BRIDGE_PKG"
    elif [ -n "$(pm path "$BRIDGE_PKG_LEGACY" 2>/dev/null)" ]; then
        target_pkg="$BRIDGE_PKG_LEGACY"
    elif [ -f "$MODDIR/hook/PenBridge-Hook.apk" ]; then
        pm install -r "$MODDIR/hook/PenBridge-Hook.apk" >/dev/null 2>&1
        if [ -n "$(pm path "$BRIDGE_PKG" 2>/dev/null)" ]; then
            target_pkg="$BRIDGE_PKG"
        fi
    fi
    if [ -z "$target_pkg" ]; then
        echo "[$(date '+%F %T')] bridge permission grant skipped: Hook APK unavailable"
        return 0
    fi
    failed=0
    for permission in \
            android.permission.BLUETOOTH_CONNECT \
            android.permission.BLUETOOTH_SCAN; do
        if ! pm grant --user 0 "$target_pkg" "$permission" >/dev/null 2>&1; then
            failed=1
            echo "[$(date '+%F %T')] bridge permission grant failed permission=$permission pkg=$target_pkg"
        fi
    done
    if [ "$failed" = 0 ]; then
        : >"$BRIDGE_PERMISSION_FILE"
        echo "[$(date '+%F %T')] bridge Bluetooth runtime permissions granted for $target_pkg"
    fi
}

# The bridge Hook APK has no launcher component, so a package (re)install or
# force-stop leaves it in the stopped state. A stopped app's ContentProvider is
# not resolvable from system_server, which silently drops every haptic dispatch
# with "Failed to find provider info". Keep it un-stopped so the provider (and
# therefore the writing haptic) is always reachable.
unstop_bridge() {
    for pkg in "$BRIDGE_PKG" "$BRIDGE_PKG_LEGACY"; do
        if [ -n "$(pm path "$pkg" 2>/dev/null)" ]; then
            if pm unstop --user 0 "$pkg" >/dev/null 2>&1; then
                echo "[$(date '+%F %T')] bridge Hook APK un-stopped ($pkg)"
            fi
        fi
    done
}

hide_hidctl_launcher() {
    [ -f "$HIDCTL_LAUNCHER_FILE" ] && return 0
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID launcher hide skipped: helper APK unavailable"
        return 0
    fi
    if pm disable --user 0 com.aclaniakea.penhidctl/.MainActivity >/dev/null 2>&1; then
        : >"$HIDCTL_LAUNCHER_FILE"
        echo "[$(date '+%F %T')] HID launcher activity disabled"
    else
        echo "[$(date '+%F %T')] HID launcher activity disable deferred"
    fi
}

retry_hidctl_setup() {
    attempt=1
    while [ "$attempt" -le 12 ] && [ ! -e "$CPS_DISABLED" ]; do
        hide_hidctl_launcher
        grant_hidctl_bluetooth_permissions
        grant_bridge_bluetooth_permissions
        unstop_bridge
        [ -f "$HIDCTL_PERMISSION_FILE" ] && [ -f "$BRIDGE_PERMISSION_FILE" ] && return 0
        sleep_sec 5
        attempt=$((attempt + 1))
    done
    echo "[$(date '+%F %T')] HID setup retry window expired"
}

hide_hidctl_launcher
grant_hidctl_bluetooth_permissions
grant_bridge_bluetooth_permissions
unstop_bridge
retry_hidctl_setup &

# The LSPosed Hook is a separate package now. This Root service deliberately
# does not call pm install and never copies an APK into /data/app.
echo "[$(date '+%F %T')] stable LSPosed Pen Hook payload expected"

# The ported OplusBatteryManager reports wirelessPenPresent=0 even when the
# real Hall pair is 0/1 (pen docked).  That makes the bridge's Java poller
# classify every magnetic edge as undocked and the OEM Capsule is never
# requested.  Read the two real Hall nodes in root context and feed the
# already-installed ColorOS handoff/capsule receiver.  Battery and charging
# still come from the CPS/GATT-backed settings and uevent; this monitor only
# repairs the physical magnetic edge.
read_hall_state() {
    # TB522FU: och1909 hall3. Driver writes prefix "hall13" (its own
    # format-string bug), so file content is "hall13 value = N" (N=0 docked).
    # The trailing-digit extraction is prefix-agnostic: fine either way.
    # The trailing-digit extraction avoids spawning tr/awk per sample.
    hall=
    [ -r "$PEN1_HALL" ] && IFS= read -r hall <"$PEN1_HALL"
    hall=${hall##* }
    case "$hall" in
        0) echo 1 ;; # docked (hall3 low when pen attached)
        1) echo 0 ;; # detached
        *) echo -1 ;;
    esac
}

valid_level() {
    case "$1" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$1" -ge 0 ] && [ "$1" -le 100 ]
}

read_level_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    for key in LEVEL BATTERY_LEVEL BATTERY_LEVEL_PERCENT BATTERY CAPACITY PEN_BATTERY; do
        level=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -1 | tr -d '\r')
        if valid_level "$level"; then
            echo "$level"
            return 0
        fi
    done
}

cache_hardware_battery() {
    level="$1"
    valid_level "$level" || return 0
    # A CPS/HID read can be repeated by several monitors.  Settings writes
    # wake SettingsProvider and IPeManager, so only publish an actual change.
    current=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
    [ "$current" = "$level" ] || \
        settings put global ipe_pencil_battery_level "$level" >/dev/null 2>&1
    current=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
    [ "$current" = "$level" ] || \
        settings put global lenovo_pen_last_valid_battery "$level" >/dev/null 2>&1
    current=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    [ "$current" = 1 ] || \
        settings put global lenovo_pen_hardware_battery_valid 1 >/dev/null 2>&1
    now=$(date '+%s' 2>/dev/null)
    case "$now" in
        ''|*[!0-9]*) ;;
        *) settings put global lenovo_pen_hardware_battery_last_at "$now" >/dev/null 2>&1 ;;
    esac
}

suspect_unconnected_zero_battery() {
    [ "$1" = 0 ] || return 1
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    [ "$connected" != 1 ]
}

read_hardware_battery() {
    level=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
    battery_valid=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    if valid_level "$level" && [ "$battery_valid" = 1 ] \
            && ! suspect_unconnected_zero_battery "$level"; then
        echo "$level"
        return 0
    fi
    level=$(read_level_from_file "$CPS_UEVENT")
    # CPS exposes LEVEL=0 while the docked pen is only waiting to power up.
    # A genuine 0% sample is still accepted from the live BLE/GATT cache, but
    # a raw kernel/CPS zero is never promoted to trusted battery state.
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    if [ "$connected" = 1 ] && valid_level "$level" && [ "$level" -gt 0 ]; then
        cache_hardware_battery "$level"
        echo "$level"
        return 0
    fi
    level=$(read_level_from_file "$UEVENT")
    if [ "$connected" = 1 ] && valid_level "$level" && [ "$level" -gt 0 ]; then
        cache_hardware_battery "$level"
        echo "$level"
        return 0
    fi
    # Never turn an unavailable sample into a new 0% value.  Keep the last
    # hardware sample until the GATT link reports a fresh one.
    level=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
    if valid_level "$level"; then
        echo "$level"
    else
        echo -1
    fi
}

read_charge_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    for key in CHARGING_STATE CHARGING CHARGE_STATE WIRELESS_CHARGING; do
        charge_state=$(sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -1 | tr -d '\r')
        case "$charge_state" in
            1|Charging|charging|Charge|charge|WIRELESS_CHARGING|Wireless\ Charging)
                echo 1
                return 0
                ;;
            0|Full|full|Not\ charging|not_charging|Discharging|discharging|Idle|idle|None|none)
                echo 0
                return 0
                ;;
        esac
    done
}

read_cps_charging() {
    # CPS8601 收发器真值。本机 DT uevent 里没有 CHARGING/ATTACHED 键，
    # 所以收发器节点是唯一可信的内核侧来源：
    #   tx_status "cps_wls_en:1" -> 线圈在送电（正在充电）
    #   tx_status "cps_wls_en:0" -> 驱动已断（充满或未吸附）
    # 2026-09-18 实测确认：笔充满时驱动会自己置 cps_wls_en:0。因此它
    # 同时是「充电中」与「笔在不在线圈上」的真值。本模块只读不写。
    state=
    if [ -n "$CPS_TX_STATUS" ] && [ -r "$CPS_TX_STATUS" ]; then
        IFS= read -r line <"$CPS_TX_STATUS"
        case "$line" in
            *cps_wls_en:1*) state=1 ;;
            *cps_wls_en:0*) state=0 ;;
        esac
    fi
    if [ -z "$state" ] && [ -n "$CPS_PS_ONLINE" ] && [ -r "$CPS_PS_ONLINE" ]; then
        state=$(tr -d '\r' <"$CPS_PS_ONLINE" 2>/dev/null)
    fi
    case "$state" in 0|1) echo "$state" ;; esac
}

cache_hardware_charging() {
    state="$1"
    case "$state" in
        0|1)
            settings put global lenovo_pen_hardware_charge_state "$state" >/dev/null 2>&1
            settings put global lenovo_pen_hardware_charge_valid 1 >/dev/null 2>&1
            # Keep the OEM charging keys in sync with the CPS truth so the
            # Hook handoff cannot override a fresh hardware sample with a
            # stale BLE 2A1A value.
            settings put global lenovo_pen_oem_charge_valid 1 >/dev/null 2>&1
            settings put global lenovo_pen_oem_charge_state "$state" >/dev/null 2>&1
            ;;
    esac
}

read_attached_from_file() {
    file="$1"
    [ -r "$file" ] || return 0
    sed -n 's/^ATTACHED=//p' "$file" 2>/dev/null | head -1 | tr -d '\r'
}

read_hardware_charging() {
    docked="$1"
    if [ "$docked" = 0 ]; then
        # Hall is authoritative for physical charging. CPS ATTACHED and its
        # charge byte can remain latched after removal; never mirror that
        # stale state into the control-center widget or the next capsule.
        cache_hardware_charging 0
        echo 0
        return 0
    fi
    charge_state=$(read_charge_from_file "$CPS_UEVENT")
    attached=$(read_attached_from_file "$CPS_UEVENT")
    case "$charge_state" in
        0|1)
            if [ "$charge_state" = 0 ] || { [ "$attached" = 1 ] || [ "$docked" = 1 ]; }; then
                cache_hardware_charging "$charge_state"
                echo "$charge_state"
                return 0
            fi
            cache_hardware_charging 0
            echo 0
            return 0
            ;;
    esac
    # 本机（sun/SM8750P）的 CPS DT uevent 不带任何键值，上面整块会空转，
    # 于是充电状态只能退到 BLE 记忆值。改从 CPS 收发器 tx_status 取真值。
    # 插在这里而非 UEVENT 之后：两者都是 CPS 硬件路径，该来源比 BLE 记忆
    # 更权威；而本机 lenovo_penraw uevent 只有 MAJOR/MINOR/DEVNAME，取不到
    # 值，所以对 pineapple（其 CPS uevent 带键值、在上面已 return）无影响。
    charge_state=$(read_cps_charging)
    case "$charge_state" in
        0|1)
            cache_hardware_charging "$charge_state"
            echo "$charge_state"
            return 0
            ;;
    esac
    charge_state=$(read_charge_from_file "$UEVENT")
    case "$charge_state" in
        0|1)
            cache_hardware_charging "$charge_state"
            echo "$charge_state"
            return 0
            ;;
    esac
    charge_valid=$(settings get global lenovo_pen_hardware_charge_valid 2>/dev/null | tr -d '\r')
    charge_state=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
        return 0
    fi
    charge_valid=$(settings get global lenovo_pen_oem_charge_valid 2>/dev/null | tr -d '\r')
    charge_state=$(settings get global lenovo_pen_oem_charge_state 2>/dev/null | tr -d '\r')
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
    else
        echo 0
    fi
}

is_pen_mac() {
    case "$1" in
        00:00:00:00:00:00) return 1 ;;
        [0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]:[0-9A-Fa-f][0-9A-Fa-f]) return 0 ;;
        *) return 1 ;;
    esac
}

normalize_pen_mac() {
    raw=$(printf '%s' "$1" | tr -d '\r' | tr 'a-f' 'A-F')
    case "$raw" in
        [0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F]:[0-9A-F][0-9A-F])
            printf '%s\n' "$raw"
            ;;
        [0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F][0-9A-F])
            printf '%s\n' "$raw" | sed 's/../&:/g; s/:$//'
            ;;
        *)
            printf '\n'
            ;;
    esac
}

pen_name_matches() {
    name=$(printf '%s' "$1" | tr -d '\r"' | tr 'A-Z' 'a-z')
    case "$name" in
        *pen*|*stylus*|*pencil*|*lenovo*|*xiaoxin*|*yoga*|*picasso*) return 0 ;;
        *) return 1 ;;
    esac
}

same_pen_mac() {
    left=$(normalize_pen_mac "$1")
    right=$(normalize_pen_mac "$2")
    [ -n "$left" ] && [ "$left" = "$right" ]
}

# Prefer the configured address when it is still a bonded pen. If it is stale,
# select a bonded pen by its advertised name and persist the new address. This
# keeps the Bluetooth path independent of any factory MAC without attempting
# unrelated bonded devices.
find_bonded_pen_mac() {
    preferred=$(normalize_pen_mac "$1")
    for config in \
            /data/misc/bluedroid/bt_config.conf \
            /data/misc/bluetooth/bt_config.conf \
            /data/misc/bluetooth/bt_config.conf.old; do
        [ -r "$config" ] || continue
        best=""
        block_mac=""
        block_name=""
        while IFS= read -r line || [ -n "$line" ]; do
            case "$line" in
                \[*\])
                    block_key=${line#\[}
                    block_key=${block_key%\]}
                    candidate=$(normalize_pen_mac "$block_key")
                    if [ -n "$block_mac" ] && pen_name_matches "$block_name"; then
                        if same_pen_mac "$block_mac" "$preferred"; then
                            printf '%s\n' "$block_mac"
                            return 0
                        fi
                        [ -n "$best" ] || best="$block_mac"
                    fi
                    block_mac="$candidate"
                    block_name=""
                    ;;
                Name=*)
                    block_name=${line#Name=}
                    ;;
            esac
        done <"$config"
        if [ -n "$block_mac" ] && pen_name_matches "$block_name"; then
            if same_pen_mac "$block_mac" "$preferred"; then
                printf '%s\n' "$block_mac"
                return 0
            fi
            [ -n "$best" ] || best="$block_mac"
        fi
        if [ -n "$best" ]; then
            printf '%s\n' "$best"
            return 0
        fi
    done
    printf '\n'
}

resolve_pen_mac() {
    configured=$(normalize_pen_mac "$(settings get global ipe_pencil_mac_addr 2>/dev/null)")
    resolved=$(find_bonded_pen_mac "$configured")
    if ! is_pen_mac "$resolved"; then
        resolved="$configured"
    fi
    if is_pen_mac "$resolved" && ! same_pen_mac "$resolved" "$configured"; then
        settings put global ipe_pencil_mac_addr "$resolved" >/dev/null 2>&1
        echo "[$(date '+%F %T')] selected bonded pen address=$resolved previous=$configured" >&2
    fi
    printf '%s\n' "$resolved"
}

pen_mac_compact() {
    mac=$(resolve_pen_mac)
    printf '%s\n' "$mac" | tr -d ':'
}

request_pen_capsule() {
    [ "$(read_hall_state)" = 1 ] || return 0
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    if [ "$connected" != 1 ]; then
        echo "[$(date '+%F %T')] magnetic capsule delayed: BLE link not ready"
        return 1
    fi
    # 胶囊是「磁吸吸附」这个物理动作的即时反馈，必须在吸附边沿弹出，不能
    # 等一次新鲜油表采样。此前这里硬卡 lenovo_pen_hardware_battery_valid==1，
    # 而该标志恰好在吸附边沿被主动清 0（见 monitor_hall_capsule），本机内核
    # 侧又没有任何新鲜电量源（CPS 路径写错、penraw uevent 无 LEVEL），于是
    # 只能等厂家 BLE 栈推首帧 GATT 样本 —— 实测 10~21 秒，且多次直接超时
    # 不弹。改为：优先新鲜样本，取不到就退化到最后一次真实采样（hook 侧的
    # invalidateHardwareBattery 本来就设计成保留上一次电量可见）。
    battery=$(read_hardware_battery)
    if ! valid_level "$battery"; then
        echo "[$(date '+%F %T')] magnetic capsule delayed: no battery sample yet"
        return 1
    fi
    battery_valid=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    [ "$battery_valid" = 1 ] || \
        echo "[$(date '+%F %T')] magnetic capsule uses last valid battery=$battery (fresh sample pending)"
    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$CAPSULE_DEDUP_FILE" 2>/dev/null)
    previous_time=${previous%%:*}
    previous_state=${previous#*:}
    case "$now:$previous_time" in
        *[!0-9:]*|:*) ;;
        *)
            if [ "$previous_state" = 1 ] && [ "$now" -ge "$previous_time" ] \
                    && [ "$((now - previous_time))" -lt "$CAPSULE_DEDUP_SECONDS" ]; then
                echo "[$(date '+%F %T')] duplicate magnetic capsule suppressed"
                return 0
            fi
            ;;
    esac
    charging=$(read_hardware_charging 1)
    mac=$(pen_mac_compact)
    am broadcast --user 0 --receiver-foreground \
        -a com.futureharmony.lenovopenbridge.action.SHOW_PENCIL_CAPSULE \
        -p com.oplus.ipemanager \
        --ei battery_level "$battery" \
        --ei charging_state "$charging" \
        --ei charging "$charging" \
        --es present 1 \
        --es macAddr "$mac" \
        --es source hardware_hall_root >/dev/null 2>&1
    echo "$now:1" >"$CAPSULE_DEDUP_FILE"
    echo "[$(date '+%F %T')] real Hall magnetic capsule requested battery=$battery charging=$charging mac=$mac"
}

request_pen_capsule_when_ready() {
    # 同一个吸附边沿只允许一个重试循环。此前每次 dock 边沿都 fork 一个新
    # worker，而 hall 在吸附瞬间会抖动，再加上开机路径那个延迟 22 秒的
    # worker，常有两三个循环同时存活 —— pen-bridge.log 里成对的重复行就是
    # 它们写的，胶囊也被重复尝试广播。用标记文件去重；任何退出路径都必须
    # 清理，否则会永久堵死后续边沿（服务启动时会先清一次）。
    if [ -e "$CAPSULE_WORKER_FILE" ]; then
        return 0
    fi
    : >"$CAPSULE_WORKER_FILE"
    attempts=0
    while [ "$attempts" -lt 40 ] && [ "$(read_hall_state)" = 1 ]; do
        if request_pen_capsule; then
            rm -f "$CAPSULE_WORKER_FILE"
            return 0
        fi
        attempts=$((attempts + 1))
        sleep_sec 0.5
    done
    rm -f "$CAPSULE_WORKER_FILE"
    echo "[$(date '+%F %T')] magnetic capsule abandoned: link/battery not ready after ${attempts} attempts"
}

publish_hall_state() {
    docked="$1"
    battery=$(read_hardware_battery)
    charging=$(read_hardware_charging "$docked")
    mac=$(pen_mac_compact)
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    [ "$connected" = 1 ] || connected=0

    settings put global lenovo_pen_physical_docked "$docked" >/dev/null 2>&1
    settings put global ipe_pencil_charging_state "$charging" >/dev/null 2>&1
    if valid_level "$battery"; then
        # This is a cache update only after a real sample.  An unavailable
        # CPS/GATT sample must not overwrite the last known level with -1.
        settings put global ipe_pencil_battery_level "$battery" >/dev/null 2>&1
    fi
    refresh_active=0
    if [ "$docked" != 1 ] && [ "$connected" = 1 ]; then
        refresh_active=1
    fi
    # The refresh-rate policy is valid only while writing, but Device Space
    # treats the next two keys as the existence of a connected pen. Do not
    # hide a real Bluetooth device merely because it is magnetically docked.
    settings put global lenovo_pen_refresh_active "$refresh_active" >/dev/null 2>&1
    # OPlusRefreshRatePolicyImpl reads settings_enable_oppo_pencil as
    # isIPEPencilConnected and votes ipePencilRateId (120 Hz) while it is 1.
    # A docked pen is not being written with, so report "pen in use" only
    # when the pen is both connected and off the magnetic dock.
    settings put global settings_enable_oppo_pencil "$refresh_active" >/dev/null 2>&1
    settings put global ipe_pencil_present "$connected" >/dev/null 2>&1

    battery_args=""
    battery_trusted=$(settings get global lenovo_pen_hardware_battery_valid 2>/dev/null | tr -d '\r')
    if valid_level "$battery" && [ "$battery_trusted" = 1 ]; then
        battery_args="--ei battery_level $battery --ei batteryLevel $battery"
        hardware_battery=true
    else
        hardware_battery=false
    fi
    am broadcast --user 0 --receiver-foreground \
        -a com.futureharmony.lenovopenbridge.action.COLOROS_PEN_STATE \
        -p com.oplus.ipemanager \
        $battery_args \
        --ei charging_state "$charging" \
        --ei chargingState "$charging" \
        --ei charging "$charging" \
        --ei physicalDocked "$docked" \
        --es macAddr "$mac" \
        --es name "Lenovo Tab Pen Pro" \
        --es source hardware_hall \
        --ez hardware_battery "$hardware_battery" \
        --ez hardware_identity_known true >/dev/null 2>&1
    echo "[$(date '+%F %T')] real Hall state docked=$docked battery=$battery trusted=$hardware_battery charging=$charging connected=$connected mac=$mac"
}

monitor_battery_cache() {
    while [ ! -e "$CPS_DISABLED" ]; do
        # Before the real HOGP link exists, IPeManager deliberately owns an
        # unknown battery state. Re-injecting a cached level every 10 seconds
        # makes it clear that state again, then restarts the same UI/BT work.
        # Hall-edge publication already preserves the last known value for
        # the capsule; continuous repair begins only after a real link.
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        if [ "$connected" != 1 ]; then
            sleep_sec 10
            continue
        fi
        before=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
        level=$(read_hardware_battery)
        # The OEM process can publish an unknown sample after the Root boot
        # snapshot. Repair only that cache transition, then let the normal
        # settings receiver consume one valid battery notification.
        if valid_level "$level" && [ "$before" != "$level" ]; then
            docked=$(read_hall_state)
            case "$docked" in
                0|1)
                    publish_hall_state "$docked"
                    echo "[$(date '+%F %T')] invalid battery cache repaired level=$level"
                    ;;
            esac
        fi
        sleep_sec 10
    done
}

monitor_charging_cache() {
    last=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        # As with battery repair, avoid fighting IPeManager's disconnected
        # state machine. A Hall edge still publishes the physical dock/charge
        # snapshot once; periodic correction is for an established BLE link.
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        if [ "$connected" != 1 ]; then
            sleep_sec 10
            continue
        fi
        docked=$(read_hall_state)
        case "$docked" in
            0|1)
                charging=$(read_hardware_charging "$docked")
                case "$charging" in
                    0|1)
                        mirrored=$(settings get global ipe_pencil_charging_state 2>/dev/null | tr -d '\r')
                        if [ "$charging" != "$last" ]; then
                            last="$charging"
                            publish_hall_state "$docked"
                            echo "[$(date '+%F %T')] charging state changed charging=$charging mirrored=$mirrored docked=$docked"
                        fi
                        ;;
                esac
                ;;
        esac
        sleep_sec 10
    done
}

# Two-way sync (system Bluetooth -> Device Space). dumpsys masks the first
# four MAC octets, so match the visible last two octets of the pen address
# against the HOGP (LE HID) profile state; HOGP state 2 is a live link.
#
# v0.1.17 简化：powered_down 否决（pen-revive-guard 维护）已随该守护一并
# 裁撤 —— 官方系统没有"笔掉电标记"，蓝牙栈的 HOGP state 就是唯一真值。
# 历史教训保留：曾试验过的 powered_down 陈旧否决会让真实在线的笔被判成
# 未连接（docs/pen_undock_after_dock_sleep_unusable_20260921.md §1.5），
# 这也是"官方没有的逻辑一律不加"的直接证据。
bt_stack_hogp_live() {
    mac=$(resolve_pen_mac)
    is_pen_mac "$mac" || return 1
    tail=${mac#*:*:*:*:}
    case "$tail" in
        *[!0-9A-Fa-f:]*|'') return 1 ;;
    esac
    tail=$(printf '%s' "$tail" | tr 'A-Z' 'a-z')
    # Match one current profile-summary line only. The bluetooth dump also
    # contains connection history; matching the MAC and state independently
    # across the whole dump made a disconnected pen look connected whenever
    # an old "HOGP connection state=2" record was still present.
    dumpsys bluetooth_manager 2>/dev/null \
        | tr 'A-Z' 'a-z' \
        | grep -E "$tail .*hogp connection state=2" >/dev/null 2>&1
}

real_bt_connected() {
    # v0.1.17：pen-revive-guard 已按"官方无此逻辑"裁撤，powered_down
    # 不再有写入方；真实链路只信蓝牙栈本身。
    bt_stack_hogp_live
}

# 笔是否正在线圈上真实响应（CPS 侧带内通信判据）。
# 与 real_bt_connected() 的分工：后者读蓝牙栈，而笔突然掉电时 HOGP state
# 会滞后保持 2（僵尸连接）；本判据读 CPS 内核驱动，与 Hall 磁场边沿同步，
# 实测 TX 关闭时同秒归零（见 docs/pen-sleep-death-analysis.md 的 1 秒序列）。
pen_cps_responsive() {
    [ -n "$CPS_PS_ONLINE" ] && [ -r "$CPS_PS_ONLINE" ] || return 1
    [ "$(tr -d '\r' <"$CPS_PS_ONLINE" 2>/dev/null)" = 1 ]
}


# 用户是否已解锁。仅用于把失败原因写清楚（诊断），**不参与任何决策** ——
# 决策一律以真实的 am 投递结果为准。
# 实测 `dumpsys user` 单次约 13ms。
user_unlocked() {
    dumpsys user 2>/dev/null | grep -q 'State: RUNNING_UNLOCKED'
}


# 笔在用时的 120Hz 由原厂 OplusRefreshRatePolicyImpl 依据
# settings_enable_oppo_pencil 自行投票；本模块不读取或写入用户的
# system min_refresh_rate / peak_refresh_rate。

# The OEM mirrors can lag or be overridden by a stale disconnect latch. Poll
# the actual Bluetooth stack at low rate and republish the connection
# mirrors so Device Space always follows the real link. If a live link appears
# while a stale user-disconnect latch is still set, the stack has already
# reconnected: clear the latch so the UI stops reporting "disconnected".
# 用户在设置页或设备空间点「断开连接」/「立即连接」之后，真实 ACL 链路需要
# 一到三秒才跟上。Hook 会同时写下意图目标与时间戳；在收敛窗口内对账器既不
# 改写连接镜像，也不清 user latch。否则它会把用户半秒前刚表达的意图当成
# 「陈旧闩锁」清掉，自动重连随即把 UI 翻回已连接，表现为反复横跳。
PEN_USER_ACTION_GRACE=8

in_user_action_window() {
    real_state="$1"
    ts=$(settings get global lenovo_pen_user_action_ts 2>/dev/null | tr -d '\r')
    target=$(settings get global lenovo_pen_user_action_target 2>/dev/null | tr -d '\r')
    case "$ts" in ''|*[!0-9]*) return 1 ;; esac
    case "$target" in 0|1) ;; *) return 1 ;; esac
    # 真实链路已经追上用户意图：立即退出窗口，恢复正常对账。
    [ "$real_state" = "$target" ] && return 1
    now=$(date '+%s' 2>/dev/null)
    case "$now" in ''|*[!0-9]*) return 1 ;; esac
    # 时钟回拨时按窗口外处理，避免窗口被拉长到不确定的时间。
    [ "$now" -lt "$ts" ] && return 1
    [ "$((now - ts))" -lt "$PEN_USER_ACTION_GRACE" ]
}

monitor_real_bt_state() {
    last=-1
    last_oem_recovery=0
    oem_recovery_attempts=0
    oem_recovery_exhausted=0
    # 「投递不出去」的独立计数：未解锁 / 包未登记时的失败不消耗 3 次尝试预算。
    oem_recovery_blocked=0
    while [ ! -e "$CPS_DISABLED" ]; do
        if real_bt_connected; then
            connected=1
        else
            connected=0
        fi
        # 用户刚操作过且链路尚未收敛：本轮完全不发布，只快速重采样。
        # 这是有界的（PEN_USER_ACTION_GRACE 秒），收敛后立即回到 30 秒低频对账，
        # 不会变成常驻 1Hz 轮询。
        if in_user_action_window "$connected"; then
            sleep_sec 1
            continue
        fi
        current=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        [ "$current" = 1 ] || current=0
        if [ "$connected" != "$current" ] || [ "$connected" != "$last" ]; then
            settings put global lenovo_pen_link_connected "$connected" >/dev/null 2>&1
            # OPlusRefreshRateService treats ipe_pencil_connect_state==1 as the
            # IPE pencil connected (isIPEPencilConnected) and votes the OEM
            # ipePencilRateId (120 Hz) while connected. The other mirror keys
            # keep the legacy connected encoding for the pen settings UI.
            connect_state=0
            [ "$connected" = 1 ] && connect_state=2
            for key in ipe_pencil_connection_state PENCIL_CONNECT_STATE pencil_connect_state; do
                settings put global "$key" "$connect_state" >/dev/null 2>&1
            done
            settings put global ipe_pencil_connect_state "$connected" >/dev/null 2>&1
            docked=$(settings get global lenovo_pen_physical_docked 2>/dev/null | tr -d '\r')
            [ "$docked" = 1 ] || docked=0
            pen_in_use=0
            [ "$connected" = 1 ] && [ "$docked" != 1 ] && pen_in_use=1
            settings put global settings_enable_oppo_pencil "$pen_in_use" >/dev/null 2>&1
            settings put global ipe_pencil_present "$connected" >/dev/null 2>&1
            if [ "$connected" = 1 ]; then
                user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
                if [ "$user_requested" = 1 ]; then
                    settings put global lenovo_pen_user_disconnect_requested 0 >/dev/null 2>&1
                    settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
                    echo "[$(date '+%F %T')] real BT link present; cleared stale disconnect latch"
                fi
            fi
            # Push the real link to the OEM UI: the Hook's handoff receiver
            # consumes the connected extra and forwards it to the panel and
            # settings callbacks, so a sheet opened before the link came up
            # stops showing a stale "connecting/disconnected" state.
            battery=$(settings get global ipe_pencil_battery_level 2>/dev/null | tr -d '\r')
            valid_level "$battery" || battery=$(settings get global lenovo_pen_last_valid_battery 2>/dev/null | tr -d '\r')
            charging=$(settings get global lenovo_pen_hardware_charge_state 2>/dev/null | tr -d '\r')
            case "$charging" in 0|1) ;; *) charging=0 ;; esac
            mac=$(pen_mac_compact)
            am broadcast --user 0 --receiver-foreground \
                -a com.futureharmony.lenovopenbridge.action.COLOROS_PEN_STATE \
                -p com.oplus.ipemanager \
                --ei connected "$connected" \
                --ei battery_level "$battery" \
                --ei charging_state "$charging" \
                --es present "$connected" \
                --es macAddr "$mac" \
                --es source hardware_hall >/dev/null 2>&1
            echo "[$(date '+%F %T')] real BT state mirror connected=$connected (was $current)"
            # A new physical ACL/HOGP session is the only sound reason to
            # retry OEM GATT-session recovery.  Do not carry a failed budget
            # across a genuine disconnect/reconnect edge.
            oem_recovery_attempts=0
            oem_recovery_exhausted=0
            oem_recovery_blocked=0
            last_oem_recovery=0
            last="$connected"
        fi
        if [ "$connected" = 1 ] \
                && [ "$(settings get global lenovo_pen_oem_control_ready 2>/dev/null | tr -d '\r')" != 1 ] \
                && [ "$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')" != 1 ]; then
            now=$(date '+%s' 2>/dev/null)
            case "$now:$last_oem_recovery" in
                *[!0-9:]*|:*) ;;
                *)
                    if [ "$oem_recovery_attempts" -lt 3 ] \
                            && [ "$now" -ge "$last_oem_recovery" ] \
                            && [ "$((now - last_oem_recovery))" -ge 30 ]; then
                        last_oem_recovery="$now"
                        # 投递失败（未解锁、包还没登记）不消耗 3 次尝试预算：那与
                        # 「原厂接收方拒绝了活链路」是两回事。开机后到首次解锁之间
                        # 必然不可达，旧实现把三次预算全烧在这里，然后一直等到下一次
                        # 真实蓝牙边沿才恢复 OEM 会话（实测 12:09 那次启动：attempt
                        # 1/3、2/3 全是 unlocked=0）。这里改成预算只记「真投出去」的
                        # 次数，投递不出去的另行计数并封顶，避免无限重试。
                        if request_oem_pen_action "$OEM_CONNECT_ACTION"; then
                            oem_recovery_attempts=$((oem_recovery_attempts + 1))
                            echo "[$(date '+%F %T')] live HOGP missing OEM haptic session; recovery requested attempt=$oem_recovery_attempts/3"
                        else
                            oem_recovery_blocked=$((oem_recovery_blocked + 1))
                            echo "[$(date '+%F %T')] OEM haptic recovery not deliverable; channel unreachable, budget kept (${oem_recovery_blocked}/40)"
                            if [ "$oem_recovery_blocked" -ge 40 ]; then
                                oem_recovery_exhausted=1
                                echo "[$(date '+%F %T')] OEM haptic recovery deferred until next real BT link edge"
                            fi
                        fi
                    elif [ "$oem_recovery_attempts" -ge 3 ] && [ "$oem_recovery_exhausted" = 0 ]; then
                        # The OEM receiver rejected a live link repeatedly.
                        # Retrying forever turns a missing optional session
                        # into a 30-second Bluetooth/system_server CPU spike.
                        # The direct GATT haptic path remains available; try
                        # OEM recovery again only after a real link edge.
                        oem_recovery_exhausted=1
                        echo "[$(date '+%F %T')] OEM haptic recovery deferred until next real BT link edge"
                    fi
                    ;;
            esac
        fi
        # Real ACL/GATT callbacks update the mirror immediately.  Keep this
        # expensive full bluetooth_manager dump as a low-rate reconciliation
        # path only, not a one-Hz permanent poll.
        trim_log_if_large
        sleep_sec 30
    done
}

monitor_hall_capsule() {
    candidate=-1
    samples=0
    boot_cycle=1
    last=$(cat "$HALL_STATE_FILE" 2>/dev/null | tr -d '\r')
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        state=$(read_hall_state)
        case "$state" in
            0|1)
                if [ "$state" = "$candidate" ]; then
                    samples=$((samples + 1))
                else
                    candidate="$state"
                    samples=1
                fi
                if [ "$samples" -ge 2 ]; then
                    # Do not republish solely because a receiver has not yet
                    # mirrored the setting. That feedback loop generated
                    # repeated system_server broadcasts every poll interval.
                    if [ "$boot_cycle" = 1 ] || [ "$state" != "$last" ]; then
                        previous="$last"
                        last="$state"
                        echo "$state" >"$HALL_STATE_FILE"
                        if [ "$state" = 1 ] && [ "$previous" != 1 ]; then
                            # A dock edge starts a new pen power session. Do
                            # not let the previous session's cached battery
                            # satisfy this attach's capsule readiness check.
                            settings put global lenovo_pen_hardware_battery_valid 0 >/dev/null 2>&1
                        fi
                        publish_hall_state "$state"
                        if [ "$state" = 1 ]; then
                            # Magnetic attach is a physical reconnect intent:
                            # clear any stale disconnect latch and restore the
                            # link if it is not already up at the BT layer.
                            settings put global lenovo_pen_user_disconnect_requested 0 >/dev/null 2>&1
                            settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
                            # 吸附边沿 = 一次全新的物理会话。这里不能只看
                            # real_bt_connected()：笔上一轮满电掉电后蓝牙栈的
                            # HOGP state 会滞留为 2（僵尸连接），判据恒真就会
                            # 吃掉这次重连请求 —— 表现正是「吸上去是死的，必须
                            # 再吸一次」。补一层 CPS 判据：笔没在线圈上真实响应
                            # 时必须重连，不管蓝牙栈怎么说。
                            # request_pen_connect 自带 8 秒去重，Hall 抖动不会
                            # 变成重连风暴。
                            if ! real_bt_connected || ! pen_cps_responsive; then
                                request_pen_connect
                            fi
                            if [ "$boot_cycle" = 1 ]; then
                                # IPeManager's BLE process registers the
                                # dynamic Capsule receiver late in boot. Do
                                # not spend the only boot request before it
                                # exists; the real Hall state is already
                                # published above.
                                (
                                    sleep_sec 22
                                    request_pen_capsule_when_ready
                                ) &
                            else
                                request_pen_capsule_when_ready &
                            fi
                        elif [ "$state" = 0 ] && [ "$previous" = 1 ]; then
                            # 离开吸附位：关闭吸附胶囊。
                            am broadcast --user 0 --receiver-foreground \
                                -a com.futureharmony.lenovopenbridge.action.DISMISS_PENCIL_CAPSULE \
                                -p com.oplus.ipemanager >/dev/null 2>&1
                            # 唤醒守护武装：本次吸附会话允许一轮唤醒尝试
                            # （monitor_wake_guard 消费；守护默认开启，开关在
                            # run_wake_guard_once 内二次确认）。
                            # 内容 = 离座时刻的 epoch：守护据此算"离座 -> 首个
                            # 笔画"的延迟。用 touch 会丢掉这个基准，改 > 写。
                            date '+%s' >"$WAKE_GUARD_ARM_FILE" 2>/dev/null
                        fi
                    fi
                    boot_cycle=0
                fi
                ;;
        esac
        sleep_sec 1
    done
}

# penhidctl 的执行回执：PenHidService 结束前把 "<elapsedRealtime> <action>
# ok|fail <detail>" 写进自己的 files 目录。锁屏（DBA）态落在 /data/user_de/0/，
# 解锁后落在 /data/user/0/，两个都探测。
hidctl_result_file() {
    for f in /data/user_de/0/com.aclaniakea.penhidctl/files/penhid.result \
             /data/user/0/com.aclaniakea.penhidctl/files/penhid.result; do
        [ -f "$f" ] && { printf '%s' "$f"; return 0; }
    done
    return 1
}

run_hidctl() {
    action="$1"
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        echo "[$(date '+%F %T')] HID $action skipped: no bonded pen MAC"
        return 1
    fi
    if [ -z "$(pm path com.aclaniakea.penhidctl 2>/dev/null)" ]; then
        echo "[$(date '+%F %T')] HID $action skipped: helper APK unavailable"
        return 1
    fi
    grant_hidctl_bluetooth_permissions
    # 先清回执再调用：任何随后出现的回执都必然属于本次调用
    # （elapsedRealtime 开机即归零，跨重启比较时间戳不可靠，靠"先删后收"排除旧件）。
    rm -f /data/user_de/0/com.aclaniakea.penhidctl/files/penhid.result \
          /data/user/0/com.aclaniakea.penhidctl/files/penhid.result 2>/dev/null
    if ! am start-foreground-service --user 0 -n "$HIDCTL_SERVICE" \
            --es action "$action" --es mac "$mac" >/dev/null 2>&1; then
        # rc!=0：PMS 拒绝解析（组件不可见 / 非 direct-boot-aware / 包未登记），
        # "Error: Not found; no service started"。am 退出码是同步判据，无需等回执。
        echo "[$(date '+%F %T')] HID $action request rejected mac=$mac unlocked=$(user_unlocked && echo 1 || echo 0)"
        return 1
    fi
    # am 的 rc=0 只证明"投递成功"，不证明"做了"。轮询回执；服务自身 8s
    # profile 超时也会落一份 fail 回执，所以窗口要盖过它（12s）。
    receipt=
    i=0
    while [ "$i" -lt 12 ]; do
        sleep_sec 1
        rf=$(hidctl_result_file)
        if [ -n "$rf" ]; then
            line=$(cat "$rf" 2>/dev/null)
            # 回执必须匹配本次 action（防止并发调用时串台）。
            case "$line" in
                *" $action "*) receipt=$line; break ;;
            esac
        fi
        i=$((i + 1))
    done
    if [ -z "$receipt" ]; then
        echo "[$(date '+%F %T')] HID $action no receipt within 12s mac=$mac (service may have crashed before writing)"
        return 1
    fi
    set -- $receipt
    verdict="$3"
    shift 3
    detail="$*"
    if [ "$verdict" = ok ]; then
        echo "[$(date '+%F %T')] HID $action ok ($detail) mac=$mac"
        return 0
    fi
    echo "[$(date '+%F %T')] HID $action FAILED ($detail) mac=$mac"
    return 1
}

request_oem_pen_action() {
    action="$1"
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        echo "[$(date '+%F %T')] OEM $action skipped: no bonded pen MAC"
        return 0
    fi
    if [ -z "$(pm path com.oplus.ipemanager 2>/dev/null)" ]; then
        # 「包还不在 PMS 视野里」（开机早期 priv-app 尚未登记，实测 12:08:55
        # 那几次 `pm path` 全空）也不等于投递成功：调用方若据此认为 OEM 通路
        # 可用，就会在只有 HID 半边可用的时候先断链。硬重连把它当失败，延后
        # 重试；其它调用方本来就不看返回值，行为不变。
        echo "[$(date '+%F %T')] OEM $action unavailable: IPeManager not registered yet"
        return 1
    fi
    # These are the vendor service's real actions.  CONNECT_PENCIL reaches
    # s0.x()/BleManager.b(), while DISCONNECT_PENCIL reaches s0.z() and the
    # hidden BluetoothDevice.disconnect() path.  The old Root service only
    # sent a custom system_server broadcast, which could not close/open the
    # OEM GATT link.
    extra=""
    if [ "$action" = "$OEM_CONNECT_ACTION" ]; then
        extra="--ez codex_auto_connect true"
    fi
    if am startservice --user 0 -n "$OEM_CORE_SERVICE" \
            -a "$action" --es device_mac_info "$mac" $extra >/dev/null 2>&1; then

        echo "[$(date '+%F %T')] OEM $action requested mac=$mac"
        return 0
    fi
    # 失败有三种成因，调用方按返回值决定是否延后（硬重连会，其它调用忽略）：
    #   1) 用户未解锁 -> PMS 过滤掉非 direct-boot-aware 的 CoreService，
    #      am 报 "Error: Not found; no service started" (rc=255)；
    #   2) 以 shell(uid 2000) 身份调用 -> "Requires permission
    #      com.oplus.permission.safe.IOT"。模块以 root(uid 0) 运行，不受此限；
    #   3) 包尚未进入 PMS（开机早期 priv-app 未登记）-> 上面的 pm path 判空。
    echo "[$(date '+%F %T')] OEM $action request failed mac=$mac unlocked=$(user_unlocked && echo 1 || echo 0)"
    return 1
}

request_pen_connect() {
    requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
    [ "$requested" = 1 ] && {
        echo "[$(date '+%F %T')] pen connect skipped: Settings disconnect latch is set"
        return 0
    }
    user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
    [ "$user_requested" = 1 ] && {
        echo "[$(date '+%F %T')] pen connect skipped: user disconnect choice is active"
        return 0
    }

    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$PEN_CONNECT_DEDUP_FILE" 2>/dev/null)
    case "$previous" in
        ''|*[!0-9]*) ;;
        *)
            [ "$now" -ge "$previous" ] && [ "$((now - previous))" -lt 8 ] && return 0
            ;;
    esac
    echo "$now" >"$PEN_CONNECT_DEDUP_FILE"
    request_oem_pen_action "$OEM_CONNECT_ACTION"
    # Let the stock CoreService create the BLE/GATT session before asking the
    # hidden HID Host profile to attach to the same bonded device. The panel
    # is forced to the real link state by the Hook, so this wait only needs
    # to cover the OEM GATT session, not the full UI round-trip.
    sleep_sec 1
    run_hidctl connect
}

# The Settings reconnect button must remain a bounded user action, but a
# single HID request can race the OEM GATT service while the pen is waking.
# Retry HID Host only (the original CoreService session stays authoritative)
# and stop immediately when the current HOGP summary reports state 2.
request_pen_connect_bounded() {
    request_pen_connect
    (
        retry=1
        while [ "$retry" -le 3 ] && [ ! -e "$CPS_DISABLED" ]; do
            sleep_sec 3
            if real_bt_connected; then
                echo "[$(date '+%F %T')] explicit pen reconnect confirmed by HOGP attempt=$retry"
                exit 0
            fi
            run_hidctl connect
            echo "[$(date '+%F %T')] explicit pen HID retry attempt=$retry"
            retry=$((retry + 1))
        done
        echo "[$(date '+%F %T')] explicit pen reconnect window ended without HOGP"
    ) &
}

request_pen_disconnect() {
    request_oem_pen_action "$OEM_DISCONNECT_ACTION"
    run_hidctl disconnect
}


# ---------------------------------------------------------------------------
# 唤醒守护（默认开启；关闭走 action.sh wake-guard-off）
#   —— docs/pen_wake_experiment_E0_E4_20260921.md §3
# 深睡笔取下后的软件唤醒，触发器 = 笔自身 BLE 链路完整 link-down -> link-up
# （E4c-fix 实测定案）。唤醒的副作用是笔端震动会话被重置，重连后必须广播
# haptic REFRESH 让 Hook 重放 inkdye 握手（§3b 定案），否则书写无震动。
#
# 触发条件（全部满足才动手）：
#   1) 开关文件存在（pen-wake-guard.state，customize.sh 安装时建立）；
#   2) 离座边沿武装（monitor_hall_capsule 在 dock 1->0 时写 pen-wake-arm.state）；
#   3) 离座持续（hall=0）；
#   4) 用户没在设置页显式点过"断开"。
#      （v0.1.20 曾要求"用户已解锁"，v0.1.22 删除：PenHidCtl DBA 化后锁屏期
#      HID 通路可用，闸门只会把每次息屏期间的守护白白挡住 —— 这台 ROM 息屏
#      就把 user 0 锁回 RUNNING_LOCKED，不是"仅开机首解前"。）
# 之后分两种形态：
#   a) 真实链路在线（lenovo_pen_link_connected=1）—— 深睡笔的常态："链路在、
#      触控死"。先听 WAKE_QUIET_SECONDS 秒笔尖节点，有事件=笔醒着，不动；
#      零事件（或节点判不了）→ 唤醒循环。
#   b) 链路已断（=0）—— 连 BLE 载波都掉了的形态。v0.1.18 在这里直接打一行
#      "nothing to cycle" 就返回，等于把故障丢回给"重新吸附"；现在改走同一套
#      循环（先断后连正是破僵尸 HOGP 的唯一有效路径，docs §1.5.2）。
# 动作：DISCONNECT_PENCIL -> 若原本有链路则轮询确认断链（前置坑：有链路时
#   CONNECT_PENCIL 会被 CoreService 静默跳过，"explicit pen connect already has
#   a real link"，E4c 假重连教训）-> 清掉本次断开自己置上的闩锁 -> 失连保持
#   WAKE_OFFLINE_HOLD_SECONDS 秒 -> CONNECT_PENCIL -> 轮询确认回链 -> 广播
#   haptic REFRESH 重放握手 -> **verify_pen_awake 验证笔是否真的活了**（只记
#   日志，2026-09-21 补：此前"循环跑完"被当成"修好了"，无法区分白跑）。
# 一次性武装：每次吸附会话只尝试一轮；冷却 WAKE_COOLDOWN_SECONDS。
#
# ⚠️ 已知局限（2026-09-21 生产样本暴露，待数据决定）：
#   · WAKE_QUIET_SECONDS=6 的静默窗口可能**短于健康的"离座 -> 首笔"延迟**。
#     样本里这个延迟是 11s，而窗口只有 6s ⇒ 每次离座都必然判成"睡死"，
#     守护退化成"无条件断链重连"。日志里的 `${n}s after undock` 就是为
#     标定这个值加的（正常分支与 cycle OK 分支都打）。
#   · 一轮不成就没有第二轮（一次性武装）。"验证 FAILED" 日志的**发生率**
#     决定要不要加重试 —— 没有证据表明第二轮有用，所以先不盲加。
# ---------------------------------------------------------------------------

# 笔的 evdev 输入节点。
#
# ⚠️ 2026-09-21 实测教训（唤醒守护形同虚设的真因）：上一版走
# `/proc/bus/input/devices` + awk 按空白分词找 `/^event[0-9]+$/`，在本机
# **永远匹配不到** —— 该文件的写法是 `H: Handlers=event5 cpufreq`，第一个
# handler 与 `Handlers=` 是**粘在一起**的，分词后字段是 `H:` /
# `Handlers=event5` / `cpufreq`，没有任何一个等于裸的 `eventN`。于是本函数
# 恒失败 → 每次离座只打一行 "pen input node unresolvable; skipping"，
# 唤醒循环**一次都没执行过**（20:21 起每次离座都是这个形态）。设备实测：
# 同一份 sysfs 里 `NVTCapacitivePen` 明明在 `/sys/class/input/input8/event5`。
# 修法：主路径回到 v0.1.14 的 **sysfs 精确名匹配**（那套当时实测有效），
# `/proc` 仅作兜底且改用 `match()` 在**整行**里找 eventN。调用方对"判不了"
# 一律按**失败保险向**处理，见 run_wake_guard_once。
resolve_pen_input_node() {
    if [ -n "$PEN_INPUT_NODE" ] && [ -e "$PEN_INPUT_NODE" ]; then
        return 0
    fi
    PEN_INPUT_NODE=
    # 主路径：sysfs 按精确设备名找。`inputN/eventM` 是目录，字符设备在
    # /dev/input/ 下，所以只能取 basename 再拼回 /dev/input/。
    for node_dir in /sys/class/input/input*; do
        [ -r "$node_dir/name" ] || continue
        [ "$(cat "$node_dir/name" 2>/dev/null)" = "$PEN_TIP_DEVICE_NAME" ] || continue
        for node_ev in "$node_dir"/event*; do
            [ -e "$node_ev" ] || continue
            case "${node_ev##*/}" in
                event[0-9]*)
                    PEN_INPUT_NODE="/dev/input/${node_ev##*/}"
                    return 0
                    ;;
            esac
        done
    done
    # 兜底：/proc/bus/input/devices 里第一条名字含 pen 的设备。必须用
    # match() 在整行里定位 eventN，不能按空白分词（理由见上方教训）。
    [ -r /proc/bus/input/devices ] || return 1
    node=$(awk '
        /^N: Name=/ { pen = tolower($0) ~ /pen/; next }
        pen && /^H: Handlers=/ {
            if (match($0, /event[0-9]+/)) {
                print "/dev/input/" substr($0, RSTART, RLENGTH); exit
            }
        }
    ' /proc/bus/input/devices 2>/dev/null | head -n 1)
    [ -n "$node" ] || return 1
    [ -e "$node" ] || return 1
    PEN_INPUT_NODE="$node"
    return 0
}

# 连续 $1 秒节点零事件则返回 0；期间任一秒读到事件返回 1；节点不可判定返回 2。
# evdev 是多客户端广播：每次探针独立 open/read/close，不会抢走真实输入管线的
# 事件。探针用外部 timeout+dd（各一进程/秒），仅存在于武装窗口，非常驻。
pen_input_silent() {
    resolve_pen_input_node || return 2
    left="$1"
    while [ "$left" -gt 0 ]; do
        if timeout 1 dd if="$PEN_INPUT_NODE" of=/dev/null bs=24 count=1 2>/dev/null; then
            return 1
        fi
        left=$((left - 1))
    done
    return 0
}

trigger_haptic_refresh() {
    # 接收方是 Hook 在 system_server 注册的动态 receiver
    # （RECEIVER_EXPORTED，root am broadcast 可达）；不加 -p ——
    # system_server 的注册不属于任何 app 包名，-p 会把广播拦在门外。
    am broadcast --user 0 --receiver-foreground \
        -a com.futureharmony.lenovopenbridge.haptic.REFRESH >/dev/null 2>&1
    am broadcast --user 0 --receiver-foreground \
        -a com.aclaniakea.lenovopenbridge.haptic.REFRESH >/dev/null 2>&1
}

do_pen_wake_cycle() {
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        echo "[$(date '+%F %T')] wake guard: no bonded pen MAC; abort"
        return 1
    fi
    # 用户在设置页显式点过"断开"就尊重用户意图，不抢连接。手动入口
    # （action.sh wake）也走这里，所以这道检查放在循环内部而不是最外层。
    if [ "$(settings get global "$PEN_USER_DISCONNECT_KEY" 2>/dev/null | tr -d '\r')" = 1 ]; then
        echo "[$(date '+%F %T')] wake guard: user disconnect choice is active; abort"
        return 1
    fi
    was=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    echo "[$(date '+%F %T')] wake guard: link cycle start (was_connected=${was:-unknown}; disconnect pen BLE link)"
    request_pen_disconnect
    i=0
    if [ "$was" = 1 ]; then
        # 前置坑：必须确认真断链，否则 CONNECT_PENCIL 静默跳过（E4c 假重连）。
        # 判据**不能只信镜像**（lenovo_pen_link_connected）：锁屏态实测镜像
        # 滞后可达 >6s（22:37 样本：HID 已断链、镜像 6s 内纹丝不动），只信
        # 它会把"断链慢半拍"误判成"没断开"。改为双判据：镜像 !=1 **或**
        # 蓝牙栈 HOGP 非 2（real_bt_connected，栈是唯一真源）。
        disconnected=0
        while [ "$i" -lt 10 ]; do
            sleep_sec 1
            connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
            if [ "$connected" != 1 ] || ! real_bt_connected; then
                disconnected=1
                break
            fi
            i=$((i + 1))
        done
        # 无论判成"断了"还是"没断"，自致闩锁都清掉（是我们断的，不是用户）。
        settings put global "$PEN_USER_DISCONNECT_KEY" 0 >/dev/null 2>&1
        settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
        if [ "$disconnected" != 1 ]; then
            # ⚠️ abort 绝不能把笔留在 FORBIDDEN：22:37 样本里"镜像滞后误判
            # 没断开 → abort"，实际链路已断且 policy=FORBIDDEN，笔被扔在
            # 死链状态。恢复 ALLOWED 让栈把链路带回来，再报失败。
            echo "[$(date '+%F %T')] wake guard: link never went down within 10s; re-allowing profile and aborting"
            run_hidctl connect
            return 1
        fi
        echo "[$(date '+%F %T')] wake guard: link down confirmed after ${i}s"
    else
        # 本来就没链路：给栈一秒把 DISCONNECT 结算掉即可，不必等"落下"。
        sleep_sec 1
        # 闩锁同样清掉（保持与确认分支一致）。
        settings put global "$PEN_USER_DISCONNECT_KEY" 0 >/dev/null 2>&1
        settings put global lenovo_pen_disconnect_requested 0 >/dev/null 2>&1
        echo "[$(date '+%F %T')] wake guard: link was already down"
    fi
    echo "[$(date '+%F %T')] wake guard: cleared self-inflicted disconnect latches; holding offline ${WAKE_OFFLINE_HOLD_SECONDS}s"
    sleep_sec "$WAKE_OFFLINE_HOLD_SECONDS"
    request_pen_connect
    # 回链窗口：解锁态 E4 实测 2–5s（原 8s 够用）；**锁屏态实测 ~23s**
    # （2026-09-21 22:32 生产样本：policy 切回 ALLOWED 后 23s 链路才回来，
    # 屏幕灭着时 BLE 重连明显更慢）。8s 窗口会把"慢但成功"误判成失败。
    # 判据用两个：Hook 镜像（lenovo_pen_link_connected，Hook 在 system_server
    # 锁屏也活着）+ 蓝牙栈本身（real_bt_connected 的 HOGP state=2）。
    i=0
    connected=
    while [ "$i" -lt "$WAKE_LINK_BACK_SECONDS" ]; do
        sleep_sec 1
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        [ "$connected" = 1 ] && break
        if real_bt_connected; then
            connected=1
            break
        fi
        i=$((i + 1))
    done
    if [ "$connected" = 1 ]; then
        echo "[$(date '+%F %T')] wake guard: link restored after ${i}s; replaying haptic handshake"
        sleep_sec 1
        trigger_haptic_refresh
        return 0
    fi
    echo "[$(date '+%F %T')] wake guard: link did not come back within 8s"
    return 1
}

# 离座 -> 首笔 的延迟（秒）。$1 = 离座时刻 epoch。取不到基准/时钟异常返回 1
# （调用方用 "?" 显示）。这是判断"WAKE_QUIET_SECONDS 窗口该不该这么短"的唯一
# 数据来源：窗口若恒小于真实醒来延迟，守护就会**每次离座都白跑一轮断链**。
pen_undock_age() {
    case "$1" in ''|*[!0-9]*) return 1 ;; esac
    t=$(date '+%s' 2>/dev/null)
    case "$t" in ''|*[!0-9]*) return 1 ;; esac
    [ "$t" -ge "$1" ] || return 1
    echo "$((t - $1))"
}

# 唤醒循环之后的**验证**：这一轮到底成没成？
#
# 背景（2026-09-21 21:36 生产样本）：循环"跑完"和"笔活了"是两件事。此前
# do_pen_wake_cycle 返回 0 只代表"链路回来了 + 握手重放了"，**不代表笔能写**。
# 于是"循环跑了但没用"会以完全正常的日志出现 —— 与 v0.1.18 的
# "unresolvable; skipping" 同属一类静默失败（本项目第五次踩）。
#
# ⚠️ 本函数**只记日志，不改行为**：不重试、不回滚、不延长武装。原因是没有
# 证据表明第二轮循环有用，而"验证窗口内静默"这个条件本身是歧义的 ——
# 用户把笔放一边不碰屏幕同样是静默。先让失败可见，拿到真实发生率再决定
# 要不要加重试（TODO.md 已记）。
verify_pen_awake() {
    undock_at="$1"
    pen_input_silent "$WAKE_VERIFY_SECONDS"
    rc=$?
    age=$(pen_undock_age "$undock_at")
    age=${age:-?}
    case "$rc" in
        1)
            echo "[$(date '+%F %T')] wake guard: cycle OK - pen emitted input ${age}s after undock"
            ;;
        2)
            echo "[$(date '+%F %T')] wake guard: WARN cycle UNVERIFIABLE - pen input node unresolvable after cycle (${age}s after undock)"
            ;;
        *)
            echo "[$(date '+%F %T')] wake guard: WARN cycle FAILED - pen still silent ${WAKE_VERIFY_SECONDS}s after cycle (${age}s after undock); re-dock may be required"
            ;;
    esac
}

run_wake_guard_once() {
    # 离座时刻先取再删（monitor_hall_capsule 写入的 epoch）。
    undock_at=$(cat "$WAKE_GUARD_ARM_FILE" 2>/dev/null)
    # 消费武装标记：每次吸附会话最多一轮。
    rm -f "$WAKE_GUARD_ARM_FILE"
    if [ "$(settings get global "$PEN_USER_DISCONNECT_KEY" 2>/dev/null | tr -d '\r')" = 1 ]; then
        echo "[$(date '+%F %T')] wake guard: undocked but user disconnect choice is active; skipping"
        return 0
    fi
    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$WAKE_GUARD_LAST_FILE" 2>/dev/null)
    case "$previous" in
        ''|*[!0-9]*) ;;
        *)
            # 冷却跳过也要留痕。原来这里是一句无声的 return 0 —— 与 v0.1.18 的
            # "unresolvable; skipping" 同属静默路径：日志上看不出"守护来过但
            # 被冷却挡住了"，只会以为守护没被触发。冷却本身是对的（防风暴），
            # 但"没做事"必须可解释。
            if [ "$now" -ge "$previous" ] && [ "$((now - previous))" -lt "$WAKE_COOLDOWN_SECONDS" ]; then
                echo "[$(date '+%F %T')] wake guard: in cooldown ($((now - previous))s < ${WAKE_COOLDOWN_SECONDS}s since last cycle); skipping this undock"
                return 0
            fi
            ;;
    esac
    connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
    if [ "$connected" != 1 ]; then
        # 形态 b：链路已经不在。原实现在这里打一行 "nothing to cycle" 就返回，
        # 而这个形态恰恰是用户报的"连震动都没了"——什么都不做等于把故障丢回
        # 给"必须重新吸附"。改走唤醒循环（先断后连是破僵尸 HOGP 的唯一路）。
        echo "[$(date '+%F %T')] wake guard: undocked with no live link; running connect cycle"
        echo "$now" >"$WAKE_GUARD_LAST_FILE"
        do_pen_wake_cycle
        verify_pen_awake "$undock_at"
        return 0
    fi
    echo "[$(date '+%F %T')] wake guard: undocked with live link; listening for pen input (${WAKE_QUIET_SECONDS}s window)"
    pen_input_silent "$WAKE_QUIET_SECONDS"
    silent=$?
    if [ "$silent" = 1 ]; then
        age=$(pen_undock_age "$undock_at")
        echo "[$(date '+%F %T')] wake guard: pen emitted input; awake, no wake needed (${age:-?}s after undock)"
        return 0
    fi
    if [ "$silent" = 2 ]; then
        # 判不了 ≠ 没事。v0.1.18 在这里静默跳过，正是整条守护空转的原因
        # （见 resolve_pen_input_node 的教训）。改为**失败保险向**：宁可多走
        # 一次 ~13 秒的唤醒循环，也不能让"判据坏了"表现为"笔坏了"。
        # 有一次性武装 + WAKE_COOLDOWN_SECONDS 冷却兜底，不会变成风暴。
        echo "[$(date '+%F %T')] wake guard: WARN pen input node unresolvable; treating as asleep and waking anyway"
    fi
    echo "$now" >"$WAKE_GUARD_LAST_FILE"
    do_pen_wake_cycle
    verify_pen_awake "$undock_at"
}

monitor_wake_guard() {
    echo "[$(date '+%F %T')] wake guard monitor started (now=$WAKE_GUARD_NOW_FILE arm=$WAKE_GUARD_ARM_FILE enabled=$WAKE_GUARD_ENABLED_FILE)"
    while [ ! -e "$CPS_DISABLED" ]; do
        if [ -e "$WAKE_GUARD_NOW_FILE" ]; then
            # 手动入口（action.sh wake）：显式意图，不看开关文件、也不看锁屏
            # （用户就在跟前操作，解锁与否由他自己负责）。
            rm -f "$WAKE_GUARD_NOW_FILE"
            echo "[$(date '+%F %T')] wake guard: manual wake requested via action.sh"
            do_pen_wake_cycle
        elif [ -f "$WAKE_GUARD_ARM_FILE" ] \
                && [ -f "$WAKE_GUARD_ENABLED_FILE" ] \
                && [ "$(read_hall_state)" = 0 ]; then
            # ⚠️ 这里**不做**锁屏闸门（v0.1.20 曾加过，v0.1.22 删除）。历史与依据：
            #   • v0.1.20 加闸门的理由：原厂 CoreService 不是 direct-boot-aware，
            #     未解锁时 OEM 投递必 rc=255，而武装是一次性的，会被白白烧掉。
            #   • 当时的假设「FBE 解过一次后恒为 RUNNING_UNLOCKED」被当晚实测
            #     **推翻**：这台 ROM 息屏就把 user 0 锁回 RUNNING_LOCKED，闸门
            #     实际把每次息屏期间的唤醒守护全部挡死 —— 又一次静默空转。
            #   • v0.1.22 起 PenHidCtl 自身 DBA 化（PenHidService 只碰蓝牙栈，
            #     不读 CE 数据），锁屏期 run_hidctl 是**可用**的断/连通路；
            #     OEM 半边失败由 request_oem_pen_action 照常记日志，不影响。
            #   • 已知代价：锁屏期唤醒后 haptic REFRESH 的下游（inkdye 会话在
            #     非 DBA 的 OEM 进程里）可能只能等解锁后自然恢复 —— 退化形态
            #     是"笔画先回来、震动后回来"，仍远好于"整段等解锁"。
            run_wake_guard_once
        fi
        sleep_sec 2
    done
}


# The stock settings action updates the IPe state, but on this port HID Host
# remains connected. Enforce an explicit settings-page Disconnect at both the
# vendor CoreService and the actual HID profile. A 1 -> 0 transition is the
# only runtime path that requests a connect; an idle 0 with no live link is
# left alone because the bounded boot loop is the only automatic recovery.
monitor_hid_latch() {
    last=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
    case "$last" in
        1|0) ;;
        *) last=-1 ;;
    esac
    repeat=0
    while [ ! -e "$CPS_DISABLED" ]; do
        requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
        case "$requested" in
            1)
                if [ "$last" != 1 ]; then
                    request_pen_disconnect
                    repeat=0
                else
                    repeat=0
                fi
                ;;
            0)
                if [ "$last" = 1 ]; then
                    user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
                    if [ "$user_requested" = 1 ]; then
                        echo "[$(date '+%F %T')] explicit pen connect skipped: user disconnect choice is active"
                    elif real_bt_connected; then
                        echo "[$(date '+%F %T')] explicit pen connect already has a real link"
                    else
                        request_pen_connect_bounded
                    fi
                    repeat=0
                else
                    repeat=0
                fi

                ;;
            *)
                repeat=0
                ;;
        esac
        last="$requested"
        sleep_sec 5
    done
}

# Boot-time monitors start here: after every helper they call (run_hidctl,
# request_oem_pen_action, request_pen_connect, request_pen_disconnect) has
# already been parsed above, so the forked subshells inherit the full symbol
# table and the first magnetic edge cannot hit "request_pen_connect: not
# found". They still start before the boot-connect retry window, preserving
# the original boot-time state publication (connection mirrors + haptics).
monitor_hall_capsule &
monitor_battery_cache &
monitor_charging_cache &
monitor_real_bt_state &

monitor_hid_latch &
monitor_wake_guard &

# The ported IPeManager package carries the vendor Bluetooth receivers in
# its resolver table, but their user-0 component state is disabled.  Enable
# only those two stock receivers so the real OAF/ACL events can start
# CoreService under Oppo's own UID; no synthetic event or connection state is
# written here.
for receiver in \
    com.oplus.ipemanager/.btadsorb.ble.BluetoothStatusReceiver \
    com.oplus.ipemanager/.btadsorb.receiver.BluetoothBroadcastReceiver; do
    if pm enable --user 0 "$receiver" >/dev/null 2>&1; then
        echo "[$(date '+%F %T')] enabled Oppo pen receiver=$receiver"
    else
        echo "[$(date '+%F %T')] unable to enable Oppo pen receiver=$receiver"
    fi
done

touch "$PEN_BOOT_READY_FILE"

# Re-emit the driver's current INFO/MAC/TOUCH_INFORMATION snapshot after the
# LSPosed system_server observer has started.
if [ -w "$UEVENT" ]; then
    echo change >"$UEVENT"
    echo "[$(date '+%F %T')] PEN_FRAMEWORK uevent requested"
fi
if [ -w "$CPS_UEVENT" ]; then
    echo change >"$CPS_UEVENT"
    echo "[$(date '+%F %T')] CPS uevent requested"
fi

# Keep the real vendor request alive across Bluetooth's late service startup
# window.  Stop as soon as the ACL/GATT callback has reported a live link; do
# not send the old RECONNECT_PEN broadcast, which clears the settings latch and
# invokes a synthetic system_server replay before the real link exists.
# The Lenovo pen powers itself off when it is not magnetically docked, so
# actively searching for it over Bluetooth is pointless (and the early pokes
# used to restart the adapter). Only connect after the CPS power-on, which can
# only wake a docked pen; the dock-attach edge reconnects it later.
# Do not poke the Bluetooth stack while it is still coming up.
bt_wait=0
while [ "$bt_wait" -lt 60 ]; do
    [ "$(settings get global bluetooth_on 2>/dev/null | tr -d '\r')" = 1 ] && break
    sleep_sec 2
    bt_wait=$((bt_wait + 2))
done
requested=$(settings get global lenovo_pen_disconnect_requested 2>/dev/null | tr -d '\r')
user_requested=$(settings get global lenovo_pen_user_disconnect_requested 2>/dev/null | tr -d '\r')
connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
if [ "$connected" = 1 ]; then
    echo "[$(date '+%F %T')] boot pen connect confirmed by real ACL/GATT state"
elif [ "$requested" = 1 ] || [ "$user_requested" = 1 ]; then
    echo "[$(date '+%F %T')] boot pen connect skipped: Settings disconnect latch is set"
elif [ "$(read_hall_state)" = 1 ]; then
    # Docked: the CPS boot power sequence already ran; connect once and give
    # the link a bounded window to come up.
    request_pen_connect
    echo "[$(date '+%F %T')] real OEM boot connect requested (docked/CPS powered)"
    attempt=2
    while [ "$attempt" -le 4 ] && [ ! -e "$CPS_DISABLED" ]; do
        sleep_sec 12
        connected=$(settings get global lenovo_pen_link_connected 2>/dev/null | tr -d '\r')
        if [ "$connected" = 1 ]; then
            echo "[$(date '+%F %T')] boot pen connect confirmed by real ACL/GATT state"
            break
        fi
        request_pen_connect
        echo "[$(date '+%F %T')] real OEM boot connect retry attempt=$attempt"
        attempt=$((attempt + 1))
    done
else
    echo "[$(date '+%F %T')] pen not docked; boot connect skipped (CPS can only wake a docked pen)"
fi

# Keep all monitor children alive after the boot retry window. Exiting the
# shell here can orphan/kill the CPS, Hall and HID reconciliation loops on
# some KernelSU/Magisk launchers, which leaves only the already-open BLE link.
while [ ! -e "$CPS_DISABLED" ]; do
    sleep_sec 30
done
