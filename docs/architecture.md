# DebDroid v2 — 技术方案（Architecture Design）

> 版本：2.0.0-draft · 配套文档：docs/requirements.md（需求规格）、mockup/index.html（界面）
> 本文档回答"怎么做"：架构分层、数据流、关键流程详细设计、CI 流水线、测试策略。
> 照本文档可直接写出全部代码而不再需要设计决策。

---

## 1. 架构总览

### 1.1 分层

```
┌──────────────────────────────────────────────────────────┐
│ UI 层（Compose · Material3 · 深色主题）                     │
│   WizardScreen / TerminalScreen / FileBrowserScreen /     │
│   TextEditorScreen / SettingsScreen / AppRoot(导航)        │
├──────────────────────────────────────────────────────────┤
│ 状态层（StateFlow · UiState 三态 Loading/Success/Error）    │
│   SettingsRepository.settings · SessionManager.sessions ·  │
│   SshManager.status · RootfsInstaller.progress             │
├──────────────────────────────────────────────────────────┤
│ 领域层（单例，经 DebDroidApp.instance 暴露）                 │
│   SessionManager（会话生命周期/单飞/恢复）                   │
│   SshManager（ssh 配置/启停/状态）                          │
│   RootfsInstaller（解压/配置/恢复出厂）                      │
├──────────────────────────────────────────────────────────┤
│ 基础设施                                                   │
│   ProotLauncher（proot argv/env 构造 + runOnce）            │
│   TerminalSession（Termux 内核封装，pty 桥接）               │
│   KeepAliveService / BootReceiver（保活）                   │
│   DataStore（设置持久化）                                   │
│   FsOps（Os.lstat/递归复制/删除等文件操作）                  │
└──────────────────────────────────────────────────────────┘
```

### 1.2 模块与包划分

| 包/类 | 职责 | 依赖 | 关键约束 |
|---|---|---|---|
| `com.debdroid.app.DebDroidApp` | Application：初始化单例仓库 | 全部 | 静态 `instance` 供 Compose 取用 |
| `ui.AppRoot` | 屏级导航（WIZARD/TERMINAL/FILES/EDITOR/SETTINGS） | 全部 UI | 用 `enum Screen`，不用导航库（屏少） |
| `prefs.Settings` / `SettingsRepository` | AppSettings 数据类 + DataStore 读写 | DataStore | 字段见需求 §7；`update{}` 内 read-transform-write |
| `rootfs.RootfsInstaller` | 解压内置 rootfs.tar.xz / 写 apt 源 / resolv.conf / 恢复出厂 | commons-compress, xz | 全程 IO 线程；进度节流 |
| `session.ProotLauncher` | 构造 proot argv/env、ensureBootstrap、runOnce | rootfs, prefs | 参数表见 §3.3（v1.x 全部经验） |
| `session.SessionManager` | 会话 CRUD、ensureSession 单飞、自动恢复 | session, prefs, service | 防死锁/防重复（见 §3.2） |
| `session.TerminalSession` | 封装 Termux TerminalSession + Process + pty | terminal-emulator | 对象主线程构造（Looper 教训） |
| `ssh.SshManager` | sshd 安装/启停/配置/状态 | ProotLauncher | busy 态防连点；端口释放等待 |
| `service.KeepAliveService` | 前台服务 + 唤醒锁 + 通知 | prefs | 启动/停止幂等 |
| `service.BootReceiver` | BOOT_COMPLETED 自启 | prefs, session | exported=true |
| `ui.theme` | M3 深色主题（电光蓝 #0A84FF）+ 8 套终端配色 | compose | 配色数据与渲染共用 |
| `ui.terminal.ExtraKeys` | 29 键布局数据 | — | 布局与模拟页一致 |
| `files.FsOps` | 文件操作：权限串/递归复制/删除/大小格式化 | — | 纯函数，可单测 |
| `terminal-emulator`/`-view` | Termux v0.118.3 终端内核/渲染 | — | 第三方库，不改源码 |

### 1.3 技术栈（需求 §3 决策落点）
Kotlin 2.4.10 · Compose BOM 2025.09.01 · Material3 · DataStore 1.1.1 · 协程 1.9.0 ·
commons-compress 1.27.1 + xz（rootfs 解压）· minSdk 26 / targetSdk 28 / compileSdk 36 · 单 arm64

---

## 2. 数据流

