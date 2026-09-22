#!/system/bin/sh
# ============================================================================
# lib/pen_id.sh — 笔身份解析（MAC 规范化 / 绑定记录发现）
# ----------------------------------------------------------------------------
# 职责：从 bt_config.conf 里找出「哪一个是笔」，并把各种写法归一成
# AA:BB:CC:DD:EE:FF。不碰蓝牙栈，也不写任何 settings（除了发现新地址时
# 回写 ipe_pencil_mac_addr）。本文件只定义函数。
# ============================================================================

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
        put_global ipe_pencil_mac_addr "$resolved"
        log_err "selected bonded pen address=$resolved previous=$configured"
    fi
    printf '%s\n' "$resolved"
}

pen_mac_compact() {
    mac=$(resolve_pen_mac)
    printf '%s\n' "$mac" | tr -d ':'
}
