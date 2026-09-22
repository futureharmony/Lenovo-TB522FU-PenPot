#!/system/bin/sh
# ============================================================================
# lib/boot.sh — 启动期一次性动作（inkdye / 稳定性 / 权限 / 首次连接）
# ----------------------------------------------------------------------------
# 职责：开机后需要「重试到确认生效」的动作。这些动作的共同点是
# PackageManager 在 service.sh 跑到时往往还没就绪，所以每个都带
# 状态复核 + 有限次重试，绝不允许「日志说做了、实际没做」。
# 本文件只定义函数，调用时机与顺序由 service.sh 决定。
# ============================================================================

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
                log "inkdye kept ENABLED (user choice via action.sh)"
                return 0
            fi
            i=$((i + 1)); sleep 3
        done
        log "WARN inkdye enable not confirmed after $i tries"
    else
        # 默认：禁用系统内置笔桥
        while [ "$i" -lt 40 ]; do
            pm disable-user --user 0 "$INKDYE_PKG" >/dev/null 2>&1
            if inkdye_is_disabled; then
                log "inkdye disabled by default (enable via action.sh)"
                return 0
            fi
            i=$((i + 1)); sleep 3
        done
        log "WARN inkdye disable not confirmed after $i tries"
    fi
}

# --- 防整机重启：禁用/停止移植ROM缺失专有驱动的高危服务 ---
apply_system_stability_fixes() {
    stop vendor.urcc-hal-aidl 2>/dev/null
    setprop ctl.stop vendor.urcc-hal-aidl 2>/dev/null
    i=0
    while [ "$i" -lt 40 ]; do
        pm disable com.oplus.gesture >/dev/null 2>&1
        if pm list packages -d 2>/dev/null | grep -q "^package:com.oplus.gesture$"; then
            log "com.oplus.gesture disabled for boot stability"
            break
        fi
        i=$((i + 1)); sleep 3
    done
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
        log "bridge permission grant skipped: Hook APK unavailable"
        return 0
    fi
    failed=0
    for permission in \
            android.permission.BLUETOOTH_CONNECT \
            android.permission.BLUETOOTH_SCAN; do
        if ! pm grant --user 0 "$target_pkg" "$permission" >/dev/null 2>&1; then
            failed=1
            log "bridge permission grant failed permission=$permission pkg=$target_pkg"
        fi
    done
    if [ "$failed" = 0 ]; then
        : >"$BRIDGE_PERMISSION_FILE"
        log "bridge Bluetooth runtime permissions granted for $target_pkg"
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
                log "bridge Hook APK un-stopped ($pkg)"
            fi
        fi
    done
}


# --- 启动失败自保：清除 post-fs-data 的失败计数 -----------------------------
# post-fs-data.sh 每次开机先 +1 记账，连续失败超阈值就熔断（见该文件）。
# 只有系统【真正】到达 sys.boot_completed=1 才清零 —— 否则一个能跑到
# late_start 但随后崩掉的坏镜像会被误判为「启动成功」，计数被白白清空。
# 由 service.sh 以 & 后台调用，不阻塞服务启动。
clear_boot_fail_counter() {
        _n=0
        while [ "$_n" -lt 180 ]; do
            [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] && break
            sleep 2
            _n=$((_n + 1))
        done
        if [ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ]; then
            rm -f /data/adb/tb522fu_pen_bridge.bootfail 2>/dev/null
        fi
}


# The ported IPeManager package carries the vendor Bluetooth receivers in
# its resolver table, but their user-0 component state is disabled.  Enable
# only those two stock receivers so the real OAF/ACL events can start
# CoreService under Oppo's own UID; no synthetic event or connection state is
# written here.
enable_oppo_pen_receivers() {
    for receiver in \
        com.oplus.ipemanager/.btadsorb.ble.BluetoothStatusReceiver \
        com.oplus.ipemanager/.btadsorb.receiver.BluetoothBroadcastReceiver; do
        if pm enable --user 0 "$receiver" >/dev/null 2>&1; then
            log "enabled Oppo pen receiver=$receiver"
        else
            log "unable to enable Oppo pen receiver=$receiver"
        fi
    done
}


# Re-emit the driver's current INFO/MAC/TOUCH_INFORMATION snapshot after the
# LSPosed system_server observer has started.  Without this the observer has
# no initial sample to seed the settings mirrors with.
emit_driver_uevents() {
    if [ -w "$UEVENT" ]; then
        echo change >"$UEVENT"
        log "PEN_FRAMEWORK uevent requested"
    fi
    if [ -w "$CPS_UEVENT" ]; then
        echo change >"$CPS_UEVENT"
        log "CPS uevent requested"
    fi
}


# Keep the real vendor request alive across Bluetooth's late service startup
# window.  Stop as soon as the ACL/GATT callback has reported a live link; do
# not send the old RECONNECT_PEN broadcast, which clears the settings latch and
# invokes a synthetic system_server replay before the real link exists.
# The Lenovo pen powers itself off when it is not magnetically docked, so
# actively searching for it over Bluetooth is pointless (and the early pokes
# used to restart the adapter).  Only connect after the CPS power-on, which can
# only wake a docked pen; the dock-attach edge reconnects it later.
# Do not poke the Bluetooth stack while it is still coming up.
boot_pen_connect() {
    bt_wait=0
    while [ "$bt_wait" -lt 60 ]; do
        [ "$(get_global bluetooth_on)" = 1 ] && break
        sleep_sec 2
        bt_wait=$((bt_wait + 2))
    done
    requested=$(get_global lenovo_pen_disconnect_requested)
    user_requested=$(get_global lenovo_pen_user_disconnect_requested)
    connected=$(get_global lenovo_pen_link_connected)
    if [ "$connected" = 1 ]; then
        log "boot pen connect confirmed by real ACL/GATT state"
    elif [ "$requested" = 1 ] || [ "$user_requested" = 1 ]; then
        log "boot pen connect skipped: Settings disconnect latch is set"
    elif [ "$(read_hall_state)" = 1 ]; then
        # Docked: the CPS boot power sequence already ran; connect once and give
        # the link a bounded window to come up.
        request_pen_connect
        log "real OEM boot connect requested (docked/CPS powered)"
        attempt=2
        while [ "$attempt" -le 4 ] && [ ! -e "$CPS_DISABLED" ]; do
            sleep_sec 12
            connected=$(get_global lenovo_pen_link_connected)
            if [ "$connected" = 1 ]; then
                log "boot pen connect confirmed by real ACL/GATT state"
                break
            fi
            request_pen_connect
            log "real OEM boot connect retry attempt=$attempt"
            attempt=$((attempt + 1))
        done
    else
        log "pen not docked; boot connect skipped (CPS can only wake a docked pen)"
    fi
}
