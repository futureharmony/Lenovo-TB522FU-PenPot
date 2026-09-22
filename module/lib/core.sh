#!/system/bin/sh
# ============================================================================
# lib/core.sh — 基础设施：日志 / settings 读写 / 不 fork 的等待
# ----------------------------------------------------------------------------
# 本文件只定义函数，不含任何执行语句（source 进来即可用）。
#
# 为什么要有这一层：service.sh 原先在 42 处手写
#   $(settings get global X 2>/dev/null | tr -d '\r')
# 和 38 处手写
#   settings put global X v >/dev/null 2>&1
# 以及 51 处手写
#   echo "[$(date '+%F %T')] ..."
# 这些管道和重定向不是业务逻辑，只是访问 SettingsProvider 的语法噪音。
# 收成 helper 后，业务判断才读得出来。
#
# ⚠️ 两条硬约束：
#   1) log() 写 stdout；被 $(...) 命令替换捕获的纯读取函数（read_hall_state /
#      read_hardware_battery / resolve_pen_mac / bt_stack_hogp_live 等）里
#      **绝不能**调 log()，否则日志会混进返回值。这类位置改用 log_err()
#      —— 它是 >&2，而 service.sh 已经把 stderr 一起重定向进同一个日志文件。
#   2) 库文件里不得出现 exit。**唯一例外是 sleep_sec 末尾那个出口**：它在
#      「找不到任何可用 sleep 后端」时主动停服务以避免 100% CPU 忙等，本来
#      就必须退掉 service.sh 主进程 —— 在 source 进来的文件里 exit 同样作用
#      于当前进程，语义与原实现一致，故原样保留、不做包装。
# ============================================================================

log() {
    # 每次调用 fork 一次 date —— 与原实现（echo "[$(date ...)]"）开销完全一致。
    echo "[$(date '+%F %T')] $*"
}

log_err() {
    # 供 $(...) 内部的读取函数写日志用：不进 stdout，不会污染命令替换结果。
    echo "[$(date '+%F %T')] $*" >&2
}

get_global() {
    settings get global "$1" 2>/dev/null | tr -d '\r'
}

put_global() {
    settings put global "$1" "$2" >/dev/null 2>&1
}

put_global_diff() {
    # 只在值真的变化时写。SettingsProvider 写入会唤醒 IPeManager 与设置页监听，
    # 高频监视循环里必须去抖（原实现是手写的 current=/[ ... ] || put 三元组）。
    [ "$(get_global "$1")" = "$2" ] || put_global "$1" "$2"
}

resolve_cps_nodes() {
    for dir in /sys/bus/i2c/devices/*-0041; do
        [ -d "$dir" ] || continue
        [ -r "$dir/tx_status" ] || continue
        CPS_TX_STATUS="$dir/tx_status"
        [ -r "$dir/uevent" ] && CPS_UEVENT="$dir/uevent"
        break
    done
    [ -r /sys/class/power_supply/cps_wls_tx/online ] && \
        CPS_PS_ONLINE=/sys/class/power_supply/cps_wls_tx/online
}

trim_log_if_large() {
    # 条数（400 行）+ 字节（512 KB）双上限，超限保留最近行而不是整file清空：
    # 控制台只显示尾部片段，清空等于把现场丢干净，下次报障无据可查。
    penlog_trim "$LOGFILE" "$LOG_MAX_LINES" "$LOG_MAX_BYTES"
}

# 不 fork 的等待。
#
# 每次调用外部 sleep 都是一次 fork+exec，而本服务有 6 处 1 秒轮询、3 处 2 秒
# 轮询，外加若干子 shell。实测代价：笔桥接运行时全系统 26 forks/s，停掉它只剩
# 8 forks/s——**它一个模块占了全系统进程创建的 69%**，而且笔没在使用时照跑不误
# （10 个常驻 shell、合计 RSS 7.7 MB、持续约 1% CPU）。
#
# read 是 shell 内建命令。把一个只读不写的 fifo 以读写方式常开在 fd 8 上，
# `read -t N -u 8` 就会阻塞到超时再返回，全程不创建进程。fifo 必须用 <> 打开，
# 否则没有写端时 read 会立刻拿到 EOF 而不是等待。
#
# fd 用 8：9 已被上面的 SERVICE_LOCK 占用。
# 自检失败（shell 不支持 read -t、fifo 建不出来、或立刻返回而不是等待）时
# 原样回退到外部 sleep，行为与改动前完全一致。

nap_init() {
    [ -p "$NAP_FIFO" ] || {
        rm -f "$NAP_FIFO" 2>/dev/null
        mknod "$NAP_FIFO" p 2>/dev/null || mkfifo "$NAP_FIFO" 2>/dev/null
    }
    [ -p "$NAP_FIFO" ] || return 1
    exec 8<>"$NAP_FIFO" 2>/dev/null || return 1
    # 自检：请求等 0.3 秒，必须真的耗掉 >=200ms。若 shell 不支持 -t 或直接
    # 拿到 EOF 就会立刻返回，那种情况下用它会把轮询变成忙等，必须回退。
    _nap_t0=$(date +%s%N 2>/dev/null) || return 1
    read -t 0.3 -u 8 _nap_discard 2>/dev/null
    _nap_t1=$(date +%s%N 2>/dev/null) || return 1
    [ $(( (_nap_t1 - _nap_t0) / 1000000 )) -ge 200 ] || return 1
    NAP_READY=1
    return 0
}

sleep_sec() {
    if [ "$NAP_READY" = 1 ]; then
        read -t "$1" -u 8 _nap_discard 2>/dev/null
        return 0
    fi
    # Use the root runtime's BusyBox first.  On this ROM /system/bin/toybox can
    # appear executable while its mount namespace is still settling; invoking it
    # then fails and a polling loop can spin at 100% CPU.
    for sleep_backend in \
            /data/adb/ksu/bin/busybox \
            /data/adb/magisk/busybox \
            /system/bin/toybox; do
        [ -x "$sleep_backend" ] || continue
        if "$sleep_backend" sleep "$1" >/dev/null 2>&1; then
            return 0
        fi
    done
    log "no working sleep backend; stopping service to avoid a busy loop"
    exit 0
}