| 流 | 来源 | 变换 | 消费方 | 线程 |
|---|---|---|---|---|
| 设置 | `dataStore.data` → Preferences | `map { toAppSettings() }` | AppRoot collectAsState → 各屏 | Main |
| 会话列表 | SessionManager 内部 List | `MutableStateFlow<List<SessionInfo>>` 同步块更新 | 抽屉、终端页 | Main 写，IO 读（同步保护） |
| 活跃会话 | SessionManager.activeIndex | StateFlow<Int> | 终端页 | Main |
| SSH 状态 | SshManager 内部枚举（IDLE/INSTALLING/RUNNING/STOPPING/FAILED(reason)） | StateFlow | 设置页 | Main |
| 安装进度 | RootfsInstaller 回调 | 节流 ≤100 次/s → StateFlow<InstallProgress> | 向导页 | IO → Main |
| 文件列表 | FsOps 异步加载 | `StateFlow<DirState(Loading/Content/Error)>` | 文件管理器 | IO 加载，Main 发布 |

原则：所有磁盘/进程操作在 `Dispatchers.IO`；UI 只订阅 StateFlow；回调跨线程用 `withContext(Main)` 或 StateFlow 更新。

---

## 3. 关键流程详细设计

### 3.1 首次安装（RootfsInstaller）

触发：`AppRoot` 启动时 `isInstalled()` 为 false → WIZARD 屏。

步骤：
1. [IO] 校验内置资产 `assets/rootfs.tar.xz` 存在（缺失 → Error("资产缺失")）
2. [IO] 解压至 `filesDir/rootfs`（TarArchiveInputStream + XZCompressorInputStream，逐条目写文件，保留符号链接与可执行位）
3. [IO] 写 `/etc/resolv.conf`（宿主 DNS 或自定义）
4. [IO] 按设置写 apt sources.list（默认官方；中文环境首次自动 TUNA——FR-W4）
5. [Main] 进度节流上报（每 ≥10ms 或每 N 条目一次）
6. [Main] 完成 → `onFinished` → 自动创建首个会话（FR-W3）

错误处理：任一步失败 → 删除半成品目录 → 回向导显示错误 + 重试；**不闪退**（FR-W5 同类）。

恢复出厂：停 SSH → 关全部会话 → 删除 `filesDir/rootfs` 等 → 回 WIZARD 屏（复用安装流程）。

### 3.2 会话生命周期（SessionManager）

状态：`SessionInfo(id, title, tmuxBadge, state: CREATING/RUNNING/CLOSED)`

创建（`newSession` / `ensureSession` 单飞）：
1. [IO] `ProotLauncher.ensureBootstrap()`（首次解包 proot.tar 到 `filesDir/opt/proot`）
2. [Main] **构造 TerminalSession 对象必须在主线程**（Termux 内核在构造线程创建 Handler —— v1.0.18 教训：会话对象在主线程 new，重活（proot 进程启动、等待）在 IO 线程）
3. [IO] `ProcessBuilder(proot argv)` + env 启动，pty 桥接 stdin/stdout
4. [Main] 加入会话列表（同步块内更新 StateFlow）

**防死锁（v1.0.19 教训）**：会话列表的更新与恢复路径**不得嵌套获取同一把不可重入锁**。
实现：列表操作用 `synchronized(list)` 短临界区；恢复路径与手动创建共用 `ensureSession` 单飞
（`Mutex` 仅在 ensureSession 顶层获取一次，内部调用不重复获取）。

**自动恢复（FR-S4）**：`KeepAliveService.onCreate` → 若 `keepRestore` 且 rootfs 已装 → 主线程
`ensureSession(settings)`（单飞防与用户手动创建重复）。幂等：已有活跃会话则跳过。

关闭：`closeSession` → kill proot 进程（destroyForcibly）→ 从列表移除；全部关闭同理。

### 3.3 proot 启动（ProotLauncher）—— v1.x 全部经验落点

**argv 表**（顺序即实际命令行）：

