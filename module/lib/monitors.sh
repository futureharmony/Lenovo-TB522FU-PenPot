#!/system/bin/sh
# ============================================================================
# lib/monitors.sh — 常驻监视循环（电量 / 充电 / 真实链路 / Hall 边沿 / HID 闩锁）
# ----------------------------------------------------------------------------
# ⚠️ 这些函数都是**永不返回的死循环**，由 service.sh 以 & 后台 fork 启动。
#    子 shell 在 fork 那一刻复制父进程的符号表，所以 service.sh 必须在
#    启动它们之前 source 完所有 lib —— 否则会静默 command not found。
#    循环退出条件是 $CPS_DISABLED 文件出现（模块被禁用/卸载）。
# ============================================================================

monitor_battery_cache() {
    while [ ! -e "$CPS_DISABLED" ]; do
        # Before the real HOGP link exists, IPeManager deliberately owns an
        # unknown battery state. Re-injecting a cached level every 10 seconds
        # makes it clear that state again, then restarts the same UI/BT work.
        # Hall-edge publication already preserves the last known value for
        # the capsule; continuous repair begins only after a real link.
        connected=$(get_global lenovo_pen_link_connected)
        if [ "$connected" != 1 ]; then
            sleep_sec 10
            continue
        fi
        before=$(get_global ipe_pencil_battery_level)
        level=$(read_hardware_battery)
        # The OEM process can publish an unknown sample after the Root boot
        # snapshot. Repair only that cache transition, then let the normal
        # settings receiver consume one valid battery notification.
        if valid_level "$level" && [ "$before" != "$level" ]; then
            docked=$(read_hall_state)
            case "$docked" in
                0|1)
                    publish_hall_state "$docked"
                    log "invalid battery cache repaired level=$level"
                    ;;
            esac
        fi
        sleep_sec 10
    done
}

