#!/usr/bin/env bash
#
# Downloads Termux 维护的 proot 构建与其运行库（aarch64/arm64），打包为 proot.tar
# （plain tar，CI 注入到 app/src/main/assets/proot.tar）。
#
# NOTE: 故意不 gzip——AAPT2 会静默解压 *.gz 资产并丢后缀（v1.0.x 教训），
# 应用侧按纯 tar 解包。
#
# Usage: fetch_proot.sh <out-tarball>
set -euo pipefail

OUT="$1"
ARCH="aarch64"
BASE="https://packages.termux.dev/apt/termux-main/pool/main"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

ROOT="$WORK/root"
mkdir -p "$ROOT/bin" "$ROOT/libexec/proot" "$ROOT/lib"

# download <pool-subdir> <name-prefix> — 下载 .deb 并解出 termux usr 目录
download() {
  local dir="$BASE/$1" prefix="$2" name
  name="$(curl -fsSL "$dir/" | grep -oE 'href="[^"]*'"$ARCH"'\.deb"' | cut -d'"' -f2 | grep "^$2" | sort -V | tail -1)"
  [ -n "$name" ] || { echo "[proot] ERROR: no $prefix deb for $ARCH" >&2; exit 1; }
  echo "[proot] $ARCH: $name" >&2
  curl -fsSL -o "$WORK/pkg.deb" "$dir/$name"
  rm -rf "$WORK/x"
  mkdir -p "$WORK/x/ar" "$WORK/x/data"
  (cd "$WORK/x/ar" && ar x "$WORK/pkg.deb")
  local data_tar
  data_tar="$(ls "$WORK/x/ar"/data.tar.* | head -1)"
  [ -n "$data_tar" ] || { echo "[proot] ERROR: no data.tar in $name" >&2; exit 1; }
  tar -xaf "$data_tar" -C "$WORK/x/data"
  local usr_dir
  usr_dir="$(find "$WORK/x/data" -type d -path '*com.termux/files/usr' | head -1)"
  [ -n "$usr_dir" ] || { echo "[proot] ERROR: termux usr dir not found in $name" >&2; exit 1; }
  echo "$usr_dir"
}

USR="$(download p/proot proot_)"
install -m 755 "$USR/bin/proot" "$ROOT/bin/proot"
for l in loader loader32; do
  [ -f "$USR/libexec/proot/$l" ] && install -m 755 "$USR/libexec/proot/$l" "$ROOT/libexec/proot/$l" || true
done

USR="$(download libt/libtalloc libtalloc_)"
for f in "$USR"/lib/libtalloc.so.*; do
  [ -e "$f" ] && cp -a "$f" "$ROOT/lib/" || true
done

USR="$(download liba/libandroid-shmem libandroid-shmem_)"
for f in "$USR"/lib/libandroid-shmem.so*; do
  [ -e "$f" ] && cp -a "$f" "$ROOT/lib/" || true
done

echo "[proot] $ARCH bundle contents:"
find "$ROOT" -type f | sort

tar -cf "$OUT" -C "$ROOT" .
echo "[proot] OK: $OUT ($(stat -c %s "$OUT") bytes)"
