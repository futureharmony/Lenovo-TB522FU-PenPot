#!/system/bin/sh
# ============================================================================
# lib/pen_link.sh — 链路动作与真值判据（OEM action / 连接 / 断开）
# ----------------------------------------------------------------------------
# 职责：把「连接笔」「断开笔」翻译成原厂 CoreService 的 action 投递，并
# 提供「链路到底活着没有」的判据（蓝牙栈 HOGP state 是唯一真值）。
# 本文件只定义函数。
# ============================================================================

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
    ts=$(get_global lenovo_pen_user_action_ts)
    target=$(get_global lenovo_pen_user_action_target)
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

request_oem_pen_action() {
    action="$1"
    mac=$(resolve_pen_mac)
    if ! is_pen_mac "$mac"; then
        log "OEM $action skipped: no bonded pen MAC"
        return 0
    fi
    if [ -z "$(pm path com.oplus.ipemanager 2>/dev/null)" ]; then
        # 「包还不在 PMS 视野里」（开机早期 priv-app 尚未登记，实测 12:08:55
        # 那几次 `pm path` 全空）也不等于投递成功：调用方若据此认为 OEM 通路
        # 可用，就会在只有 HID 半边可用的时候先断链。硬重连把它当失败，延后
        # 重试；其它调用方本来就不看返回值，行为不变。
        log "OEM $action unavailable: IPeManager not registered yet"
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

        log "OEM $action requested mac=$mac"
        return 0
    fi
    # 失败有三种成因，调用方按返回值决定是否延后（硬重连会，其它调用忽略）：
    #   1) 用户未解锁 -> PMS 过滤掉非 direct-boot-aware 的 CoreService，
    #      am 报 "Error: Not found; no service started" (rc=255)；
    #   2) 以 shell(uid 2000) 身份调用 -> "Requires permission
    #      com.oplus.permission.safe.IOT"。模块以 root(uid 0) 运行，不受此限；
    #   3) 包尚未进入 PMS（开机早期 priv-app 未登记）-> 上面的 pm path 判空。
    log "OEM $action request failed mac=$mac unlocked=$(user_unlocked && echo 1 || echo 0)"
    return 1
}

request_pen_connect() {
    requested=$(get_global lenovo_pen_disconnect_requested)
    [ "$requested" = 1 ] && {
        log "pen connect skipped: Settings disconnect latch is set"
        return 0
    }
    user_requested=$(get_global lenovo_pen_user_disconnect_requested)
    [ "$user_requested" = 1 ] && {
        log "pen connect skipped: user disconnect choice is active"
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
}

request_pen_disconnect() {
    request_oem_pen_action "$OEM_DISCONNECT_ACTION"
}
