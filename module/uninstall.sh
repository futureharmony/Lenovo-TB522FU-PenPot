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
INKDYE_PKG=com.inkdye.lenovopentocoloros
INKDYE_STATE="$MODDIR/inkdye-disabled.state"
if [ -f "$INKDYE_STATE" ]; then
    pm enable "$INKDYE_PKG" >/dev/null 2>&1 || \
        echo "WARN: failed to re-enable $INKDYE_PKG; run manually: pm enable $INKDYE_PKG" >&2
    rm -f "$INKDYE_STATE"
fi
