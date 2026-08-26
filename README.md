# DebDroid

在 Android 手机上运行**完整的 Debian 13 (trixie)**，无需 root。基于 [proot](https://proot-me.github.io/)（用户态 chroot），Debian rootfs 内置在 APK 中，**首次安装零下载、全程离线**。

> v2 = 从零重写版：全新代码、干净 git 历史、重做构建/CI 与文档。旧 v1.x 代码仅作参考存档。

## 功能

| 模块 | 说明 |
|---|---|
| 🖥 终端 | 完整 proot Debian 终端：bash/tmux、29 键扩展键、8 套配色、Nerd Font、字号捏合缩放、多会话抽屉 |
| 📁 文件管理器 | 双栏浏览（外部存储 ↔ rootfs）、排序、书签、多选批操作（删除/复制/移动）、权限串与类型图标 |
| 📝 文本编辑器 | 行号、查找替换、未保存三按钮拦截、撤销/重做、只读、状态栏 |
| 🔌 SSH | rootfs 预装 openssh-server：端口/监听/密码/公钥/自启，真实状态与失败原因 |
| 🔋 保活 | 前台服务常驻、唤醒锁、电池白名单、开机自启、会话自动恢复 |

## 安装

- 仅支持 **arm64**（minSdk 26，targetSdk 28）
- 从 [Releases](../../releases) 下载 `DebDroid-*.apk` 安装
- 首启进入安装向导：内置 rootfs 解压约 1–3 分钟（需约 400MB 空闲空间）

## 从源码构建

```bash
# 1. 注入运行时资产（APK 内置镜像；CI 自动执行）
./scripts/fetch_rootfs.sh app/src/main/assets/rootfs.tar.xz   # Debian 13 arm64 rootfs
./scripts/fetch_proot.sh  app/src/main/assets/proot.tar       # Termux proot 运行时

# 2. 签名（Release 需密钥；无密钥时回退 debug 签名）
export KEYSTORE_BASE64="$(base64 -w0 release.keystore)"   # 或 KEYSTORE_FILE=...
export KEYSTORE_PASSWORD=... KEY_ALIAS=... KEY_PASSWORD=...

# 3. 构建
./gradlew testReleaseUnitTest       # 单元测试门禁
./gradlew assembleRelease -PversionCode=1
# 产物：app/build/outputs/apk/release/*.apk
```

## 架构与技术要点

- **proot 免 root**：`proot -r <filesDir>/rootfs -0 -l -L --kill-on-exit ...`，完整参数见 docs/architecture.md §3.3
- **targetSdk 28 是硬约束**：API 29+ 禁止执行应用私有目录中的可执行文件，proot 无法运行
- 技术栈：Kotlin 2.4 + Compose (BOM 2025.09) + DataStore + commons-compress(xz)；终端内核/渲染来自 Termux v0.118.3（Apache-2.0）

## 文档

- [需求规格](docs/requirements.md)（FR 编号 + EARS + 验收标准 + 界面元素表）
- [架构设计](docs/architecture.md)（分层、数据流、proot 参数表、会话生命周期、CI、风险登记）
- [模拟页验收清单](docs/mockup-acceptance.md)
- [变更日志](CHANGELOG.md)
- [第三方组件声明](THIRD-PARTY-NOTICES.md)

## 许可

- terminal-emulator / terminal-view：Apache-2.0（Termux，见 THIRD-PARTY-NOTICES.md）
- Debian rootfs：Debian 项目（LXC 镜像，images.linuxcontainers.org）
- DebDroid 本体代码许可沿用 v1.x 声明（GPL-3.0，LICENSE 文件随发布补全）
