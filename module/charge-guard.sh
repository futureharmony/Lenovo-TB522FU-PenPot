#!/system/bin/sh
# ============================================================================
# TB522FU 手写笔充电守护 (charge guard) v3 —— 纯观察者
# ----------------------------------------------------------------------------
# v2 → v3 的关键变更（2026-09-18，反向分析驱动 + 受控实验后）
#
# v2 会主动写 tx_status=0 强制断电。但把 CPS8601 驱动本体
# （/vendor_dlkm/lib/modules/cps_wls_charger.ko）拉下来分析后确认：
#
#   * 该驱动是【原厂 Lenovo TB522FU 构建】（整个 /vendor_dlkm 308 个模块同源，
#     含 dhall_och1909 / lenovo_sys_temp / lenovo_thermal_control 等 Lenovo 专属
#     驱动），ColorOS 只提供 system/product/odm，没有介入 CPS。
#   * 充满截止是驱动自己的事：它自己读 hall_status，通过带内 ASK/FSK 从笔拿
#     charging_soc / charging_status / charging_cmd，笔要求停就 close tx；
#     另有 cps_handle_rechg_work 做补充充电。设备树与模块 parameters 里
#     没有任何策略配置 —— 策略硬编码在驱动内。
#
# 受控实验（停用本守护、笔重新吸附后保持不动，2 秒一采样）：
#   15:10:41 吸附确认 → 驱动开 TX（此时电量已 100%）
#   15:21:03 驱动【自行】关 TX（笔仍吸附 hall3=0）—— 全程守护未动作
#   → 驱动会自关，耗时约 10 分钟（涓流 + 确认周期）。
#
# 而 v2 的强制写会在吸附后 3~6 秒就抢先关掉，打断驱动的正常周期；更要命的是
# v2 用【BLE 侧电量】当判据、驱动用【带内 charging_soc】，两者不一致时 v2 会
# 挡住合法补电（表现为"笔吸上去不充电"）。
#
# 所以 v3 定位为【纯观察者】：**不写 tx_status**，只做
#   1) 观察驱动动作 → 发「已充满 / 已恢复充电」通知；
#   2) 回写 ipe_pencil_charging_state（ColorOS 的 IPeManager 恒 0，是移植缺口）。
#
# ⚠️ 已披露的边界情况：若将来换成不支持该带内协议的笔，驱动拿不到 charging_cmd
#    就可能长期不关 TX。届时应加长超时兜底（而不是恢复到 v2 的秒级强制关）。
#    分析详见 docs/cps-charger-driver-analysis.md。
#
# 硬件接口（实测）：
#   <i2c>-0041/tx_status   内容 cps_boost_mode:%d, cps_wls_en:%d —— 唯一可信的
#                          "正在送电"判据是 cps_wls_en；写入只认裸数字 0/1
#   /sys/devices/virtual/hall/och1909/hall3  内容 "hall13 value = 0"（驱动格式串
#                          bug，前缀是 hall13 不是 hall3；解析只取行尾数字）
# 注意：CPS 节点按 I2C 地址 0x41 运行时解析，兼容 pineapple(2-0041)/sun(11-0041)。
# ============================================================================

