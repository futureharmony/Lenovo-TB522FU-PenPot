#!/system/bin/sh
# KSU 管理器「执行」按钮：状态总览 + inkdye 切换开关
# ------------------------------------------------------------
# 背景：inkdye（com.inkdye.lenovopentocoloros）与本模块的 Hook 功能重叠。
#   * Hook 需要 LSPosed 勾选作用域后才真正生效；
#   * 若 Hook 未生效就禁用 inkdye，触觉反馈等能力会出现空窗。
# 因此 inkdye 的禁用改为用户手动开关（本脚本），开机不再自动禁用。
MODDIR=${0%/*}
LOG="$MODDIR/pen-bridge.log"
INKDYE_PKG=com.inkdye.lenovopentocoloros
STAMP="$MODDIR/inkdye-disabled.state"

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
    echo "inkdye=DISABLED by this module ($(cat "$STAMP"))"
else
    echo "inkdye=ENABLED"
fi

echo "================ 用法 ================"
echo "切换 inkdye:  sh $0 disable|enable|toggle"

op="$1"
case "$op" in
    disable|toggle)
        if [ -f "$STAMP" ] && [ "$op" = toggle ]; then
            op=enable
        elif [ "$op" = toggle ]; then
            op=disable
        fi
        ;;
esac

case "$op" in
    disable)
        if pm disable-user --user 0 "$INKDYE_PKG" >/dev/null 2>&1; then
            echo "disabled at $(date)" >"$STAMP"
            echo "inkdye DISABLED（触觉/通知改由 Hook APK 承担，请确认 LSPosed 作用域已勾选）"
            echo "inkdye disabled via action.sh" >>"$LOG"
        else
            echo "disable failed"
        fi
        ;;
    enable)
        pm enable "$INKDYE_PKG" >/dev/null 2>&1
        rm -f "$STAMP"
        echo "inkdye ENABLED（已交还系统内置笔桥）"
        echo "inkdye enabled via action.sh" >>"$LOG"
        ;;
    ""|status)
        ;;
    *)
        echo "unknown op: $op"
        ;;
esac

echo "================ pen-bridge.log 末尾 ================"
tail -15 "$LOG" 2>/dev/null
