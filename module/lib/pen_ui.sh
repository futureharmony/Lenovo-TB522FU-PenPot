#!/system/bin/sh
# ============================================================================
# lib/pen_ui.sh — 状态镜像与磁吸胶囊（只读事件的对外发布）
# ----------------------------------------------------------------------------
# 职责：把硬件真值转成 ColorOS 设置页 / 磁吸胶囊/ 刷新率策略能消费的
# settings 键与广播。**只跟随真实事件**，不强行回放、不合成状态。
# 本文件只定义函数。
# ============================================================================

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
        put_global "$key" 0
    done
    put_global lenovo_pen_refresh_active 0
    put_global settings_enable_oppo_pencil 0
    put_global ipe_pencil_present 0
    put_global ipe_pencil_charging_state 0
    put_global lenovo_pen_physical_docked 0
    put_global lenovo_pen_hardware_battery_valid 0
    put_global lenovo_pen_oem_charge_valid 0
    log "stale v1.0.61 connection mirror cleared"
}

normalize_disconnect_latch() {
    user_requested=$(get_global "$PEN_USER_DISCONNECT_KEY")
    case "$user_requested" in
        1|0) ;;
        *) user_requested=0 ;;
    esac
    # lenovo_pen_disconnect_requested is a runtime guard. Do not carry a
    # natural ACL shutdown from the previous boot into the next boot; only an
    # explicit settings-page Disconnect is persistent across reboot.
    put_global "$PEN_USER_DISCONNECT_KEY" "$user_requested"
    put_global lenovo_pen_disconnect_requested "$user_requested"
    log "disconnect latch normalized user=$user_requested"
}

request_pen_capsule() {
    [ "$(read_hall_state)" = 1 ] || return 0
    connected=$(get_global lenovo_pen_link_connected)
    if [ "$connected" != 1 ]; then
        log "magnetic capsule delayed: BLE link not ready"
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
        log "magnetic capsule delayed: no battery sample yet"
        return 1
    fi
    battery_valid=$(get_global lenovo_pen_hardware_battery_valid)
    [ "$battery_valid" = 1 ] || \
        log "magnetic capsule uses last valid battery=$battery (fresh sample pending)"
    now=$(date '+%s' 2>/dev/null)
    previous=$(cat "$CAPSULE_DEDUP_FILE" 2>/dev/null)
    previous_time=${previous%%:*}
    previous_state=${previous#*:}
    case "$now:$previous_time" in
        *[!0-9:]*|:*) ;;
        *)
            if [ "$previous_state" = 1 ] && [ "$now" -ge "$previous_time" ] \
                    && [ "$((now - previous_time))" -lt "$CAPSULE_DEDUP_SECONDS" ]; then
                log "duplicate magnetic capsule suppressed"
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
    log "real Hall magnetic capsule requested battery=$battery charging=$charging mac=$mac"
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
    log "magnetic capsule abandoned: link/battery not ready after ${attempts} attempts"
}

publish_hall_state() {
    docked="$1"
    battery=$(read_hardware_battery)
    charging=$(read_hardware_charging "$docked")
    mac=$(pen_mac_compact)
    connected=$(get_global lenovo_pen_link_connected)
    [ "$connected" = 1 ] || connected=0

    put_global lenovo_pen_physical_docked "$docked"
    put_global ipe_pencil_charging_state "$charging"
    if valid_level "$battery"; then
        # This is a cache update only after a real sample.  An unavailable
        # CPS/GATT sample must not overwrite the last known level with -1.
        put_global ipe_pencil_battery_level "$battery"
    fi
    refresh_active=0
    if [ "$docked" != 1 ] && [ "$connected" = 1 ]; then
        refresh_active=1
    fi
    # The refresh-rate policy is valid only while writing, but Device Space
    # treats the next two keys as the existence of a connected pen. Do not
    # hide a real Bluetooth device merely because it is magnetically docked.
    put_global lenovo_pen_refresh_active "$refresh_active"
    # OPlusRefreshRatePolicyImpl reads settings_enable_oppo_pencil as
    # isIPEPencilConnected and votes ipePencilRateId (120 Hz) while it is 1.
    # A docked pen is not being written with, so report "pen in use" only
    # when the pen is both connected and off the magnetic dock.
    put_global settings_enable_oppo_pencil "$refresh_active"
    put_global ipe_pencil_present "$connected"

    battery_args=""
    battery_trusted=$(get_global lenovo_pen_hardware_battery_valid)
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
    log "real Hall state docked=$docked battery=$battery trusted=$hardware_battery charging=$charging connected=$connected mac=$mac"
}
