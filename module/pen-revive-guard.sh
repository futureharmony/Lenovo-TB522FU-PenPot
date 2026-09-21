#!/system/bin/sh
# ============================================================================
# TB522FU 手写笔「链路状态守护」(pen link guard) —— pen-revive-guard.sh v2
# ----------------------------------------------------------------------------
# 文件名保留 "revive" 是为了不动 build_root.py 的 INCLUDE 白名单与
# service.sh 的挂载点；脚本职责已在 v2 重新定位（见下）。
#
# 【v1 的结论已被推翻】v1 做的是「检测失联 -> 写 tx_status=1 送电唤醒」，
# 五组独立实验全部证伪，详见 docs/pen-sleep-death-analysis.md：
#   B  写 tx_status=1        TX 开满 30s，online 恒 0
#   C  广播 CONNECT_PENCIL   CoreService 响应并上报 charging=1，online 恒 0
#   D  TX 常开 120s          驱动 30s 后按空载超时自己关，online 恒 0
#   E  关 TX 后 50s 重开      连开 23s，online 恒 0
#   F  10Hz 载波常驻顶回      中断不到 1 秒，笔照样失联
# 机制：Hall 边沿走 dhall_och1909 -> cps_wls_charger 的【内核内函数调用】，
# 对笔而言等价于 Qi Digital Ping；而写 tx_status 只置使能位（Analog Ping）。
# 笔只认磁场边沿，软件不可替代。=> v2 不再写 tx_status，一个字节都不写。
#
# 完整因果链（2026-09-21 定位，内核 ASK 包实证）：
#   1) 吸附 -> Hall 边沿 -> 驱动开 TX -> 笔上电 -> 完成带内握手 -> BLE/ HOGP
#   2) 笔报 SOC=100 后，通过 ASK 包下发 charging_cmd「停充」：
#        dmesg: ask pkt data[2]:1, charging_cmd:1, close tx
#   3) 驱动照办关 TX -> 笔失去载波 -> 固件判定「已离座」-> 自行关机
#   4) 笔是突然掉电，不走正常 BLE disconnect -> 蓝牙栈 HOGP profile state
#      【不清零】-> real_bt_connected() 仍为真 = 僵尸连接
#   5) 僵尸连接吃掉 service.sh:1240 的 `! real_bt_connected` 判据
#      => 吸附边沿不再发起 request_pen_connect
#      => 用户看到「吸上去是死的，必须再吸一次」
#   这不是本模块引入的：docs/p0-recon-20260918.md（移植第一天）就记到了
#   「充满 -> online 归零 -> 静止 23 分钟」。charge-guard v2 的 tx_set 0 只
#   存在 4 小时（059e018 -> d7e8609），与现象无关。
#
# v2 做两件确定有效的事：
#   1) 检测「笔已自行关机但系统仍报已连接」的僵尸连接，置位 settings
#      lenovo_pen_powered_down=1，让上层状态镜像恢复诚实；
#   2) 该标志被 service.sh::real_bt_connected() 消费 —— 僵尸连接不再让判据
#      恒真，于是吸附边沿的 request_pen_connect 能正常执行（修掉第 5 步）。
#
# 判据：Hall 说笔在吸附位，但 CPS 侧 TX 已关闭超过 POWERED_DOWN_AFTER_SEC。
#   「TX 停送电 -> 笔掉电」是本故障唯一的确定性因果，不依赖任何活性信号。
#   不用 online 做判据：实测 TX 关闭时 online 与它同秒归零（没有额外区分度）；
#   不用 HOGP 做判据：僵尸连接恰恰就是它不翻转造成的。
#   不用 lenovo_pen_hardware_battery_last_at：它由本模块的 monitor_battery_cache
#   在 connected=1 时周期刷新，僵尸连接期间会被我们自己喂成常青，不具备活性含义。
#
# 开关文件：
#   $MODDIR/pen-revive.dryrun   存在=dry-run（只记录，不改任何 settings）
#                               【不存在=生效（默认）】
#   $MODDIR/disable-pen-revive  存在=整个守护停摆
# ============================================================================

