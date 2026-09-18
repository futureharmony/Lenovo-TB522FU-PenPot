#!/system/bin/sh
# ============================================================================
# panic.sh — 一键恢复到安全状态
# ----------------------------------------------------------------------------
# 用途：笔桥把系统搞得不正常（触控/蓝牙/设置页异常、开机变慢、无响应）时，
#       只要还能拿到 root shell，就能一条命令把本模块的所有运行时改动收回，
#       并让下次开机跳过本模块。
#
# 用法：
#   su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh'           # 完整恢复并停用模块
#   su -c 'sh /data/adb/modules/tb522fu_pen_bridge/panic.sh --keep'    # 只恢复状态，不禁用模块
#
# 若连 root shell 都没有，改用 KernelSU 安全模式（开机后连按音量-三次）
# 或 ksud：`ksud module disable tb522fu_pen_bridge`。详见
# docs/install-vector-route.md 的「救援」节。
# ============================================================================
MODDIR=${0%/*}
LOG="$MODDIR/pen-bridge.log"
INKDYE_PKG=com.inkdye.lenovopentocoloros
KEEP=0
[ "$1" = "--keep" ] && KEEP=1

echo "== panic: 恢复运行时状态 =="

# 1) 交还系统内置笔桥
if pm enable "$INKDYE_PKG" >/dev/null 2>&1; then
    echo "  inkdye: enabled"
else
    echo "  inkdye: enable FAILED — 手动执行 pm enable $INKDYE_PKG"
fi
rm -f "$MODDIR/inkdye-disabled.state"

# 2) 停掉守护与服务进程
for f in charge-guard.pid service.pid cps-gpio.pid; do
    p="$MODDIR/$f"
    [ -r "$p" ] || continue
    pid=$(cat "$p" 2>/dev/null)
    case "$pid" in
        ''|*[!0-9]*) ;;
        *) kill "$pid" 2>/dev/null && echo "  killed $f (pid $pid)" ;;
    esac
    rm -f "$p"
done

# 3) 恢复无线充电发射（裸数字写法，驱动只认 0/1）
if [ -w /sys/bus/i2c/devices/11-0041/tx_status ]; then
    echo 1 >/sys/bus/i2c/devices/11-0041/tx_status 2>/dev/null && \
        echo "  tx_status: 1"
fi

# 4) 清掉一次性标记，避免下次开机沿用旧的临时状态
rm -f "$MODDIR/pen-hall.state" "$MODDIR/pen-capsule.last" \
      "$MODDIR/pen-connect.last" "$MODDIR/pen-boot-ready" \
      "$MODDIR/disable-charge-guard" 2>/dev/null

# 5) 默认让下次开机跳过本模块（--keep 则保留）
if [ "$KEEP" = "1" ]; then
    echo "  module: 保持启用（--keep）"
else
    : >"$MODDIR/disable"
    echo "  module: 已标记停用（下次开机不加载）"
    echo "          重新启用：删除 $MODDIR/disable 后重启"
fi

# 6) 失败计数也清掉，避免下次开机立刻熔断
rm -f /data/adb/tb522fu_pen_bridge.bootfail 2>/dev/null

echo "== 完成，建议重启 =="
echo "panic invoked via shell" >>"$LOG" 2>/dev/null
