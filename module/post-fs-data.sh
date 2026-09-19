#!/system/bin/sh

# The service writes the vendor pen-wakeup proc nodes once after boot/CPS is
# ready; this early stage only pins the LSPosed hook path.
MODDIR=${0%/*}

# ============================================================================
# 启动失败自保（boot guard）
# ----------------------------------------------------------------------------
# 本脚本运行在 post-fs-data 阶段，会【阻塞开机】。它是全模块唯一可能把设备
# 拖死在开机动画的地方（service.sh 在 late_start 异步跑，卡不住开机）。
#
# 两级自保：
#   1) 计数：每次进入本脚本先 +1 记账。只要系统没能真正启动完成
#      （sys.boot_completed=1），计数就一路累加。
#   2) 熔断：连续超过 3 次未启动完成 → 生成 $MODDIR/disable，让 KernelSU
#      下次开机直接跳过本模块；本次也立刻退出，不再执行任何操作。
# 计数清零由 service.sh 在确认 boot_completed 之后执行（见 service.sh）。
# 计数文件放在 /data/adb 顶层而非模块目录内，避免模块未挂载时读不到。
# ============================================================================
BOOT_GUARD_FILE=/data/adb/tb522fu_pen_bridge.bootfail
BOOT_GUARD_MAX=3

fail=$(cat "$BOOT_GUARD_FILE" 2>/dev/null)
case "$fail" in
    ''|*[!0-9]*) fail=0 ;;
esac
fail=$((fail + 1))
echo "$fail" >"$BOOT_GUARD_FILE" 2>/dev/null

if [ "$fail" -gt "$BOOT_GUARD_MAX" ]; then
    : >"$MODDIR/disable" 2>/dev/null
    pm enable com.inkdye.lenovopentocoloros >/dev/null 2>&1
    rm -f "$MODDIR/inkdye-enabled.state" "$MODDIR/inkdye-disabled.state" \
          "$MODDIR/charge-guard.pid" "$MODDIR/service.pid" 2>/dev/null
    [ -w /sys/bus/i2c/devices/11-0041/tx_status ] && \
        echo 1 >/sys/bus/i2c/devices/11-0041/tx_status 2>/dev/null
    echo "[$(date '+%F %T')] BOOT GUARD tripped after $fail failed boots; module disabled" \
        >>"$MODDIR/pen-bridge.log" 2>/dev/null
    exit 0
fi

# 有超时上限地运行一条命令。toybox 一般带 timeout；没有就用后台 + 轮询兜底。
# 目的：任何一次 app_process 卡死都不能把 post-fs-data 拖成死机。
run_bounded() {
    _secs="$1"; shift
    if command -v timeout >/dev/null 2>&1; then
        timeout "$_secs" "$@"
        return $?
    fi
    "$@" &
    _pid=$!
    _n=0
    while [ "$_n" -lt "$_secs" ]; do
        kill -0 "$_pid" 2>/dev/null || { wait "$_pid"; return $?; }
        sleep 1
        _n=$((_n + 1))
    done
    kill -9 "$_pid" 2>/dev/null
    wait "$_pid" 2>/dev/null
    return 124
}

# LSPosed-style path pinning is DISABLED for Vector.
#
# /data/adb/lspd/config/modules_config.db is Vector's live database (Vector
# reuses the lspd path; the -wal/-shm mtimes move whenever vector-cli runs).
# LsposedPathSync was written against the LSPosed schema (API 93/100) and
# Vector is API 102 — writing to it risks corrupting Vector's module table.
# Vector enables modules and scopes through its own daemon + CLI, which
# replaces this entire block:
#   /data/adb/modules/zygisk_vector/cli modules enable com.futureharmony.lenovopenbridge
#   /data/adb/modules/zygisk_vector/cli scope set com.futureharmony.lenovopenbridge \
#       android/0 com.coloros.note/0 ... (see docs/install-vector-route.md)
# Only re-enable this block if the device actually runs LSPosed again.
LSP_DB=/data/adb/lspd/config/modules_config.db
LSP_APK="$MODDIR/hook/PenBridge-Hook.apk"
LSP_SYNC="$MODDIR/bin/lsposed-path-sync.jar"
# Disabled on Vector (see comment above). Kept behind an explicit opt-in so
# restoring LSPosed only needs `touch $MODDIR/enable-lsposed-path-sync`.
if [ -f "$MODDIR/enable-lsposed-path-sync" ] &&
        [ -f "$LSP_DB" ] && [ -f "$LSP_APK" ] && [ -f "$LSP_SYNC" ]; then
    chown 0:0 "$LSP_APK" "$LSP_SYNC" 2>/dev/null
    chmod 0644 "$LSP_APK" "$LSP_SYNC" 2>/dev/null
    chcon u:object_r:system_file:s0 "$LSP_APK" "$LSP_SYNC" 2>/dev/null
    sync_attempt=0
    while [ "$sync_attempt" -lt 2 ]; do
        if run_bounded 20 env CLASSPATH="$LSP_SYNC" app_process /system/bin \
                com.aclaniakea.tools.LsposedPathSync "$LSP_DB" "$LSP_APK" \
                com.futureharmony.lenovopenbridge \
                system com.coloros.note com.oplus.exsystemservice \
                com.heytap.mydevices com.oplus.ipemanager \
                com.oplus.wirelesssettings com.oplus.screenshot com.coloros.translate >/dev/null 2>&1; then
            break
        fi
        sync_attempt=$((sync_attempt + 1))
        sleep 1
    done
fi

# 刷新率配置（refresh_rate_config.xml）的 bind 已迁回 fix 模块。
# 它是整机显示基线，与笔无关：笔在用时的 120Hz 由原厂
# OplusRefreshRatePolicyImpl 依 settings_enable_oppo_pencil 自行投票，
# 本模块只负责把那个键写对。放在这里曾导致 ratemagic 被误删 144，
# 面板被长期钉在 60Hz——详见 fix-module/module/post-fs-data.sh 里的说明。

# 便签引擎钉版守卫（防 HeyTap 静默自更新后涂鸦崩溃/不渲染复发）。
# 详见 docs/sunia_recurrence_root_cause_20260918.md。
# 用 run_bounded 包裹，避免任何 app_process/md5sum 意外拖死开机。
if [ -f "$MODDIR/guard_note_engine.sh" ]; then
    chmod 0755 "$MODDIR/guard_note_engine.sh" 2>/dev/null
    run_bounded 30 "$MODDIR/guard_note_engine.sh"
fi

exit 0
