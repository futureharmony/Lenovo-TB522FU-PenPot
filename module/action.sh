#!/system/bin/sh
# KSU / Magisk 管理器「执行」按钮：状态总览 + inkdye 开关
# ------------------------------------------------------------
# 默认行为：本模块**默认禁用**系统内置笔桥 inkdye
#   （com.inkdye.lenovopentocoloros），笔能力由本模块（Root 服务 + Hook）接管。
# 若内置笔桥 Hook 尚未生效、或想回退到系统原厂笔体验，用本脚本开启：
#     sh action.sh enable      # 恢复系统内置笔桥并持续维持
#     sh action.sh disable     # 回到模块默认（禁用内置笔桥）
#     sh action.sh toggle      # 在两者间切换
# 状态标记：存在 inkdye-enabled.state = 用户显式选择"启用内置笔桥"（覆盖默认）。
MODDIR=${0%/*}
LOG="$MODDIR/pen-bridge.log"
INKDYE_PKG=com.inkdye.lenovopentocoloros
STAMP="$MODDIR/inkdye-enabled.state"

echo "================ 状态 ================"
echo "hall3=$(cat /sys/devices/virtual/hall/och1909/hall3 2>/dev/null)"
echo "tx_status=$(cat /sys/bus/i2c/devices/11-0041/tx_status 2>/dev/null)"
echo "pen_battery=$(settings get global ipe_pencil_battery_level 2>/dev/null)"
echo "ipe_charging=$(settings get global ipe_pencil_charging_state 2>/dev/null)"
echo "hook_apk=$(pm path com.aclaniakea.lenovopenbridge 2>/dev/null | head -1)"
echo "hidctl_apk=$(pm path com.aclaniakea.penhidctl 2>/dev/null | head -1)"
echo "lsposed=$([ -d /data/adb/lspd ] && echo installed || echo no)"
echo "charge_guard_pid=$(cat "$MODDIR/charge-guard.pid" 2>/dev/null)"
if [ -f "$STAMP" ]; then
    echo "inkdye=ENABLED (user override, $(cat "$STAMP"))"
else
    echo "inkdye=DISABLED (module default)"
fi

echo "================ 用法 ================"
echo "切换 inkdye:  sh $0 enable|disable|toggle   （默认 disable）"

op="$1"
case "$op" in
    toggle)
        # 当前启用 → 切到禁用；当前禁用（默认）→ 切到启用
        if [ -f "$STAMP" ]; then op=disable; else op=enable; fi
        ;;
esac

case "$op" in
    enable)
        pm enable "$INKDYE_PKG" >/dev/null 2>&1
        echo "enabled at $(date)" >"$STAMP"
        echo "inkdye ENABLED（已交还系统内置笔桥；笔能力不再由本模块 Hook 接管）"
        echo "inkdye enabled via action.sh" >>"$LOG"
        ;;
    disable)
        if pm disable-user --user 0 "$INKDYE_PKG" >/dev/null 2>&1; then
            rm -f "$STAMP"
            echo "inkdye DISABLED（模块默认状态；请确认 Hook 作用域已勾选并生效）"
            echo "inkdye disabled via action.sh" >>"$LOG"
        else
            echo "disable failed"
        fi
        ;;
    ""|status)
        ;;
    *)
        echo "unknown op: $op"
        ;;
esac

echo "================ pen-bridge.log 末尾 ================"
tail -15 "$LOG" 2>/dev/null