MODDIR=${0%/*}
LOG="$MODDIR/charge-guard.log"
PIDFILE="$MODDIR/charge-guard.pid"

HALL3=/sys/devices/virtual/hall/och1909/hall3
POLL_SEC=${POLL_SEC:-15}
FULL_TH=${FULL_TH:-100}
RESUME_TH=${RESUME_TH:-95}
GUARD_DISABLE="$MODDIR/disable-charge-guard"

# CPS8601 的 I2C 地址恒为 0x41，按总线符号链接解析
TX_NODE=
for d in /sys/bus/i2c/devices/*-0041; do
    [ -r "$d/tx_status" ] && { TX_NODE="$d/tx_status"; break; }
done

# 日志上限：本脚本只写 charge-guard.log 一个文件，直接覆盖全局默认值
# （吸附/充电事件是稀疏事件，200 行足够覆盖好几天；实现见 bin/penlog.sh）。
PENLOG_MAX_LINES=200
if [ -f "$MODDIR/bin/penlog.sh" ]; then
    . "$MODDIR/bin/penlog.sh"
fi
if type penlog_append >/dev/null 2>&1; then
    log() { penlog_append "$LOG" "$*"; }
else
    log() { echo "[$(date '+%F %T')] $*" >>"$LOG"; }
fi

tx_get() {
    [ -n "$TX_NODE" ] || { echo -1; return; }
    v=$(cat "$TX_NODE" 2>/dev/null)
    case "$v" in
        *cps_wls_en:1*) echo 1 ;;
        *cps_wls_en:0*) echo 0 ;;
        *) echo -1 ;;
    esac
}

hall_docked() {
    h1=$(cat "$HALL3" 2>/dev/null)
    h1=${h1##* }
    if [ "$h1" = "0" ]; then
        echo 1
    else
        echo 0
    fi
}

pen_battery() {
    b=$(settings get global ipe_pencil_battery_level 2>/dev/null)
    case "$b" in ''|*[!0-9]*) echo -1 ;; *) echo "$b" ;; esac
}

notify() {
    # 实测（2026-09-18）：cmd notification post rc=0 且系统接收，
    # 但 ColorOS 会静默丢弃 shell(uid 0) 来源的通知（dumpsys 无记录、不显示）。
    # 因此 UI 展示由 Hook APK（有身份的应用）承担：
    #   守护 → SHOW_PENCIL_CAPSULE 广播 → Hook 收到后弹胶囊/发通知。
    # 保留 cmd notification：在原生 AOSP 上可用，ColorOS 上无害。
    out=$(cmd notification post -S bigtext -t "手写笔" "pen_charge_guard" "$1" 2>&1)
    rc=$?
    log "notify rc=$rc msg=$1"
}

sync_ipe_state() {
    cur=$(settings get global ipe_pencil_charging_state 2>/dev/null)
    [ "$cur" = "$1" ] && return 0
    settings put global ipe_pencil_charging_state "$1" >/dev/null 2>&1
    settings put global lenovo_pen_charging_state "$1" >/dev/null 2>&1
    log "ipe charging_state $cur -> $1"
}

main_loop() {
    prev_docked=dummy
    prev_tx=dummy
    notified_full=0

    while true; do
        [ -f "$GUARD_DISABLE" ] && { sleep "$POLL_SEC"; continue; }

        docked=$(hall_docked)
        batt=$(pen_battery)
        tx=$(tx_get)

        if [ "$docked" != "1" ]; then
            if [ "$prev_docked" = "1" ]; then
                log "pen detached (battery=$batt)"
                # 离开即会话结束，为下一次吸附重置通知状态
                notified_full=0
            fi
            prev_docked=0
            prev_tx=$tx
            sleep "$POLL_SEC"
            continue
        fi

        [ "$prev_docked" != "1" ] && log "pen docked (battery=$batt tx=$tx)"
        prev_docked=1

        # 充电状态镜像：以驱动真值(cps_wls_en)为准，只在变化时写
        case "$tx" in
            0) sync_ipe_state 0 ;;
            1) sync_ipe_state 1 ;;
        esac

        # 观察驱动的"充满断电"：TX 由 1 变 0 且电量已满 —— 只通知，不干涉
        if [ "$prev_tx" = "1" ] && [ "$tx" = "0" ] && [ "$notified_full" = "0" ]; then
            if [ "$batt" -ge "$FULL_TH" ] 2>/dev/null; then
                notified_full=1
                log "driver closed TX at full (battery=$batt); observing - not overriding"
                notify "手写笔已充满，已停止充电"
            fi
        fi

        # 电量回落到阈值以下 → 视为恢复充电
        if [ "$notified_full" = "1" ] && [ "$batt" -ge 0 ] && [ "$batt" -le "$RESUME_TH" ]; then
            notified_full=0
            notify "手写笔电量 $batt%，已恢复充电"
        fi

        prev_tx=$tx
        sleep "$POLL_SEC"
    done
}

# ---------------------------------------------------------------------------
# 单实例保护（2026-09-18 修正）
#
# 旧实现只做 `kill -0 "$old" && exit 0`。致命缺陷有两点：
#   a) pidfile 落在 /data（$MODDIR），会跨重启保留；
#   b) PID 号由内核复用，重启后会被重新分配。
# 实测踩坑：某次开机 pidfile 里是上一轮的 PID 7313，而本次开机 service.sh
# 自己的监控子进程恰好占用了 7313 → `kill -0` 成功 → 本脚本判定"已在运行"
# 直接 exit 0，**既不更新 pidfile 也不写任何日志**，守护整轮静默缺席。
#
# 现在改成两道校验，只有"确实是本脚本的存活实例"才退出：
#   1) 旧记录必须来自【本次开机】（比对 /proc/sys/kernel/random/boot_id）；
#   2) 该 PID 的 /proc/<pid>/cmdline 必须真的指向 charge-guard.sh。
# 任一不满足即视为陈旧记录，清理后正常启动。pidfile 现在存 "pid bootid"。
# ---------------------------------------------------------------------------
cur_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)

if [ -r "$PIDFILE" ]; then
    read -r old old_boot <"$PIDFILE" 2>/dev/null
    case "$old" in ''|*[!0-9]*) old= ;; esac    # 非数字内容=陈旧，直接丢弃
    if [ -n "$old" ]; then
        if [ -n "$cur_boot" ] && [ -n "$old_boot" ] && [ "$old_boot" != "$cur_boot" ]; then
            log "pidfile pid=$old belongs to a previous boot; starting fresh"
        elif kill -0 "$old" 2>/dev/null; then
            old_cmd=$(tr '\0' ' ' <"/proc/$old/cmdline" 2>/dev/null)
            case "$old_cmd" in
                *charge-guard.sh*)
                    log "already running as pid=$old; not starting a second instance"
                    exit 0 ;;
                *)
                    log "pidfile pid=$old is a recycled non-guard process ($old_cmd); starting fresh" ;;
            esac
        else
            log "pidfile pid=$old is no longer alive; starting fresh"
        fi
    fi
fi
echo "$$ ${cur_boot:-}" >"$PIDFILE"

log "charge-guard v3 (observer) started [rev 2026-09-18b]: tx_node=${TX_NODE:-NONE} full>=$FULL_TH resume<=$RESUME_TH poll=${POLL_SEC}s - never writes tx_status"
main_loop
