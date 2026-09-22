#!/system/bin/sh
# ============================================================================
# 卸载脚本（v0.1.17 减法版）
# ----------------------------------------------------------------------------
# v0.1.17 起本模块不再触碰任何充电/TX/GPIO/唤醒节点，卸载时没有硬件状态
# 需要恢复——只需交还 inkdye、清自保标记、卸配套 Hook APK。
# The vendor DSI/panel driver owns these nodes. Never write them from the
# Root service or its uninstall hook; this is part of the black-screen fix.
# ============================================================================

MODDIR=${0%/*}

# --- 恢复系统内置 inkdye 笔桥（本模块卸载/禁用时交还控制权）---
# 模块默认会禁用 inkdye，所以卸载时**无条件**恢复启用。
INKDYE_PKG=com.inkdye.lenovopentocoloros
pm enable "$INKDYE_PKG" >/dev/null 2>&1 || \
    echo "WARN: failed to re-enable $INKDYE_PKG; run manually: pm enable $INKDYE_PKG" >&2
rm -f "$MODDIR/inkdye-enabled.state" "$MODDIR/inkdye-disabled.state"
# v0.1.23 起深睡唤醒守护已删除，卸载时清掉历史遗留的状态文件即可。
rm -f "$MODDIR/pen-wake-guard.state" "$MODDIR/pen-wake-arm.state" \
      "$MODDIR/pen-wake.last" "$MODDIR/pen-wake-now" \
      "$MODDIR/pen-hid-permissions.ready" "$MODDIR/pen-hid-launcher.hidden" 2>/dev/null

# --- 清掉自保状态：失败计数与 disable 标记 ---
# 否则重新安装时会带着旧的失败计数，可能一开机就被 boot guard 熔断。
rm -f /data/adb/tb522fu_pen_bridge.bootfail 2>/dev/null
rm -f "$MODDIR/disable" 2>/dev/null

# --- 同步卸载配套 LSPosed Hook APK ---
pm uninstall com.futureharmony.lenovopenbridge >/dev/null 2>&1
pm uninstall com.aclaniakea.lenovopenbridge >/dev/null 2>&1

# --- 移除 HID 控制器 PenHidCtl（随 v0.1.23 深睡唤醒一起下线）---
# 该包可能以 priv-app overlay 或 `pm install -r` 的 UPDATED_SYSTEM_APP 形式存在，
# 两处都要卸，否则 PMS 会指向已不存在的 priv-app 路径。
pm uninstall com.aclaniakea.penhidctl >/dev/null 2>&1
