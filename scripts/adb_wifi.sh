#!/usr/bin/env bash
# WiFi (TCP) adb for the ColorOS bench device (OPD2409 / TB522FU work).
#
# Why this exists
# ---------------
# "无线 adb 不稳" (TODO.md 工程项) has three separate causes, only one of which
# is actually about WiFi:
#   1. USB + WiFi both listed by `adb devices` -> every bare `adb <cmd>` fails
#      with "more than one device/emulator". build.py's device_online() then
#      silently reports offline and SKIPS the step-4 device self-check.
#      Fix: pin the target with ANDROID_SERIAL (adb reads it natively, so every
#      subprocess -- build.py, pen_release.py -- inherits it with no code change).
#   2. A stale adb server holds a pre-DHCP network view -> `adb connect` returns
#      "No route to host" while `nc -z <ip> 5555` succeeds. Restarting the
#      server fixes it. This script retries automatically.
#   3. DHCP moves the device. Re-discovery reads wlan0 over USB instead of
#      trusting a cached address.
#
# Usage
#   scripts/adb_wifi.sh                 # connect (or reconnect) and print status
#   scripts/adb_wifi.sh connect [IP]    # same, with explicit address
#   scripts/adb_wifi.sh status          # show which transports are up
#   scripts/adb_wifi.sh env             # print `export ANDROID_SERIAL=...`
#   scripts/adb_wifi.sh off             # put adbd back into USB mode
#
#   eval "$(scripts/adb_wifi.sh env)"   # make every later adb call use WiFi
#
# Notes
#   * persist.adb.tcp.port=5555 is already set on the device, so after a reboot
#     adbd listens on 5555 with no USB involved -- `connect` is tried first.
#   * `adb install` is rejected by ColorOS (Failure [-99]); use root pm install.
set -euo pipefail

REPO="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$REPO/scripts/.build/adb_wifi.last"
PORT=5555

# --- adb resolution (same order as deploy_vector.sh / push_to_device.sh) ------
if [ -z "${ADB:-}" ]; then
    if command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    else
        for c in "$HOME/Library/Android/sdk/platform-tools/adb" \
                 /opt/homebrew/bin/adb /usr/local/bin/adb; do
            [ -x "$c" ] && ADB="$c" && break
        done
    fi
fi
[ -n "${ADB:-}" ] && [ -x "$ADB" ] || {
    echo "adb not found; install platform-tools or set ADB=/path/to/adb" >&2; exit 1;
}

say() { printf '%s\n' "$*"; }
die() { printf '%s\n' "$*" >&2; exit 1; }

# USB-connected serials only (WiFi transports look like "1.2.3.4:5555").
usb_serials() {
    "$ADB" devices | awk '$2=="device" && $1 !~ /:/ {print $1}'
}
# All device-state serials, both transports.
up_serials() {
    "$ADB" devices | awk '$2=="device" {print $1}'
}
is_up() {  # $1 = serial
    "$ADB" devices | awk -v s="$1" '$1==s && $2=="device" {found=1} END {exit !found}'
}

# Read wlan0's IPv4 from a USB-attached device -- authoritative after DHCP moves.
ip_from_usb() {
    local s
    for s in $(usb_serials); do
        "$ADB" -s "$s" shell "ip -f inet addr show wlan0 2>/dev/null" 2>/dev/null \
            | awk '/inet /{sub(/\/.*/,"",$2); print $2; exit}'
    done
}

discover_ip() {
    local arg="${1:-}"
    if [ -n "$arg" ]; then printf '%s\n' "$arg"; return; fi
    local via_usb; via_usb="$(ip_from_usb || true)"
    if [ -n "$via_usb" ]; then printf '%s\n' "$via_usb"; return; fi
    if [ -f "$CACHE" ]; then cat "$CACHE"; return; fi
    return 1
}

