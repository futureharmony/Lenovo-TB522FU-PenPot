#!/system/bin/sh

# The vendor DSI/panel driver owns these nodes. Never write them from the
# Root service or its uninstall hook; this is part of the black-screen fix.

# Stop the real CPS GPIO keeper if this module is removed/disabled.
MODDIR=${0%/*}
PIDFILE="$MODDIR/cps-gpio.pid"
if [ -r "$PIDFILE" ]; then
    pid=$(cat "$PIDFILE" 2>/dev/null)
    case "$pid" in
        ''|*[!0-9]*) ;;
        *) kill "$pid" 2>/dev/null ;;
    esac
    rm -f "$PIDFILE"
fi
[ -x /system/bin/gpioset ] && /system/bin/gpioset gpiochip0 10=0 108=0 >/dev/null 2>&1

# --- 恢复系统内置 inkdye 笔桥（本模块卸载/禁用时交还控制权）---
# 模块默认会禁用 inkdye，所以卸载时**无条件**恢复启用。
INKDYE_PKG=com.inkdye.lenovopentocoloros
pm enable "$INKDYE_PKG" >/dev/null 2>&1 || \
    echo "WARN: failed to re-enable $INKDYE_PKG; run manually: pm enable $INKDYE_PKG" >&2
rm -f "$MODDIR/inkdye-enabled.state" "$MODDIR/inkdye-disabled.state"

# --- 收回充电守护：停进程并把无线发射恢复到开启态（裸数字写法）---
CG_PID="$MODDIR/charge-guard.pid"
if [ -r "$CG_PID" ]; then
    pid=$(cat "$CG_PID" 2>/dev/null)
    case "$pid" in
        ''|*[!0-9]*) ;;
        *) kill "$pid" 2>/dev/null ;;
    esac
    rm -f "$CG_PID"
fi
[ -w /sys/bus/i2c/devices/11-0041/tx_status ] && \
    echo 1 >/sys/bus/i2c/devices/11-0041/tx_status 2>/dev/null

# --- 清掉自保状态：失败计数与 disable 标记 ---
# 否则重新安装时会带着旧的失败计数，可能一开机就被 boot guard 熔断。
rm -f /data/adb/tb522fu_pen_bridge.bootfail 2>/dev/null
rm -f "$MODDIR/disable" "$MODDIR/disable-charge-guard" 2>/dev/null
