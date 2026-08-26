#!/usr/bin/env bash
#
# Downloads the Debian 13 (trixie) arm64 rootfs from the official LXC image
# server, verifies SHA256SUMS, and places it at the output path (CI 注入到
# app/src/main/assets/rootfs.tar.xz)。
#
# Usage: fetch_rootfs.sh <output-file> [cache-dir]
# Env:
#   PINNED_BUILD  可选，钉住某构建目录（如 20260822_05:24），默认取最新。
set -euo pipefail

OUT="$1"
CACHE_DIR="${2:-.rootfs-cache}"
ARCH="arm64"
BASE="https://images.linuxcontainers.org/images/debian/trixie/${ARCH}/default"
PINNED_BUILD="${PINNED_BUILD:-}"

mkdir -p "$CACHE_DIR" "$(dirname "$OUT")"

CACHED="$CACHE_DIR/rootfs-${ARCH}.tar.xz"
if [[ -f "$CACHED" ]]; then
  echo "[rootfs] using cached copy for $ARCH"
  cp "$CACHED" "$OUT"
  exit 0
fi

resolve_latest_build() {
  curl -fsSL "$BASE/" | grep -oE '[0-9]{8}_[0-9]{2}:[0-9]{2}/' | sort -u | tail -1 | tr -d '/'
}

BUILD=""
if [[ -n "$PINNED_BUILD" ]] && curl -fsSL -o /dev/null "$BASE/$PINNED_BUILD/rootfs.tar.xz"; then
  BUILD="$PINNED_BUILD"
fi
if [[ -z "$BUILD" ]]; then
  echo "[rootfs] resolving latest build for $ARCH ..."
  BUILD="$(resolve_latest_build)"
fi
[[ -n "$BUILD" ]] || { echo "[rootfs] ERROR: no build resolved" >&2; exit 1; }

URL="$BASE/$BUILD/rootfs.tar.xz"
echo "[rootfs] downloading $URL"
curl -fSL --retry 3 --retry-delay 5 -o "$OUT" "$URL"

SUMS_FILE="$CACHE_DIR/SHA256SUMS-${ARCH}-${BUILD//\:/_}"
curl -fsSL -o "$SUMS_FILE" "$BASE/$BUILD/SHA256SUMS"

EXPECTED=$(grep ' rootfs.tar.xz$' "$SUMS_FILE" | awk '{print $1}' | head -1)
ACTUAL=$(sha256sum "$OUT" | awk '{print $1}')
if [[ -z "$EXPECTED" || "$EXPECTED" != "$ACTUAL" ]]; then
  echo "[rootfs] ERROR: SHA256 mismatch for $ARCH" >&2
  rm -f "$OUT"
  exit 1
fi

cp "$OUT" "$CACHED"
echo "[rootfs] OK: $OUT ($(stat -c %s "$OUT") bytes, build $BUILD)"
