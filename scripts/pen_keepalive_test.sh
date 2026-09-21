#!/system/bin/sh
# ============================================================================
# 方案 F 验证：TX 载波常驻（临时诊断工具，不进 module zip 白名单）
# ============================================================================
# 因果链（2026-09-21 实证，见 cps-charger-driver-analysis.md + 内核日志）：
#   1) 吸附 → Hall 边沿 → dhall_och1909 直接调 cps_wls_charger → 开 TX + 带内握手
#      （内核模块引用计数证实：cps_wls_charger 被 qti_battery_charger,dhall_och1909 持有）
#   2) 笔满电 → 笔通过 ASK 发包 charging_cmd=停充 → 驱动日志 "close tx"
#      （驱动字符串："ask pkt data[2]:%d, charging_cmd:%d, close tx"）
#   3) 载波消失 → 笔固件判定"已离座" → 自行关机
#   4) 蓝牙 HOGP state 不清零 → real_bt_connected() 恒真 → 僵尸连接 → 无人重连
#
# 已证伪的路（全部 software-only 尝试，online 恒 0）：
#   B) 写 tx_status=1       TX 开 30s，online=0
#   C) 广播 CONNECT_PENCIL  CoreService 响应并上报 charging=1，online=0
#   D) TX 常开 120s         驱动约 30s 后自己关（空载超时），online=0
#   E) 关 TX 后 50s 重开    online 连 23s 恒 0
#
# 本方案要回答：若载波中断被压缩到 <100ms（驱动一关就顶回去），
# 笔是否还会判定"离座"并关机？成功判据：online 保持 1 远超 27s 的驱动关 TX 周期。
#
# ★ 结论（2026-09-21，实机跑过）：**证伪**。10Hz 顶回时载波中断 <1 秒，笔照样失联。
#   实测序列：09:18:22 KEEP#1 tx 0->1（驱动一关即顶回）→ 09:18:24 online=0。
#   笔对"离座"的判定是即时的，载波一瞬都不能断。本脚本因此已无实用价值，
#   保留仅为复现参考；修法转到「状态镜像诚实化 + 僵尸判据」，
#   见 docs/pen-sleep-death-analysis.md。
#
# 用法：DURATION=900 sh pen_keepalive_test.sh
# 安全：只写 1，从不写 0；未见 online=1 前不动手（不空载送电）；连续失败即放弃。
# ============================================================================

OUT=${OUT:-/data/adb/modules/tb522fu_pen_bridge/pen-keepalive.log}
DURATION_SEC=${DURATION_SEC:-900}
MAX_FAIL=${MAX_FAIL:-10}
TICK=0.1                 # 维持循环周期（秒）—— 载波中断上限
LOG_EVERY=20             # 每 20 tick（2s）做一次全量采样与判定

TX_NODE=
for d in /sys/bus/i2c/devices/*-0041; do
    [ -r "$d/tx_status" ] && { TX_NODE="$d/tx_status"; break; }
done
[ -n "$TX_NODE" ] || { echo "TX_NODE not found" >>"$OUT"; exit 1; }

HALL=/sys/devices/virtual/hall/och1909/hall3
read_tx() { cat "$TX_NODE" 2>/dev/null | tr -d ' ' | grep -o 'cps_wls_en:[01]' | cut -d: -f2; }
read_on() { cat /sys/class/power_supply/cps_wls_tx/online 2>/dev/null; }
read_cap() { cat /sys/class/power_supply/cps_wls_tx/capacity 2>/dev/null; }
read_hall() { h=$(cat "$HALL" 2>/dev/null); echo "${h##* }"; }

printf '[%s] === keepalive start  TICK=%ss DURATION=%ss MAX_FAIL=%s ===\n' \
    "$(date '+%F %T')" "$TICK" "$DURATION_SEC" "$MAX_FAIL" >>"$OUT"

TOTAL=$((DURATION_SEC * 10))
i=0
keeps=0
holds=0
fail_run=0
armed=0                  # 见过 online=1 才允许动手
gave_up=0
prev_hall=""
prev_line=""
docks=0

while [ "$i" -lt "$TOTAL" ]; do
    h=$(read_hall)

    # 新的吸附边沿：重置所有状态（用户重新放回笔）
    if [ "$prev_hall" = "1" ] && [ "$h" = "0" ]; then
        docks=$((docks+1))
        armed=0; fail_run=0; gave_up=0
        printf '[%s] --- new docking #%d; re-armed, awaiting fresh online=1 ---\n' \
            "$(date '+%H:%M:%S')" "$docks" >>"$OUT"
    fi
    prev_hall="$h"

    # --- 高频维持：吸附中且已武装，TX 一灭就顶上 ---
    if [ "$h" = "0" ] && [ "$armed" = "1" ] && [ "$gave_up" = "0" ]; then
        tx=$(read_tx)
        if [ "$tx" != "1" ]; then
            if printf '1\n' >"$TX_NODE" 2>/dev/null; then
                keeps=$((keeps+1))
                printf '[%s] KEEP#%d tx 0->1 (tick %d)\n' \
                    "$(date '+%H:%M:%S')" "$keeps" "$i" >>"$OUT"
            fi
        fi
    fi

    # --- 低频判定（每 LOG_EVERY tick）---
    if [ $((i % LOG_EVERY)) -eq 0 ]; then
        on=$(read_on); cap=$(read_cap)
        if [ "$on" = "1" ]; then
            holds=$((holds+1)); fail_run=0
            [ "$armed" = "0" ] && printf '[%s] online=1 seen -> armed\n' "$(date '+%H:%M:%S')" >>"$OUT"
            armed=1
        else
            holds=0
            [ "$armed" = "1" ] && [ "$gave_up" = "0" ] && fail_run=$((fail_run+1))
        fi

        cur="hall=$h online=$on cap=$cap holds=${holds}x2s keeps=$keeps fail=$fail_run/$MAX_FAIL"
        if [ "$cur" != "$prev_line" ]; then
            printf '[%s] %s\n' "$(date '+%H:%M:%S')" "$cur" >>"$OUT"
            prev_line="$cur"
        fi

        if [ "$fail_run" -ge "$MAX_FAIL" ] && [ "$gave_up" = "0" ]; then
            gave_up=1
            printf '[%s] gave up: %d consecutive samples with online=0 while driving -> pen powered off; coil released\n' \
                "$(date '+%H:%M:%S')" "$fail_run" >>"$OUT"
        fi
    fi

    i=$((i+1))
    sleep "$TICK"
done

printf '[%s] === finished: keeps=%d hold_ticks=%d docks=%d gave_up=%d ===\n' \
    "$(date '+%F %T')" "$keeps" "$holds" "$docks" "$gave_up" >>"$OUT"
