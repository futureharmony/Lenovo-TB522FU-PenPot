#!/system/bin/sh
# ============================================================================
# TB522FU 手写笔充电守护 (charge guard)  v2
# ----------------------------------------------------------------------------
# 设计依据（2026-09-18 实测）：
#   * TX 写入是瞬时的：echo 1 后约 30s 被 cps-wls-charger 驱动按自身策略改回。
#     笔充满时驱动自发 cps_wls_en:0（dmesg: Notcharging）→ 充满断电由驱动负责。
#   * 因此守护定位为「观察 + 通知 + 状态修正」，只在驱动"该关没关"时兜底写 0。
#   * 实测 ipe_pencil_charging_state 恒 0（内核在充、IPeManager 不知道）→ 由本守护回写。
#
# 功能：
#   1) 磁吸状态：och1909 hall3，0=吸附；
#   2) 充满通知：吸附且电量>=FULL_TH 且 TX 关闭 → 通知一次"已充满，已停止充电"；
#   3) 兜底断电：同一状态下若 TX 仍为 1（驱动异常），写 0 强制断；
#   4) 恢复充电：吸附且已满分且电量<=RESUME_TH → 通知"恢复充电"；
#   5) 修正 ipe_pencil_charging_state：按真实 吸附+TX 状态回写。
#
# 硬件接口（实测）：
#   /sys/bus/i2c/devices/11-0041/tx_status   写裸数字 0/1（带字样会被判 0！）
#   /sys/devices/virtual/hall/och1909/hall3  形如 "hall3 value = 0"
# 注意：adb/shell 直接内联引号会丢失，一律以脚本文件执行本脚本。
# ============================================================================

MODDIR=${0%/*}
LOG="$MODDIR/charge-guard.log"
PIDFILE="$MODDIR/charge-guard.pid"

TX_NODE=/sys/bus/i2c/devices/11-0041/tx_status
HALL3=/sys/devices/virtual/hall/och1909/hall3
POLL_SEC=${POLL_SEC:-30}
FULL_TH=${FULL_TH:-100}
RESUME_TH=${RESUME_TH:-95}
GUARD_DISABLE="$MODDIR/disable-charge-guard"

log() { echo "[$(date '+%F %T')] $*" >>"$LOG"; }

tx_get() {
    v=$(cat "$TX_NODE" 2>/dev/null)
    case "$v" in
        *cps_wls_en:1*) echo 1 ;;
        *cps_wls_en:0*) echo 0 ;;
        *) echo -1 ;;
    esac
}

tx_set() {
    case "$1" in 0|1) : ;; *) return 1 ;; esac
    [ -w "$TX_NODE" ] || return 1
    echo "$1" >"$TX_NODE" 2>/dev/null
}

hall_docked() {
    h=$(cat "$HALL3" 2>/dev/null)
    h=${h##* }
    [ "$h" = "0" ] && echo 1 || echo 0
}

pen_battery() {
    b=$(settings get global ipe_pencil_battery_level 2>/dev/null)
    case "$b" in ''|*[!0-9]*) echo -1 ;; *) echo "$b" ;; esac
}

notify() {
    # 实测（2026-09-18）：cmd notification post rc=0 且系统接收，
    # 但 ColorOS 会静默丢弃 shell(uid 0) 来源的通知（dumpsys 无记录、不显示）。
    # 因此 UI 展示由 P1 的 Hook APK（有身份的应用）承担：
    #   守护 → SHOW_PENCIL_CAPSULE 广播 → Hook 收到后弹胶囊/发通知。
    # 保留 cmd notification：在原生 AOSP 上可用，ColorOS 上无害。
    out=$(cmd notification post -S bigtext -t "手写笔" "pen_charge_guard" "$1" 2>&1)
    rc=$?
    log "notify rc=$rc msg=$1 (ColorOS drops shell notifs; UI via hook broadcast)"
    am broadcast -a com.aclaniakea.lenovopenbridge.action.SHOW_PENCIL_CAPSULE \
        --es text "$1" >/dev/null 2>&1
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
    full_notified=0

    while true; do
        [ -f "$GUARD_DISABLE" ] && { sleep "$POLL_SEC"; continue; }

        docked=$(hall_docked)
        batt=$(pen_battery)
        tx=$(tx_get)

        if [ "$docked" != "1" ]; then
            [ "$prev_docked" = "1" ] && log "pen detached (battery=$batt)"
            [ "$prev_docked" != "0" ] && full_notified=0
            prev_docked=0
            sleep "$POLL_SEC"
            continue
        fi

        [ "$prev_docked" != "1" ] && log "pen docked (battery=$batt tx=$tx)"
        prev_docked=1

        if [ "$batt" -ge "$FULL_TH" ] 2>/dev/null; then
            if [ "$tx" = "1" ]; then
                # 驱动该关没关 → 兜底
                if tx_set 0; then
                    log "driver left TX on at full ($batt); forced off"
                else
                    log "WARN: TX off failed at full ($batt)"
                fi
            fi
            if [ "$full_notified" = "0" ]; then
                full_notified=1
                sync_ipe_state 0
                notify "手写笔已充满，已停止充电"
            fi
        else
            if [ "$full_notified" = "1" ] && [ "$batt" -ge 0 ] && [ "$batt" -le "$RESUME_TH" ]; then
                full_notified=0
                notify "手写笔电量 $batt%，已恢复充电"
            fi
            if [ "$tx" = "1" ] && [ "$batt" -ge 0 ]; then
                sync_ipe_state 1
            elif [ "$tx" = "0" ]; then
                sync_ipe_state 0
            fi
        fi

        sleep "$POLL_SEC"
    done
}

if [ -r "$PIDFILE" ]; then
    old=$(cat "$PIDFILE" 2>/dev/null)
    case "$old" in
        ''|*[!0-9]*) ;;
        *) kill -0 "$old" 2>/dev/null && exit 0 ;;
    esac
fi
echo $$ >"$PIDFILE"

log "charge-guard v2 started (full>=$FULL_TH resume<=$RESUME_TH poll=${POLL_SEC}s)"
main_loop
