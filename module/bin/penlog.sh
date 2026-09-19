#!/system/bin/sh
# ============================================================================
# penlog.sh — 模块内所有「写文件的日志」共用的限流 helper
# ----------------------------------------------------------------------------
# 为什么需要它：模块目录在 /data/adb 下，没有任何外部轮转（logrotate 之类）。
# service.sh 更是把整个输出 `exec >>"$LOGFILE"` 重定向进去，而它的监控循环
# 每 30s 就在跑 —— 不限流的话日志按天无限长（实测 26 分钟约 11 KB）。
#
# 双上限：
#   PENLOG_MAX_LINES  条数上限（默认 400 行）。超了就保留"最近 N 行"。
#   PENLOG_MAX_BYTES  字节上限（默认 128 KB）。防止某行超长（例如把一整个
#                     dumpsys 片段打印出来）把条数上限绕过去。
#
# ⚠️⚠️ 硬约束：裁剪必须【同 inode 就地回写】，绝对不能 `mv` 后重建同名文件。
# 原因：service.sh 用 `exec >>"$LOGFILE"` 持有该文件的 fd。一旦 inode 被
# unlink（mv 走的正是这条路），后续所有 `echo` 都写进那个已经没人看见的旧
# inode —— 现象是"日志停在裁剪那一刻，之后什么都没了"，而且 `ls` 看到的
# 文件大小永远不变。用 `>` 就地截断再写回则是安全的：fd 是 O_APPEND，
# 下一次写入照旧落在当前文件末尾（即 0），不会产生空洞文件。
#
# 使用方式（调用方只需 source 本文件）：
#   . "$MODDIR/bin/penlog.sh"
#   log() { penlog_append "$LOG" "$*"; }              # 追加 + 自检裁剪
#   penlog_trim "$LOG" 200                            # 手动裁剪（行数可覆盖）
# 每个脚本只有一个日志文件时，直接改 PENLOG_MAX_LINES 即可（见 charge-guard.sh）。
# ============================================================================

PENLOG_MAX_LINES=${PENLOG_MAX_LINES:-400}
PENLOG_MAX_BYTES=${PENLOG_MAX_BYTES:-131072}

# penlog_caps -> "400 行 / 131072 B"（给控制台展示用）
penlog_caps() {
    echo "${PENLOG_MAX_LINES} 行 / ${PENLOG_MAX_BYTES} B"
}

# penlog_trim <file> [max_lines] [max_bytes]
# 仅在超限时动作：保留最近 max_lines 行 + 尾部片段，同 inode 就地回写。
# 未超限时只花一次 `wc`，不做任何写操作（避免每次都 tail 造成写放大与闪存磨损）。
penlog_trim() {
    _plf=$1
    _plines=${2:-$PENLOG_MAX_LINES}
    _pbytes=${3:-$PENLOG_MAX_BYTES}
    [ -f "$_plf" ] || return 0
    # 一次 wc 同时取行数与字节数（stdin 输入时输出 "行 字节"）
    set -- $(wc -l -c <"$_plf" 2>/dev/null)
    _pcur=${1:-}
    _psz=${2:-}
    case "$_pcur" in ''|*[!0-9]*) return 0 ;; esac
    case "$_psz" in ''|*[!0-9]*) _psz=0 ;; esac
    if [ "$_pcur" -le "$_plines" ] && [ "$_psz" -lt "$_pbytes" ]; then
        return 0
    fi
    # 先按条数取"最近 N 行"；若这部分本身就超过字节上限（例如某行是把整个
    # dumpsys 片段打出来的超长行），就逐次对折条数，直到字节也落回上限内
    # （下限 20 行 —— 再少就没有排查价值了，宁可略微超字节）。
    _pkeep=$_plines
    while : ; do
        _pkept=$(tail -n "$_pkeep" "$_plf" 2>/dev/null)
        _pkeptsz=$(printf '%s\n' "$_pkept" | wc -c 2>/dev/null | tr -d ' \t')
        case "$_pkeptsz" in ''|*[!0-9]*) _pkeptsz=0 ;; esac
        [ "$_pkeptsz" -lt "$_pbytes" ] && break
        [ "$_pkeep" -le 20 ] && break
        _pkeep=$((_pkeep / 2))
    done
    {
        echo "[$(date '+%F %T')] log trimmed: ${_pcur} lines/${_psz} B -> keep last ${_pkeep} lines"
        [ -n "$_pkept" ] && printf '%s\n' "$_pkept"
    } >"$_plf"
}

# penlog_append <file> <msg...>
# 追加一行（自带时间戳），随后做一次限流自检。
penlog_append() {
    _plf=$1
    shift
    printf '[%s] %s\n' "$(date '+%F %T')" "$*" >>"$_plf"
    penlog_trim "$_plf"
}