MODDIR=${0%/*}
LOG="$MODDIR/pen-revive.log"
PIDFILE="$MODDIR/pen-revive.pid"

HALL3=/sys/devices/virtual/hall/och1909/hall3
PS_ONLINE=/sys/class/power_supply/cps_wls_tx/online
PS_CAPACITY=/sys/class/power_supply/cps_wls_tx/capacity
# 只读探测，v2 从不写它。保留节点解析是为了 tx_get() 的观测能力。
TX_NODE=
for d in /sys/bus/i2c/devices/*-0041; do
    [ -r "$d/tx_status" ] && { TX_NODE="$d/tx_status"; break; }
done

POLL_SEC=${POLL_SEC:-15}
# TX 关闭多久后判定笔已自行关机。驱动实测在吸附后 15~60 秒关 TX，笔随即掉电。
# 默认 90s 留约一倍余量：宁可晚一点把状态改诚实，也不要误报「笔已断开」——
# 本脚本的动作是改上层镜像，误判会让 UI 显示未连接而笔其实可用。
POWERED_DOWN_AFTER_SEC=${POWERED_DOWN_AFTER_SEC:-90}
DRYRUN_FLAG="$MODDIR/pen-revive.dryrun"
DISABLE_FLAG="$MODDIR/disable-pen-revive"

PENLOG_MAX_LINES=300
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

# hall3: attached(磁吸) 读 0，detached 读 1（2026-09-18 标定）。
hall_docked() {
    h=$(cat "$HALL3" 2>/dev/null)
    h=${h##* }
    [ "$h" = "0" ] && echo 1 || echo 0
}

ps_online() {
    v=$(cat "$PS_ONLINE" 2>/dev/null | tr -d '\r')
    case "$v" in 0|1) echo "$v" ;; *) echo -1 ;; esac
}

ps_capacity() {
    v=$(cat "$PS_CAPACITY" 2>/dev/null | tr -d '\r')
    case "$v" in ''|*[!0-9]*) echo -1 ;; *) echo "$v" ;; esac
}

setting_get() {
    settings get global "$1" 2>/dev/null | tr -d '\r'
}

setting_put() {
    settings put global "$1" "$2" >/dev/null 2>&1
}

# 笔已掉电标志：由本脚本维护，由 service.sh::real_bt_connected() 消费。
# 只在真正翻转时写，避免每轮 15 秒无谓唤醒 SettingsProvider。
set_powered_down() {
    want="$1"
    cur=$(setting_get lenovo_pen_powered_down)
    [ "$cur" = "$want" ] && return 0
    if [ -f "$DRYRUN_FLAG" ]; then
        log "[DRYRUN] would set lenovo_pen_powered_down=$want (was ${cur:-unset})"
        return 0
    fi
    setting_put lenovo_pen_powered_down "$want"
    log "lenovo_pen_powered_down=$want (was ${cur:-unset})"
}

# --- 单实例保护：与 charge-guard 同款（pidfile + boot_id + cmdline 三验）----
cur_boot=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null)
if [ -r "$PIDFILE" ]; then
    read -r old old_boot <"$PIDFILE" 2>/dev/null
    case "$old" in ''|*[!0-9]*) old= ;; esac
    if [ -n "$old" ]; then
        if [ -n "$cur_boot" ] && [ -n "$old_boot" ] && [ "$old_boot" != "$cur_boot" ]; then
            log "pidfile pid=$old from previous boot; starting fresh"
        elif kill -0 "$old" 2>/dev/null; then
            old_cmd=$(tr '\0' ' ' <"/proc/$old/cmdline" 2>/dev/null)
            case "$old_cmd" in
                *pen-revive-guard.sh*)
                    log "already running as pid=$old; not starting a second instance"
                    exit 0 ;;
                *)
                    log "pidfile pid=$old is a recycled process ($old_cmd); starting fresh" ;;
            esac
        else
            log "pidfile pid=$old not alive; starting fresh"
        fi
    fi
fi
echo "$$ ${cur_boot:-}" >"$PIDFILE"

# NOTE: the dry-run flag is NOT auto-created here on purpose. Auto-creating it
# would resurrect dry-run on every restart after the operator cleared it, i.e.
# "rm pen-revive.dryrun" would silently stop meaning anything.

log "pen-link-guard v2 started: poll=${POLL_SEC}s powered_down_after=${POWERED_DOWN_AFTER_SEC}s (never writes tx_status)"

tx_off_since=0
prev_docked=-1
# 开机先清一次标志：新会话的真实性由本轮采样重新判定，不继承上次开机的结论。
set_powered_down 0

while true; do
    if [ -f "$DISABLE_FLAG" ]; then
        sleep "$POLL_SEC"
        continue
    fi

    now=$(date '+%s' 2>/dev/null)
    case "$now" in ''|*[!0-9]*) now=0 ;; esac

    docked=$(hall_docked)
    tx=$(tx_get)
    online=$(ps_online)
    cap=$(ps_capacity)

    if [ "$docked" != 1 ]; then
        # 笔不在吸附位：会话结束。这里【故意不改 powered_down 标志】。
        # 标志的唯一解除条件见下方 tx/online 分支（笔重新上电）。
        # 若在此清标志，用户「拿起笔 -> 放回」时 attach 边沿会看到标志=0，
        # 于是 real_bt_connected() 重新被僵尸连接占住，重连请求又被吃掉 ——
        # 那正是我们要修的那个 bug。笔离座后按固件行为本就自行关机
        # （service.sh:1667 "The Lenovo pen powers itself off when it is not
        # magnetically docked"），保持标志=1 是诚实的。
        if [ "$prev_docked" = 1 ]; then
            log "pen detached; session ended (tx=$tx online=$online cap=$cap), powered_down flag held"
        fi
        [ "$prev_docked" != 0 ] && log "idle: not docked tx=$tx online=$online cap=$cap"
        prev_docked=0
        tx_off_since=0
        sleep "$POLL_SEC"
        continue
    fi

    if [ "$prev_docked" != 1 ]; then
        log "pen docked: tx=$tx online=$online cap=$cap"
        prev_docked=1
    fi

    # 送电中，或驱动仍能看到笔的带内响应 = 笔活着，标志必须清零。
    # 这是 powered_down 唯一的解除条件，也保证了吸附瞬间必定先清后判。
    if [ "$tx" = 1 ] || [ "$online" = 1 ]; then
        tx_off_since=0
        set_powered_down 0
        sleep "$POLL_SEC"
        continue
    fi

    # tx=0 且 online=0：驱动已停送电。可能是「正常充满静置」，也可能是笔
    # 已经掉电 —— 两者在物理上是同一个过程，只能用时间区分。
    [ "$tx_off_since" = 0 ] && tx_off_since=$now
    off_elapsed=$((now - tx_off_since))

    if [ "$off_elapsed" -lt "$POWERED_DOWN_AFTER_SEC" ]; then
        log "tx off ${off_elapsed}s/${POWERED_DOWN_AFTER_SEC}s; waiting (online=$online cap=$cap)"
        sleep "$POLL_SEC"
        continue
    fi

    log "pen powered down (tx off ${off_elapsed}s >= ${POWERED_DOWN_AFTER_SEC}s, online=$online cap=$cap); publishing honest link state"
    set_powered_down 1
    sleep "$POLL_SEC"
done