connect_target() {  # $1 = ip  -> 0 on success
    local ip="$1" target="$1:$PORT"
    if is_up "$target"; then say "  已在 WiFi 上: $target"; return 0; fi

    say "  adb connect $target"
    local out
    out="$("$ADB" connect "$target" 2>&1 || true)"
    say "    $out"
    sleep 1
    if is_up "$target"; then return 0; fi

    # Stale-server retry: the classic "No route to host" while nc succeeds.
    if printf '%s' "$out" | grep -qi -e 'no route to host' -e 'failed to connect'; then
        say "  [retry] adb server 网络视图可能过期，重启 server 后重试"
        "$ADB" kill-server >/dev/null 2>&1 || true
        "$ADB" start-server >/dev/null 2>&1 || true
        sleep 2
        out="$("$ADB" connect "$target" 2>&1 || true)"
        say "    $out"
        sleep 1
        is_up "$target" && return 0
    fi
    return 1
}

cmd_connect() {
    local ip
    if ! ip="$(discover_ip "${1:-}")"; then
        die "找不到设备 IP：USB 未连接且无缓存 ($CACHE)。
插 USB 跑一次本脚本即可写缓存，或显式指定：
  scripts/adb_wifi.sh connect 192.168.x.y"
    fi
    [ -n "$ip" ] || die "IP 为空"

    local target="$ip:$PORT"

    # 1) direct: works after reboot because persist.adb.tcp.port=5555 is set
    if connect_target "$ip"; then :; else
        # 2) bounce adbd into TCP mode over USB, then retry
        local s
        s="$(usb_serials | head -1)"
        if [ -n "$s" ]; then
            say "  USB 在线 ($s) -> adb -s $s tcpip $PORT"
            "$ADB" -s "$s" tcpip "$PORT" >/dev/null 2>&1 || true
            sleep 3
            connect_target "$ip" || die "WiFi 连接失败：$target
诊断：
  $ADB devices -l
  nc -z -v $ip $PORT
  $ADB shell su -c 'ss -tlnp | grep $PORT'"
        else
            die "WiFi 连接失败且无 USB 兜底：$target
若设备刚重启，等 WiFi 起来再试；否则插 USB 后重跑本脚本。"
        fi
    fi

    mkdir -p "$(dirname "$CACHE")"
    printf '%s\n' "$ip" > "$CACHE"
    if usb_serials | grep -q .; then
        say ""
        say "⚠  USB 与 WiFi 同时在线：不带 -s 的 adb 命令会报 more than one device。"
        say "   先执行： eval \"\$(scripts/adb_wifi.sh env)\""
        say "   或物理拔掉 USB 线。"
    fi
    cmd_status
}

cmd_status() {
    say "adb: $ADB"
    say "transports:"
    "$ADB" devices -l | sed 's/^/  /'
    say "ANDROID_SERIAL=${ANDROID_SERIAL:-<unset>}"
    if [ -f "$CACHE" ]; then say "cached IP: $(cat "$CACHE")"; else say "cached IP: <none>"; fi
    local n wifi; n="$(up_serials | grep -c . || true)"
    wifi="$(up_serials | grep ':' | head -1 || true)"
    if [ "$n" -gt 1 ]; then
        say ""
        say "⚠  检测到 $n 个可用设备：不带 -s 的 adb 命令会报 more than one device。"
        say "   跑 build.py / deploy_vector.sh 前先执行："
        if [ -n "$wifi" ]; then
            say "     export ANDROID_SERIAL=$wifi"
        else
            say "     eval \"\$(scripts/adb_wifi.sh env)\""
        fi
    fi
}

cmd_env() {
    local ip
    if ! ip="$(discover_ip "")"; then
        die "无 IP 可导出；先跑 scripts/adb_wifi.sh connect" >&2
    fi
    printf 'export ANDROID_SERIAL=%s:%s\n' "$ip" "$PORT"
}

cmd_off() {
    local ip; ip="$(discover_ip "" || true)"
    [ -n "$ip" ] || die "无 IP；无设备可切换"
    is_up "$ip:$PORT" || die "$ip:$PORT 不在线，无需切回"
    "$ADB" -s "$ip:$PORT" usb
    say "已切回 USB 模式（5555 关闭）"
    cmd_status
}

case "${1:-connect}" in
    connect) shift || true; cmd_connect "${1:-}" ;;
    status)  cmd_status ;;
    env)     cmd_env ;;
    off)     cmd_off ;;
    -h|--help|help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) # treat a bare argument as connect <ip>
       cmd_connect "$1" ;;
esac
