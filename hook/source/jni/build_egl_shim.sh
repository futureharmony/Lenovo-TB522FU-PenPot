#!/usr/bin/env bash
# Build libeglshim.so (arm64-v8a) for the pen-bridge LSPosed module.
#
# Prereqs:
#   * Android NDK (ndk-build / cmake from the NDK). Point ANDROID_NDK to it,
#     or let $ANDROID_HOME/ndk/<ver> be picked up automatically.
#   * Network once, to clone Dobby (pinned). After that the clone is cached.
#
# Output: ../resources/lib/arm64-v8a/libeglshim.so  (picked up by
#         hook/tools/build_hook_source.py and embedded into the APK).
#
# This script does NOT run on the porting Mac if NDK is absent -- build it on
# any machine with the NDK, then drop the .so into the repo (or let the usual
# release flow carry it).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
OUT="$DIR/../resources/lib/arm64-v8a"
DOBBY_DIR="$DIR/Dobby"
# PIN: Dobby master HEAD is currently broken twice over:
#   1. circular include (platform.h <-> common.h) -> OSMemory undeclared;
#   2. external/logging/logging.c includes <android/log.h> INSIDE a function
#      body, which NDK >= r26 rejects (android/log.h now has static inline
#      functions).
# 67d155b (2022-12-12) builds clean with NDK r30 once logging.c is patched
# (the patch is already applied to the vendored copy under jni/Dobby -- keep
# it!). If you delete the vendored dir, this script re-fetches the pinned
# commit but you must re-apply the logging.c patch by hand.
DOBBY_COMMIT="${DOBBY_COMMIT:-67d155b}"

# --- locate NDK ---
NDK="${ANDROID_NDK:-}"
if [ -z "$NDK" ]; then
  for CAND in "$HOME/Library/Android/sdk" "${ANDROID_HOME:-/nonexistent}"; do
    NDK="$(ls -d "$CAND"/ndk/* 2>/dev/null | sort -r | head -1)"
    [ -n "$NDK" ] && break
  done
fi
if [ -z "$NDK" ] || [ ! -d "$NDK" ]; then
  echo "ANDROID_NDK not found. Set ANDROID_NDK or install under ~/Library/Android/sdk/ndk/." >&2
  exit 1
fi
echo "NDK = $NDK"

# homebrew tools (cmake) are often missing from non-interactive PATH.
case ":$PATH:" in
  *":/opt/homebrew/bin:"*) ;;
  *) [ -d /opt/homebrew/bin ] && PATH="/opt/homebrew/bin:$PATH" ;;
esac

# --- vendor Dobby (once). The vendored, patched copy in the repo is
# authoritative; only fetch if it is missing entirely. ---
if [ ! -f "$DOBBY_DIR/CMakeLists.txt" ]; then
  echo "== fetching pinned Dobby ${DOBBY_COMMIT} =="
  rm -rf "$DOBBY_DIR"
  git clone --depth 1 https://github.com/jmpews/Dobby.git "$DOBBY_DIR"
  # GitHub rejects shallow fetches of arbitrary SHAs more often than not, so
  # fall back to fetching the commit out of full history.
  if ! git -C "$DOBBY_DIR" fetch --depth 1 origin "${DOBBY_COMMIT}" 2>/dev/null; then
    git -C "$DOBBY_DIR" fetch --unshallow origin master 2>/dev/null \
      || git -C "$DOBBY_DIR" fetch origin master 2>/dev/null || true
  fi
  git -C "$DOBBY_DIR" checkout --force "${DOBBY_COMMIT}" 2>/dev/null \
    || echo "[warn] could not pin ${DOBBY_COMMIT}; using default HEAD -- verify the logging.c patch!"
  # Re-apply the NDK>=r26 logging fix if this is a fresh (unpatched) clone.
  if grep -q '#include <android/log.h>' "$DOBBY_DIR/external/logging/logging.c" >/dev/null 2>&1 \
     && ! grep -q 'Moved to file scope' "$DOBBY_DIR/external/logging/logging.c"; then
    python3 - "$DOBBY_DIR/external/logging/logging.c" <<'EOF'
import sys
p = sys.argv[1]
s = open(p).read()
s = s.replace('''#if defined(__ANDROID__)
#define ANDROID_LOG_TAG "Dobby"
#include <android/log.h>
    __android_log_vprint''', '''#if defined(__ANDROID__)
#define ANDROID_LOG_TAG "Dobby"
    __android_log_vprint''')
marker = '#include <time.h>'
fix = marker + '''

/* NDK >= r26: android/log.h contains static inline functions and can no
 * longer be included inside a function body (upstream Dobby includes it in
 * the middle of DobbyLogVAG). Moved to file scope. */
#if defined(__ANDROID__)
#include <android/log.h>
#endif'''
s = s.replace(marker, fix, 1)
open(p, 'w').write(s)
print("applied logging.c patch")
EOF
  fi
fi

# --- configure + build with the NDK toolchain ---
BUILD="$DIR/build"
rm -rf "$BUILD"
cmake -S "$DIR" -B "$BUILD" \
  -DCMAKE_TOOLCHAIN_FILE="$NDK/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a \
  -DANDROID_PLATFORM=android-31 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build "$BUILD" -j"$(sysctl -n hw.ncpu 2>/dev/null || echo 4)"

mkdir -p "$OUT"
STRIP="$(dirname "$(find "$NDK/toolchains" -name llvm-strip | head -1)")/llvm-strip"
if [ -x "$STRIP" ]; then
  "$STRIP" -o "$OUT/libeglshim.so" "$BUILD/libeglshim.so"
else
  cp "$BUILD/libeglshim.so" "$OUT/libeglshim.so"
fi
echo "==> wrote $OUT/libeglshim.so"
ls -la "$OUT/libeglshim.so"
