#!/system/bin/sh
# ============================================================================
# lib/pen_hw.sh — 硬件真值读取（Hall / 电量 / 充电 / CPS）
# ----------------------------------------------------------------------------
# 职责：从内核节点与 Settings 缓存里读出笔的物理状态真值。**全部只读**
# （唯一的写是把自己的新鲜采样回写进 settings 缓存，见 cache_hardware_*）。
#
# ⚠️ 本文件里绝大多数函数都是被 $(...) 捕获的纯读取函数 —— 函数内的 echo
#    全是「返回值」，不是日志。所以这里绝对不能出现 log()，需要日志时用
#    log_err()（>&2，不进返回值）。
# ============================================================================

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
    put_global_diff ipe_pencil_battery_level "$level"
    put_global_diff lenovo_pen_last_valid_battery "$level"
    put_global_diff lenovo_pen_hardware_battery_valid 1
    now=$(date '+%s' 2>/dev/null)
    case "$now" in
        ''|*[!0-9]*) ;;
        *) put_global lenovo_pen_hardware_battery_last_at "$now" ;;
    esac
}

suspect_unconnected_zero_battery() {
    [ "$1" = 0 ] || return 1
    connected=$(get_global lenovo_pen_link_connected)
    [ "$connected" != 1 ]
}

read_hardware_battery() {
    level=$(get_global ipe_pencil_battery_level)
    battery_valid=$(get_global lenovo_pen_hardware_battery_valid)
    if valid_level "$level" && [ "$battery_valid" = 1 ] \
            && ! suspect_unconnected_zero_battery "$level"; then
        echo "$level"
        return 0
    fi
    level=$(read_level_from_file "$CPS_UEVENT")
    # CPS exposes LEVEL=0 while the docked pen is only waiting to power up.
    # A genuine 0% sample is still accepted from the live BLE/GATT cache, but
    # a raw kernel/CPS zero is never promoted to trusted battery state.
    connected=$(get_global lenovo_pen_link_connected)
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
    level=$(get_global lenovo_pen_last_valid_battery)
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
            put_global lenovo_pen_hardware_charge_state "$state"
            put_global lenovo_pen_hardware_charge_valid 1
            # Keep the OEM charging keys in sync with the CPS truth so the
            # Hook handoff cannot override a fresh hardware sample with a
            # stale BLE 2A1A value.
            put_global lenovo_pen_oem_charge_valid 1
            put_global lenovo_pen_oem_charge_state "$state"
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
    charge_valid=$(get_global lenovo_pen_hardware_charge_valid)
    charge_state=$(get_global lenovo_pen_hardware_charge_state)
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
        return 0
    fi
    charge_valid=$(get_global lenovo_pen_oem_charge_valid)
    charge_state=$(get_global lenovo_pen_oem_charge_state)
    if [ "$charge_valid" = 1 ] && { [ "$charge_state" = 0 ] || [ "$charge_state" = 1 ]; }; then
        echo "$charge_state"
    else
        echo 0
    fi
}

# 笔是否正在线圈上真实响应（CPS 侧带内通信判据）。
# 与 real_bt_connected() 的分工：后者读蓝牙栈，而笔突然掉电时 HOGP state
# 会滞后保持 2（僵尸连接）；本判据读 CPS 内核驱动，与 Hall 磁场边沿同步，
# 实测 TX 关闭时同秒归零（见 docs/pen-sleep-death-analysis.md 的 1 秒序列）。
pen_cps_responsive() {
    [ -n "$CPS_PS_ONLINE" ] && [ -r "$CPS_PS_ONLINE" ] || return 1
    [ "$(tr -d '\r' <"$CPS_PS_ONLINE" 2>/dev/null)" = 1 ]
}