| 参数 | 原因 |
|---|---|
| `<prootBin>` | `filesDir/opt/proot/bin/proot`（首次从 assets/proot.tar 解包；回退 nativeLibraryDir/libproot.so） |
| `-r <filesDir>/rootfs` | 根文件系统 |
| `-0` | 模拟 root |
| `-l` | link2symlink：dpkg 的 linkat 在无权限时转符号链接 |
| `-L` | 跟随符号链接 |
| `--kill-on-exit` | 会话退出即杀 guest，避免残留进程 |
| `-b <filesDir>/tmp:/tmp` | **宿主 /tmp 绑定**：npm/cacache 临时文件（TMPDIR=/tmp）落宿主，link2symlink 不对宿主 bind 生成 .l2s，rename 不再 ENOENT（v1.0.28） |
| `-b <filesDir>/npm:/root/.npm` | **npm 缓存绑定**：跨会话保留 + 避开 .l2s（v1.0.26）；**必须用 filesDir 而非 cacheDir**——cacheDir 会被系统清空导致悬空绑定 d??????????（v1.0.27） |
| `-b /dev` `-b /dev/urandom:/dev/random` `-b /dev/pts` `-b /proc` `-b /sys` `-b /proc/self/fd:/dev/fd` | 标准设备/伪文件系统（v1.0.28 完整 -b 集合） |
| `-b <resolv>:/etc/resolv.conf` | 自定义 DNS（resolv 存在时） |
| `-w <initialDir>` | 初始工作目录，默认 /root |
| `/usr/bin/env -i HOME=/root USER=root LOGNAME=root SHELL=/bin/bash TERM=xterm-256color PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LANG=C.UTF-8 LC_CTYPE=C.UTF-8` | guest 环境（-i 清空继承） |
| `/bin/sh -c <startup>` | 经 shell 执行（多词命令正确）；startup 默认 `/bin/bash --login`；`tmuxAttach && 有 tmux && startup==默认` 时替换为 `tmux new -A -s main` |

**env 表**（proot 进程自身，Android 侧）：

| 变量 | 值 | 原因 |
|---|---|---|
| `PROOT_TMP_DIR` | `<cacheDir>/proot` | proot 内部临时目录 |
| `PROOT_F2FS_WORKAROUND=1` | 固定 | **强制 f2fs workaround**：探测不触发时 npm link→.l2s 损坏（v1.0.29 根因修复） |
| `PATH` | `/system/bin:/system/xbin` | Android 宿主路径 |
| `HOME` | `<filesDir>` | 宿主侧 HOME |
| `TMPDIR` | `/tmp` | **guest 视角**指向宿主绑定（v1.0.28） |
| `ANDROID_ROOT=/system` `ANDROID_DATA=/data` `EXTERNAL_STORAGE=...` | 固定 | Android 环境 |
| `LD_LIBRARY_PATH` / `PROOT_LOADER` | proot lib/loader 路径 | 解包成功时注入 |
| **不设置** `PROOT_NO_SECCOMP` | — | seccomp 快路径保留（纯 ptrace 会输入回显卡顿，v1.0.7 教训） |

**runOnce**（SSH/apt 等一次性命令）：复用上述 argv/env，把尾部 `/bin/sh -c <startup>` 替换为
`/bin/bash -c <command>`，输出收尾、超时（默认 600s）强制杀。

### 3.4 保活（KeepAliveService + BootReceiver）

- 前台服务：`startForeground(NOTIF_ID, 通知)`；`keepForeground=false` 时用普通 start（不常驻通知）
- 唤醒锁：`keepWakelock` → PARTIAL_WAKE_LOCK 获取/释放
- 电池白名单：`keepBatteryWhitelist` → 启动时检查 `isIgnoringBatteryOptimizations`，未通过且设置开启 → 引导 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（FR-K3）
- 开机自启：`BootReceiver` 收 BOOT_COMPLETED → `keepBoot` → 启动服务（+ `keepRestore` 恢复会话）
- 幂等：`start()`/`stop()` 内部判重，连点不重复启动（v1.0.25 SSH 同款防护思路）
- 安装完成不再常亮（v1.0.17 教训）：唤醒锁仅会话活动期按需持有，向导/空闲释放

### 3.5 SSH（SshManager）

- 配置生成（proot runOnce 内写）：
  - `/etc/ssh/sshd_config`：Port、ListenAddress（仅局域网=`<手机IP>`；全部=`0.0.0.0`）、
    PermitRootLogin=yes、PasswordAuthentication=开关、PubkeyAuthentication=开关、
    AuthorizedKeysFile、PermitEmptyPasswords=no
  - `~/.ssh/authorized_keys`（公钥多行写入）
- 启动：`startBlocking` → runOnce 起 `sshd`；**返回 String? 失败原因**（真实 stderr：端口占用/配置错误，FR-H2）
- 停止：`stopBlocking` → kill sshd → **等待进程退出 ≤3s**（destroyForcibly 异步，立即重启会
  "address already in use"，v1.0.25 教训）
- 竞态：switch/start/stop 按钮 busy 态禁用；内部 `isBusy` 标志，快速连点不并发
- 状态机：`IDLE → INSTALLING → RUNNING ⇄ STOPPING → FAILED(reason)`；安装失败回滚开关为 off
- 预装：rootfs 构建期预装 openssh-server（镜像内，FR-H1 即开即用）