monitor_charging_cache() {
    last=$(get_global lenovo_pen_hardware_charge_state)
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        # As with battery repair, avoid fighting IPeManager's disconnected
        # state machine. A Hall edge still publishes the physical dock/charge
        # snapshot once; periodic correction is for an established BLE link.
        connected=$(get_global lenovo_pen_link_connected)
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
                        mirrored=$(get_global ipe_pencil_charging_state)
                        if [ "$charging" != "$last" ]; then
                            last="$charging"
                            publish_hall_state "$docked"
                            log "charging state changed charging=$charging mirrored=$mirrored docked=$docked"
                        fi
                        ;;
                esac
                ;;
        esac
        sleep_sec 10
    done
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
        current=$(get_global lenovo_pen_link_connected)
        [ "$current" = 1 ] || current=0
        if [ "$connected" != "$current" ] || [ "$connected" != "$last" ]; then
            put_global lenovo_pen_link_connected "$connected"
            # OPlusRefreshRateService treats ipe_pencil_connect_state==1 as the
            # IPE pencil connected (isIPEPencilConnected) and votes the OEM
            # ipePencilRateId (120 Hz) while connected. The other mirror keys
            # keep the legacy connected encoding for the pen settings UI.
            connect_state=0
            [ "$connected" = 1 ] && connect_state=2
            for key in ipe_pencil_connection_state PENCIL_CONNECT_STATE pencil_connect_state; do
                put_global "$key" "$connect_state"
            done
            put_global ipe_pencil_connect_state "$connected"
            docked=$(get_global lenovo_pen_physical_docked)
            [ "$docked" = 1 ] || docked=0
            pen_in_use=0
            [ "$connected" = 1 ] && [ "$docked" != 1 ] && pen_in_use=1
            put_global settings_enable_oppo_pencil "$pen_in_use"
            put_global ipe_pencil_present "$connected"
            if [ "$connected" = 1 ]; then
                user_requested=$(get_global lenovo_pen_user_disconnect_requested)
                if [ "$user_requested" = 1 ]; then
                    put_global lenovo_pen_user_disconnect_requested 0
                    put_global lenovo_pen_disconnect_requested 0
                    log "real BT link present; cleared stale disconnect latch"
                fi
            fi
            # Push the real link to the OEM UI: the Hook's handoff receiver
            # consumes the connected extra and forwards it to the panel and
            # settings callbacks, so a sheet opened before the link came up
            # stops showing a stale "connecting/disconnected" state.
            battery=$(get_global ipe_pencil_battery_level)
            valid_level "$battery" || battery=$(get_global lenovo_pen_last_valid_battery)
            charging=$(get_global lenovo_pen_hardware_charge_state)
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
            log "real BT state mirror connected=$connected (was $current)"
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
                && [ "$(get_global lenovo_pen_oem_control_ready)" != 1 ] \
                && [ "$(get_global lenovo_pen_disconnect_requested)" != 1 ]; then
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
                            log "live HOGP missing OEM haptic session; recovery requested attempt=$oem_recovery_attempts/3"
                        else
                            oem_recovery_blocked=$((oem_recovery_blocked + 1))
                            log "OEM haptic recovery not deliverable; channel unreachable, budget kept (${oem_recovery_blocked}/40)"
                            if [ "$oem_recovery_blocked" -ge 40 ]; then
                                oem_recovery_exhausted=1
                                log "OEM haptic recovery deferred until next real BT link edge"
                            fi
                        fi
                    elif [ "$oem_recovery_attempts" -ge 3 ] && [ "$oem_recovery_exhausted" = 0 ]; then
                        # The OEM receiver rejected a live link repeatedly.
                        # Retrying forever turns a missing optional session
                        # into a 30-second Bluetooth/system_server CPU spike.
                        # The direct GATT haptic path remains available; try
                        # OEM recovery again only after a real link edge.
                        oem_recovery_exhausted=1
                        log "OEM haptic recovery deferred until next real BT link edge"
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
    reconcile_ticks=0
    last=$(cat "$HALL_STATE_FILE" 2>/dev/null | tr -d '\r')
    case "$last" in 0|1) ;; *) last=-1 ;; esac
    while [ ! -e "$CPS_DISABLED" ]; do
        state=$(read_hall_state)
        case "$state" in
            0|1)
                # --- 低频对账（每 5 秒）：镜像被外部写者写歪时自愈 ---
                # 2026-09-22 实测事故：hall3=1（**未**吸附）而
                # lenovo_pen_physical_docked 卡在 1，ColorOS 笔 UI 因此长期显示
                # 「吸附 / 充电」，而模块自己的 pen-hall.state 早已是 0。
                # 原因：这个键有两个写者，且**各自只在各自触发点写**——
                #   ① 本文件的 publish_hall_state()（Hall 边沿 / 充电跳变时）
                #   ② HookUtils.setPhysicalDocked()（Hook 收到 OEM/桥接事件时）
                # 没有任何一方做对账 ⇒ 一旦某一方在另一方不发布的时刻写歪，
                # 这个值就**永久歪下去**（实测写入后 30 秒无变化、手改 0 后也不被改回）。
                # 这里每 5 秒与**原始 Hall** 比对一次并纠正。
                # 特意只修这一个键：不走 publish_hall_state（那会重刷
                # ipe_pencil_battery_level，monitor_battery_cache 的注释说明过
                # 每 10 秒重注入电量会让 IPeManager 清状态并重启 UI/BT 工作），
                # 也不触发胶囊。
                reconcile_ticks=$((reconcile_ticks + 1))
                if [ "$reconcile_ticks" -ge 5 ]; then
                    reconcile_ticks=0
                    published=$(get_global lenovo_pen_physical_docked)
                    case "$published" in 0|1) ;; *) published=-1 ;; esac
                    if [ "$published" != "$state" ]; then
                        put_global lenovo_pen_physical_docked "$state"
                        log "physical_docked reconciled: $published -> $state (raw hall)"
                    fi
                fi
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
                            put_global lenovo_pen_hardware_battery_valid 0
                        fi
                        publish_hall_state "$state"
                        if [ "$state" = 1 ]; then
                            # Magnetic attach is a physical reconnect intent:
                            # clear any stale disconnect latch and restore the
                            # link if it is not already up at the BT layer.
                            put_global lenovo_pen_user_disconnect_requested 0
                            put_global lenovo_pen_disconnect_requested 0
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
                        fi
                    fi
                    boot_cycle=0
                fi
                ;;
        esac
        sleep_sec 1
    done
}

# The stock settings action updates the IPe state, but on this port HID Host
# remains connected. Enforce an explicit settings-page Disconnect at both the
# vendor CoreService and the actual HID profile. A 1 -> 0 transition is the
# only runtime path that requests a connect; an idle 0 with no live link is
# left alone because the bounded boot loop is the only automatic recovery.
monitor_hid_latch() {
    last=$(get_global lenovo_pen_disconnect_requested)
    case "$last" in
        1|0) ;;
        *) last=-1 ;;
    esac
    repeat=0
    while [ ! -e "$CPS_DISABLED" ]; do
        requested=$(get_global lenovo_pen_disconnect_requested)
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
                    user_requested=$(get_global lenovo_pen_user_disconnect_requested)
                    if [ "$user_requested" = 1 ]; then
                        log "explicit pen connect skipped: user disconnect choice is active"
                    elif real_bt_connected; then
                        log "explicit pen connect already has a real link"
                    else
                        request_pen_connect
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
