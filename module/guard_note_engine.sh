#!/system/bin/sh
# guard_note_engine.sh — com.coloros.note 引擎钉版 / 自愈守卫
#
# 运行位置：KSU 模块 post-fs-data.sh（root，开机早期）
# 目的：让便签在"移植 ROM + 可能自更新"的环境下，引擎始终落在已知良好构建，
#       且对未知（未来升级）引擎显式告警而非静默坏掉。
#
# 安全原则：
#   - 仅在 app == 16.7.2 时覆盖为"已修 16.7.2 引擎"（md5 db61d1ff…）；
#   - app != 16.7.2（未来升级，ABI 可能变）时【不】盲目打补丁，写 WARN 并尽量锁自更新；
#   - 任何未知引擎都不强行覆盖，避免"不渲染"类错配被掩盖。
#
# 依赖：toybox md5sum / pm / dumpsys（系统自带）。需 root。

MODDIR="${0%/*}"
NOTE_PKG="com.coloros.note"
ENGINE_FIXED="$MODDIR/engine/libSuniaEngine.16.7.2.fixed.so"   # 已修 16.7.2 引擎（随模块分发）
LOG="/data/local/tmp/note_engine_guard.log"
WARN_MARK="/data/local/tmp/note_engine_guard.UNKNOWN"

# 已知 md5（小写，toybox md5sum 输出）
MD5_FIXED_16_7_2="db61d1ffdd8c25062920d0a25c697af3"   # 已修 16.7.2（目标态）
MD5_FALLBACK_16_6_14="1b0481baf9badda6734d94ef836ebac1" # 16.6.14（不渲染，但可用）
MD5_CRASH_16_7_2="42fb5fd53e04368ca42d255579075d96"    # 16.7.2 原版（崩溃）

log() { echo "$(date '+%m-%d %H:%M:%S') [$NOTE_PKG] $*" >> "$LOG"; }

[ -w "$LOG" ] || : > "$LOG"
log "=== guard start ==="

# 1) 定位已安装引擎路径
ENGINE_PATH="$(pm path "$NOTE_PKG" 2>/dev/null | head -n1 | sed 's/^package://; s#base.apk#lib/arm64/libSuniaEngine.so#')"
if [ -z "$ENGINE_PATH" ] || [ ! -f "$ENGINE_PATH" ]; then
  log "engine path not found (app not installed / not extracted yet) -> skip"
  exit 0
fi

# 2) 取 app versionCode
APP_VER="$(dumpsys package "$NOTE_PKG" 2>/dev/null | grep -m1 'versionCode=' | sed 's/.*versionCode=//; s/ .*//')"
log "app versionCode=$APP_VER engine=$ENGINE_PATH"

# 3) 计算引擎 md5
ENGINE_MD5="$(md5sum "$ENGINE_PATH" 2>/dev/null | awk '{print $1}')"
log "engine md5=$ENGINE_MD5"

# 4) 决策
if [ "$APP_VER" != "160070002" ]; then
  # 未来升级：ABI 可能变，禁止盲目覆盖
  log "WARN: app version ($APP_VER) != 16.7.2 -> 未知引擎，不覆盖。请人工评估新引擎 ABI。"
  : > "$WARN_MARK"
  # 尽量阻断 HeyTap 自更新，防止进一步覆盖
  if pm disable-user com.heytap.market >/dev/null 2>&1; then
    log "已禁用 com.heytap.market 以阻止继续自更新"
  fi
  exit 0
fi

rm -f "$WARN_MARK"

# app == 16.7.2：把引擎钉回"已修 16.7.2"（覆盖崩溃版与 16.6.14 不渲染版）
if [ "$ENGINE_MD5" = "$MD5_FIXED_16_7_2" ]; then
  log "OK: 引擎已是已修 16.7.2，无需动作"
  exit 0
fi

if [ ! -f "$ENGINE_FIXED" ]; then
  log "ERROR: 模块内缺少已修引擎 $ENGINE_FIXED，无法钉版（保留现状）"
  : > "$WARN_MARK"
  exit 1
fi

# 备份当前（无论崩溃版还是 16.6.14），再覆盖
cp "$ENGINE_PATH" "${ENGINE_PATH}.guard.bak.$(date '+%H%M%S')" 2>/dev/null
cp "$ENGINE_FIXED" "$ENGINE_PATH"
chown system:system "$ENGINE_PATH" 2>/dev/null
chcon u:object_r:apk_data_file:s0 "$ENGINE_PATH" 2>/dev/null
restorecon "$ENGINE_PATH" 2>/dev/null

NEW_MD5="$(md5sum "$ENGINE_PATH" 2>/dev/null | awk '{print $1}')"
if [ "$NEW_MD5" = "$MD5_FIXED_16_7_2" ]; then
  log "RE-PINNED: 引擎已覆盖为已修 16.7.2 (was $ENGINE_MD5)"
else
  log "ERROR: 覆盖后 md5=$NEW_MD5 不符预期，请检查"
fi

log "=== guard done ==="