### 3.6 文件管理器

- 双栏：L=Debian rootfs（`filesDir/rootfs` 路径映射为 `/` 视角），R=手机存储（`/sdcard`）
  —— 注意：**rootfs 内路径经 proot 视角 vs 宿主 filesDir 直读**两套；v2 采用**应用层直读**
  （Os.lstat 拿真实权限串，v1.0.13 移除 proot 挂载的决定），读写统一走宿主路径
- 列表加载：`DirState(Loading/Content/Error)`，IO 线程列目录（含 lstat 权限、大小、mtime），Main 发布
- 排序：名称↑↓/大小/时间（FsOps 纯函数 comparator）
- 批操作：多选 → 复制/移动（递归，IO 线程，进度 snackbar）、删除（确认弹窗含"不可撤销"）
- 编辑器入口：扩展名白名单（py/md/sh/txt/js/json/yaml/toml/conf…）→ TextEditorScreen

### 3.7 文本编辑器

- 行号栏：Canvas 按可视行绘制（万行不卡，v1.0.17 经验）
- 撤销/重做：栈（容量 100），编辑命令入栈
- 查找/替换：匹配计数 + 跳转 + 全部替换
- 脏标记：文本 != 磁盘内容 → 标题"未保存"徽标；返回拦截确认（Dialog）
- 保存：写回原路径（IO 线程）→ 脏标记清除 → snackbar"已保存"
- 只读：🔒 切换，禁编辑

### 3.8 设置持久化

DataStore `debdroid_settings`；`update {}` 内 read-transform-write（并发不丢写，v1.0.x 捏合缩放教训）。
字段表见需求 §7，读写映射集中在 `SettingsRepository`（toAppSettings/writeSettings 对称）。

---

## 4. 数据与持久化设计

### 4.1 应用私有目录布局（filesDir 优先，禁 cacheDir 存重要数据）

| 路径 | 内容 | 说明 |
|---|---|---|
| `filesDir/rootfs/` | Debian 13 rootfs | 解压目标；恢复出厂时删除重解 |
| `filesDir/opt/proot/` | proot 运行时（bin/lib/libexec） | 首次从 assets/proot.tar 解包 |
| `filesDir/npm/` | npm 缓存（绑 /root/.npm） | 见 §3.3 |
| `filesDir/tmp/` | 宿主 /tmp 绑定 | 见 §3.3 |
| `filesDir/resolv.conf` | DNS 覆盖 | 存在即绑定 |
| `cacheDir/proot/` | proot 临时目录 | 可清，无碍 |

### 4.2 设置迁移
DataStore Preferences 加字段即默认值兼容；`toAppSettings` 对每个键做 `?: 默认值` 兜底
（v1.x 同款，无需版本号）。

---

## 5. CI/CD 流水线

### 5.1 工作流 `.github/workflows/build.yml`（步骤明细）

| # | 步骤 | 工具/命令 | 输入 | 输出 | 失败处理 |
|---|---|---|---|---|---|
| 1 | 检出 | actions/checkout@v4 | — | 代码 | — |
| 2 | 恢复 keystore | `echo $KEYSTORE_BASE64 \| base64 -d > release.keystore` | Secret | keystore 文件 | 缺失→构建失败（应报） |
| 3 | JDK | actions/setup-java@v4 (temurin 17) | — | Java 17 | — |
| 4 | Gradle 缓存 | gradle/actions/setup-gradle@v4 | — | 依赖缓存 | — |
| 5 | 构建 rootfs 资产 | `scripts/fetch_rootfs.sh arm64 app/src/main/assets/rootfs.tar.xz` | LXC 镜像 + SHA256 | rootfs.tar.xz | 校验失败→exit 1 |
| 6 | 构建 proot 资产 | `scripts/fetch_proot.sh aarch64 app/src/main/assets/proot.tar` | Termux deb | proot.tar | 失败→exit 1 |
| 7 | 单元测试 | `./gradlew testReleaseUnitTest -PversionCode=...` | 源码 | 测试报告 | **失败→阻断 Release**（FR-Q1） |
| 8 | 编译 | `./gradlew assembleRelease -PversionCode=${{ github.run_number }}` | 源码+资产 | arm64 APK | 失败→修复重推 |
| 9 | 发 Release | `gh release create v2.0.0-<run> <apk> --generate-notes` | APK | GitHub Release | 步骤 7-8 全绿才执行 |

