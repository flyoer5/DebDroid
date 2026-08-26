# Changelog

All notable changes to DebDroid. 版本与功能编号对应 docs/requirements.md 的 FR 编号。

## [2.0.0] — 从零重写版（首个重写 Release）

这是对 v1.x 的**推倒重写**：全新目录、干净 git 历史、重写全部代码与 CI。功能清单与 v1.0.29 对齐，同时修复 v1.x 的四大痛点（构建/CI、代码结构、git 历史、文档）。

### 新增 / 改进

- **全新工程结构**（解决"代码混乱"）
  - 单一 app 模块 + 两个上游库模块（terminal-emulator / terminal-view，Termux v0.118.3），不再有 arm64/armhf 双 flavor
  - 领域层/会话层/UI 层按架构文档分层（docs/architecture.md §1.2），单测覆盖纯函数层
  - CI 单元测试门禁：测试失败即红，禁止发布（FR-Q1）

- **构建与 CI 重做**（解决"构建/CI 混乱"）
  - 单 arm64 产物（不再误装 32 位），minSdk 26 / targetSdk 28 / compileSdk 36
  - GitHub Actions：测试 → 注入 rootfs/proot → 签名 Release 构建 → tag 自动发 Release
  - 新签名密钥（PKCS12，alias debdroid），密钥仅存 GitHub Secrets（KEYSTORE_BASE64）

- **文档补齐**（解决"文档落后"）
  - docs/requirements.md：完整需求规格（FR-W/T/S/F/E/H/K/C/Q + EARS + 验收标准 + 界面元素表 + 设置字段表）
  - docs/architecture.md：分层、数据流、proot 参数/env 全表、会话生命周期、保活、SSH、文件管理器、编辑器、CI、测试策略、风险登记
  - docs/mockup-acceptance.md：模拟页逐 FR 验收核对清单
  - 本 CHANGELOG + README

### 功能（与 v1.0.29 对齐）

- **proot Debian 13 (trixie) 终端**：免 root、镜像内置零下载、离线安装（FR-W/T）
  - `-l -L --kill-on-exit`、`PROOT_F2FS_WORKAROUND=1` 强制、宿主 `/tmp`+`/root/.npm` 绑定 filesDir（非 cacheDir）
  - seccomp 快路径保留（不设 PROOT_NO_SECCOMP）
- **文件管理器**：双栏、排序、书签、批操作（复制/移动/删除）、权限串与类型图标（FR-F）
- **文本编辑器**：行号、查找替换、未保存三按钮拦截、撤销/重做、只读、状态栏（FR-E）
- **SSH 服务器**：rootfs 预装 openssh-server，即开即用；端口/监听/密码/公钥/自启；真实失败原因透传；端口释放等待 ≤3s（FR-H）
- **保活**：前台服务 + 唤醒锁 + 电池白名单 + 开机自启 + 会话自动恢复（FR-K）

### 修复（相对 v1.x 的已知问题）

- 会话并发建会话死锁：Mutex 只在 ensureSession 顶层获取（不可重入），结构性防回归
- 安装进度回调无节流：≤100 次/s（v1.0.17）
- TerminalSession 构造线程：必须在主线程（Looper）
- rootfs 资产必须以纯 tar.xz 存在、proot.tar 纯 tar（AAPT2 解压 *.gz 的坑）
- 设置并发写丢失：DataStore 读-改-写收敛到单个 edit{} 事务

### 已知限制

- targetSdk 28 为硬约束（API 29+ 禁止执行应用私有目录可执行文件）
- 仅 arm64（v2 决策 T-04）
- 镜像构建期已预装 tmux/openssh-server（CI qemu chroot）；若使用未预装的自制镜像，SSH 首启需联网 apt 安装

## [1.0.29] — v1.x 最终版（仅存档，不再维护）

历史版本，见旧仓库 git log。本重写版不继承其提交历史。
