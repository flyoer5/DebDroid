#!/usr/bin/env bash
#
# 在 x86_64 CI runner 上用 qemu-user-static chroot 向 arm64 rootfs 预装
# tmux + openssh-server（FR-S3 / FR-H1：即开即用，无需首启联网 apt）。
#
# LXC 官方镜像不含这两个包；本脚本把它们写回 rootfs.tar.xz 再打包。
# resolv.conf 在 chroot 内临时使用、装完删除——App 运行时由 ProotLauncher
# 把应用私有 resolv.conf 经 -b 绑定进 /etc/resolv.conf，镜像内无需保留。
#
# Usage: provision_rootfs.sh <rootfs.tar.xz>
set -euo pipefail

ROOTFS_XZ="$1"
WORK="$(mktemp -d)"
# chroot 内 root 创建的文件宿主侧归 root 所有，普通用户删不掉 → sudo 清理
trap 'sudo rm -rf "$WORK"' EXIT

echo "[provision] installing qemu-user-static + binfmt ..."
sudo apt-get install -y -qq qemu-user-static binfmt-support >/dev/null
sudo update-binfmts --enable qemu-aarch64 >/dev/null 2>&1 || true

echo "[provision] extracting rootfs ..."
mkdir -p "$WORK/rfs"
tar -xJf "$ROOTFS_XZ" -C "$WORK/rfs"

# chroot 内 apt 需要 DNS（runner 的 resolv.conf 是 symlink，直接写静态文件更稳）
# rootfs 的 /etc/resolv.conf 常为指向 /run 的绝对路径 symlink——先删链接再写普通文件
sudo rm -f "$WORK/rfs/etc/resolv.conf"
sudo sh -c "printf 'nameserver 8.8.8.8\\nnameserver 223.5.5.5\\n' > '$WORK/rfs/etc/resolv.conf'"

echo "[provision] resolv.conf inside rootfs:"
sudo cat "$WORK/rfs/etc/resolv.conf"

echo "[provision] apt install tmux + openssh-server (qemu) ..."
sudo chroot "$WORK/rfs" /usr/bin/env -i \
  HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin DEBIAN_FRONTEND=noninteractive \
  bash -c 'apt-get update -qq && apt-get install -y -qq --no-install-recommends tmux openssh-server && \
    rm -rf /var/lib/apt/lists/* /etc/ssh/ssh_host_* && \
    rm -rf /usr/share/doc /usr/share/man /usr/share/info /var/cache/apt/archives && \
    find /usr/share/locale -mindepth 1 -maxdepth 1 ! -name "en*" ! -name "locale.alias" -exec rm -rf {} +'

sudo rm -f "$WORK/rfs/etc/resolv.conf"

echo "[provision] rootfs 解压后大小: $(sudo du -sh "$WORK/rfs" | cut -f1)"

echo "[provision] repacking rootfs.tar.xz ..."
# chroot 内 root 创建的文件宿主侧归 root：sudo 打包，产物交还 runner 所有
sudo tar -cJf "$ROOTFS_XZ" -C "$WORK/rfs" .
sudo chown "$(id -u):$(id -g)" "$ROOTFS_XZ"

echo "[provision] OK: tmux + openssh-server 已写入 $ROOTFS_XZ ($(stat -c %s "$ROOTFS_XZ") bytes)"