并发：`concurrency: build-${{ github.ref }}` + `cancel-in-progress: true`（迭代期连续 push 不排队）。
权限：`contents: write`。触发：push main + workflow_dispatch。

### 5.2 资产脚本（单 arm64 简化版）

```
scripts/fetch_rootfs.sh <arm64> <out> [cache-dir]
  - LXC images.linuxcontainers.org debian/trixie/arm64/default
  - SHA256SUMS 校验 + 缓存
  - EXTRA_PACKAGES="tmux openssh-server" 经 qemu-user-static chroot 预装（失败回退原镜像，不阻断）
  - 输出 app/src/main/assets/rootfs.tar.xz

scripts/fetch_proot.sh <aarch64> <out>
  - Termux main 仓库 proot/libtalloc/libandroid-shmem deb 解包
  - 打包为 纯 tar（不 gzip！AAPT2 会静默解压 *.gz 资产并丢后缀 → 应用内查找失败）
  - 输出 app/src/main/assets/proot.tar
```

### 5.3 签名
- Secrets：KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS=debdroid / KEY_PASSWORD
- build.gradle.kts：`storeFile = file(System.getenv("KEYSTORE_FILE") ?: "release.keystore")`，
  CI 传 `KEYSTORE_FILE=$GITHUB_WORKSPACE/release.keystore`；本地无 Secrets 时回退 debug 签名
- 新 keystore 已生成并本机备份（~/.dsh/keystores/debdroid/），**旧版用户无法覆盖安装**（决策已确认）

### 5.4 发布
Release 命名 `v2.0.0-<run>`；APK 命名 `DebDroid-arm64-v2.0.0-<run>.apk`（在 workflow 里重命名产物）；
手机从 `https://github.com/<owner>/<repo>/releases/latest` 下载。

---

## 6. 测试策略

| 被测单元 | 用例 | 断言要点 |
|---|---|---|
| ProotLauncher 参数构造 | 默认 / tmux 开启 / 自定义命令 / 有/无 tmux | argv 序列与 §3.3 表一致；tmux 替换条件正确 |
| ProotLauncher env | F2FS 固定 / 无 NO_SECCOMP / TMPDIR=/tmp | env 表逐项 |
| FsOps 排序 | 名称↑↓/大小/时间、目录优先 | comparator 稳定 |
| FsOps 权限串 | drwxr-xr-x 构造 | 与 lstat mode 位一致 |
| SettingsRepository 映射 | 默认值 / 越界（fontSize/port）/ 枚举容错 | to/from 对称 |
| SessionManager 单飞 | 并发 ensureSession 只建一个 | 列表长度 == 1 |
| RootfsInstaller 解压 | 小型 tar 资产 | 文件/链接/权限正确 |
| SSH 配置生成 | 端口/监听/密码开关组合 | sshd_config 内容正确 |

门禁：`./gradlew testReleaseUnitTest` 全绿；新增核心逻辑必须有测试（FR-Q1）。
终端内核自带 Termux 测试（terminal-emulator/src/test，沿用）。

---

## 7. 实现风险与规避

| # | 风险 | 规避 |
|---|---|---|
| I-01 | API 29+ 禁止执行私有目录可执行文件 | targetSdk 28 硬约束；README 说明"为旧版设计"是预期 |
| I-02 | AAPT2 静默解压 *.gz 资产 | proot.tar 用纯 tar；rootfs.tar.xz 用 .xz（不在 AAPT2 处理名单） |
| I-03 | 不可重入锁嵌套死锁（v1.0.19 教训） | 列表操作用短 synchronized 临界区；恢复/创建共用单飞，锁只在顶层取一次 |
| I-04 | TerminalSession 构造线程 Looper 异常（v1.0.18 教训） | 会话对象主线程构造，进程启动 IO 线程 |
| I-05 | cacheDir 被清导致悬空 bind（v1.0.27） | npm/tmp/resolv 全部放 filesDir |
| I-06 | f2fs 探测不触发（v1.0.29） | PROOT_F2FS_WORKAROUND=1 强制 |
| I-07 | 无本地 SDK，编译错误靠 CI 发现 | 关键逻辑单测先行；CI 全量编译门禁；push 前本地 ktlint/语法粗查 |
| I-08 | SSH 端口释放不及时（v1.0.25） | stop 后等待 ≤3s 进程退出 |
| I-09 | 安装回调刷爆重组（v1.0.17） | 进度节流 ≤100 次/s |

---

*本文档 + docs/requirements.md + mockup/index.html 为 v2.0.0 实现基线；代码实现与本文档冲突时以本文档为准并回写更新。*
