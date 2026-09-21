#!/system/bin/sh
# ============================================================================
# 高频笔状态探针（临时诊断工具，1 秒采样，仅在状态变化时落盘）
# ----------------------------------------------------------------------------
# 目的：精确确定「驱动关 TX」与「笔失联(online 0)」的先后与间隔，
#       从而算出干预窗口 —— revive-guard 必须在窗口内重开 TX 才可能有效。
# 背景：15 秒轮询的 pen-revive.log 精度不足以区分因果关系
#       （09:04:27 suspect lost 早于 09:04:30 driver closed TX，疑似采样误差）。
#
# 用法：DURATION=300 sh pen_trace.sh   （默认 300 秒）
# 输出：$OUT 仅记录变化行，首行是基线。
# ============================================================================

OUT=${OUT:-/data/adb/modules/tb522fu_pen_bridge/pen-trace.log}
DURATION=${DURATION:-300}
TX_NODE=
for d in /sys/bus/i2c/devices/*-0041; do
    [ -r "$d/tx_status" ] && { TX_NODE="$d/tx_status"; break; }
done

sample() {
    h=$(cat /sys/devices/virtual/hall/och1909/hall3 2>/dev/null); h=${h##* }
    tx=$(cat "$TX_NODE" 2>/dev/null | tr -d ' ' | grep -o 'cps_wls_en:[01]' | cut -d: -f2)
    bo=$(cat "$TX_NODE" 2>/dev/null | tr -d ' ' | grep -o 'cps_boost_mode:[01]' | cut -d: -f2)
    on=$(cat /sys/class/power_supply/cps_wls_tx/online 2>/dev/null)
    cap=$(cat /sys/class/power_supply/cps_wls_tx/capacity 2>/dev/null)
    chg=$(settings get global ipe_pencil_charging_state 2>/dev/null)
    link=$(settings get global lenovo_pen_link_connected 2>/dev/null)
    echo "hall=${h:-?} tx=${tx:-?} boost=${bo:-?} online=${on:-?} cap=${cap:-?} chg=${chg:-?} link=${link:-?}"
}

command -v penlog_append >/dev/null 2>&1 || {
    if [ -f "${OUT%/*}/bin/penlog.sh" ]; then . "${OUT%/*}/bin/penlog.sh"; fi
}

i=0
last=""
while [ "$i" -lt "$DURATION" ]; do
    cur=$(sample)
    if [ "$cur" != "$last" ]; then
        printf '[%s] %s\n' "$(date '+%F %T')" "$cur" >>"$OUT"
        last="$cur"
    fi
    i=$((i + 1))
    sleep 1
done
printf '[%s] trace finished (%ss)\n' "$(date '+%F %T')" "$DURATION" >>"$OUT"
