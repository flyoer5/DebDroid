# Changelog

All notable changes to DebDroid. 版本与功能编号对应 docs/requirements.md 的 FR 编号。

## [2.1.22] — 目录型符号链接在文件管理器中可正常导航

### 修复

- **指向目录的符号链接被当普通文件**（rootfs 内大量存在：libncurses6→libtinfo6 等
  Debian 替换包链接）：`listDir` 用 lstat，链接的 isDir 恒 false——文件管理器里目录
  链接显示 🔗 文件图标、排序靠后、点击走"打开方式"而非进入目录（真机暴露：
  /usr/share/doc 下 libncurses6 无法点开，guest 侧 ls 可达）。
- 修复：链接目标的 isDir 用 stat（跟随链接）判定；权限串仍按 lstat（'l' 前缀保留，
  isLink 标记不变）；悬空链接安全按非目录处理（stat 失败兜底）。
- debug API files 列表同步受益（同一 listDir）。

## [2.1.21] — 会话生命周期入诊断日志

### 改进

- **/api/logs 诊断端点长期为空**：只捕获 TerminalSessionClient 回调，健康运行一小时
  也近乎无内容，诊断价值形同虚设（真机暴露）。
- 会话生命周期事件入环形缓冲：`[session-created]`（含 name/handle/total）、
  `[session-closed]`（manual=true/remaining），与既有 `[session-finished]` 构成完整
  生命周期链，配合 v2.1.14 的退出诊断可完整回溯会话历史。

## [2.1.20] — 调试 API files/write 二进制安全与显式契约

### 修复

- **files/write 只认文本 content，contentBase64 被静默忽略、写出空文件仍返回 ok**
  （真机暴露：base64 写二进制 → md5=d41d8… 空文件）。
- 修复：支持 `contentBase64`（解码后二进制安全写入，优先于 `content`）；
  新增 `append` 布尔字段（默认覆盖）；`content`/`contentBase64` 均缺 → 400 显式报错
  （不再静默写空）。成功响应附 `bytes` 实写字节数。

## [2.1.19] — tmux 保持模式落地 shell（detach/exit 不再瞬死）

### 改进

- **tmuxAttach 开启时，detach（Ctrl+B d）与退出 main 都让会话直接消亡**——裸
  `tmux new -A -s main` 包装下 client 退出即 proot 退出；配合 v2.1.17 重生机制还会
  立刻 re-attach 回 main，detach 完全无效，用户永远无法脱离 tmux（真机验证暴露）。
- 改为 Termux 式落地 shell：`tmux new -A -s main; exec /bin/bash --login`——
  detach/退出 main 后落到普通 bash（会话存活）；exit bash 才会话终结（重生机制
  兜底再进 tmux）。非默认启动命令与 tmuxAttach=false 路径不变。

## [2.1.18] — 补建会话防误触与防连环重生

### 修复

- **手动关闭最后一个会话仍被强行补建**（真机暴露：badge 关闭后 Session 5/6 连环重生）：
  `closeSession` 先登记 `manualCloses`，`onSessionFinished` 区分手动关闭与进程自然退出，
  手动路径不置补建标志——尊重用户意图（抽屉"新建会话"仍可用）。
- **补建会话秒死会无限连环重生**（旧 tmux server 拆卸竞态即触发一次，真机 Session 5）：
  ①补建前 1.5s 延迟避开拆卸窗口；②连续 5 次补建仍秒死则停手（rootfs 损坏等场景防
  死循环），60s 稳定后计数自愈复位。
- 顺带：`closeSession` 对已死会话此前不清理列表（残留幽灵项）——补上直接移除路径。

## [2.1.17] — 末会话退出后自动补新会话（消除空白卡死）

### 修复

- **用户输入 `exit`/Ctrl+D（或共享 tmux 会话销毁）后所有会话清空，终端区空白、
  无任何操作入口**（真机复现：exit 后 sessions=0、屏幕空，仅能靠抽屉自救）。
- 修复：`SessionManager.lastSessionDied` 标记"末会话因进程退出而清空"（仅此路径
  置位，手动关闭/恢复出厂不触发），`AppRoot` 观察到清空即自动补建新会话
  （Termux 同款语义）。新会话按当前设置启动（tmux 模式自动重建 main 服务）。

## [2.1.16] — 修复第二会话"干净消失"（tmux ACL 跨 proot 拒绝）

### 修复

- **新建第二个会话并选中后，原会话/新会话在数十秒内 exit 0"干净消失"（真机多会话
  走查暴露，v2.1.14 诊断日志定位）**：死亡会话的 tmux 客户端全部以 `access not
  allowed` + exit 0 退出。
- 根因：tmux 3.3+ 的 server-access ACL。proot -0 伪造 getuid=root，tmux server 自认
  owner uid=0；客户端 connect 时 SO_PEERCRED 上报**真实 app uid**。首会话客户端是
  server 创建者（免检）可连；后续任何 proot 的客户端（第二会话/runOnce 诊断）都被
  拒绝——`tmux new -A -s main` 秒退 → proot --kill-on-exit → 会话从列表消失。
- 修复（真机已验证）：`RootfsInstaller.applyTmuxAcl()` 于 configure 幂等执行——
  /etc/passwd 增加 `ddapp` 条目映射真实 uid，/root/.tmux.conf 写入
  `server-access -a ddapp`（server 启动时把真实 uid 加入允许名单）。写入后跨
  proot `tmux ls` 立即可用，多会话不再级联死亡。

## [2.1.15] — 修复多会话级联死亡（fd 双重关闭竞态）

### 修复

- **第二会话选中后，原会话在数秒~一分钟后"干净退出"（exit 0）消失，且可能级联**
  （真机多会话走查 + v2.1.14 诊断日志定位：被杀会话 tmux client 全部 exit 0 =
  pty master 被提前关闭→EOF）。
- 根因：`TerminalSession` 的 pty master fd 存在三个关闭者（reader 线程
  try-with-resources 隐式 close、writer 线程同、`cleanupResources` 的 `JNI.close`）。
  线程晚到的 close 会关掉**已被新会话复用的 fd 号**，杀死无辜会话的终端。
- 修复：fd 生命周期归 `cleanupResources` 独占——IO 线程退出不再关闭底层流，
  cleanup 幂等（`mCleanedUp` 标记），全进程内每个 fd 恰好 close 一次。

## [2.1.14] — 会话退出诊断补强

### 变更

- **多会话偶发死亡零日志（真机走查暴露，定位中）**：第二会话在选中/启动后数秒内
  偶发消失（会话列表回落、无崩溃、无 transcript、无任何日志），机制未定位。
- 本版先补诊断：`onSessionFinished` 恒记录（名称 / handle / 退出码 / transcript 尾部
  或 `<blank>`）。退出码语义：负值=信号（-9=SIGKILL、-1=SIGHUP）、正值=exit code，
  下一轮复现即可锁定杀手；原实现只记非空 transcript，空白会话死亡完全无痕。

## [2.1.13] — SSH 设置变更重启不再冻结 UI

### 修复

- **设置页改 SSH 配置时 UI 冻结数秒、有 ANR 风险（真机 UI 走查暴露）**：
  v2.1.11 的 `restartSshIfRunning` 在 Main 协程直接跑阻塞式 `startBlocking`
  （内部 stop 含 runOnce pkill，秒级），重启期间整个设置界面无响应。
- 修复：与启停开关同款做法，`withContext(Dispatchers.IO)` 包裹后执行，
  期间仅显示"正在重启…"状态；UI 走查实测改端口 8022→8025 自动重启生效、
  界面不冻结。

## [2.1.12] — DNS/apt 镜像设置变更即时生效

### 修复

- **自定义 DNS / apt 镜像源改后无效（真机暴露）**：二者只在安装时写一次文件——
  设置页改了 customDns，guest 的 resolv.conf（proot 绑定的是宿主文件）仍是安装时
  内容；改了镜像，rootfs 内 sources.list 仍是旧源。
- 修复：抽出幂等写盘 `RootfsInstaller.applyDns`（宿主 resolv.conf，绑定文件改写后
  运行中会话立即可见）/ `applyMirror`（rootfs sources.list，未安装静默跳过），
  configure() 复用。触发点：
  - 设置页 DNS 对话框、镜像选择（IO 协程即时写盘）
  - 调试接口 POST /api/settings 对应键变化时
- 纯函数 `RootfsInstaller.resolvContent(customDns)`（空 → 默认 8.8.8.8/223.5.5.5）
  单测覆盖。

## [2.1.11] — SSH 设置变更运行中自动生效

### 修复

- **sshd 运行中改端口/监听/密码/公钥静默不生效（真机暴露）**：只落盘不重启——
  改密码后旧密码仍可登录（安全盲区）、改端口 sshd 仍在旧端口监听、改监听范围
  无变化。现在四处 UI 变更点（端口对话框、监听开关、密码、公钥）与调试接口
  POST /api/settings 统一处理：**sshd 运行中且相关配置变化 → 自动重启即时应用**
  （startBlocking 先停后起，短时中断）；仅启停变化 → 自动启/停。
- 判定纯函数 `SshManager.sshConfigChanged(old,new)` 单测覆盖（端口/监听/密码/
  公钥四项触发；无关设置不触发）。

## [2.1.10] — 调试接口 POST body 中文修复

### 修复

- **DebugApiServer POST body 非 ASCII 全毁(真机暴露)**:NanoHTTPD 2.3.1 的
  `parseBody` 对 postData 按 `ContentType.getEncoding()` 解码,而 **Content-Type 不带
  charset 时默认 US-ASCII**——任何不带 `; charset=utf-8` 的客户端(curl 等)POST
  中文,JSON 里非 ASCII 全变 U+FFFD(efbfbd):files/write 中文内容/路径写坏、
  session/write 中文命令乱码。已用 jar 级最小复现 + 源码定位确认。
  修复:新增 `HttpBody.readUtf8`——按 Content-Length 直接读原始字节、UTF-8 解码
  (JSON body 按 RFC 8259 即 UTF-8,不再依赖客户端 charset 头);无长度信息时退回
  parseBody 旧行为。JVM 单测起本地 NanoHTTPD 全链路验证中文往返。

## [2.1.9] — 时区跟随系统 + 诊断占用修复

### 改进

- **guest 时区跟随系统（真机暴露）**：此前 rootfs 恒为 `Etc/UTC`，终端 `date`/服务日志与
  本地时间差 8 小时。现在安装后与应用每次启动时，把 `/etc/localtime` 指向与 Android 系统
  时区对应的 zoneinfo（Android 与 Debian 共用 IANA tz 库；id 路径校验 `timezoneTarget` 纯函数
  单测覆盖；本地无对应条目则保持 UTC 不报错）。

### 修复

- **诊断页 rootfs 占用恒显示 4.0K**：此前用顶层目录自身 `length()` 统计；改为
  `FsOps.dirSize` 递归统计（含目录项、不跟随符号链接、不可读子树跳过，迭代实现防深目录栈溢出，
  单测覆盖）。现在能正确显示 ~几百 M 实际占用。

## [2.1.7] — SSH 稳定性修复（真机暴露问题）

### 修复

- **SSH 连接风暴锁死（FR-H4 自愈）**：真机长时间运行暴露——客户端连接受阻反复重试会
  占满 sshd 默认未认证连接槽（MaxStartups=10）与新连接 120s 死亡宽限，导致 sshd
  「端口通但新连接 banner 不来」持续数分钟，只能重启应用恢复。
  - sshd_config 稳定性加固（SshdConfig 纯函数生成，单测覆盖）：`MaxStartups 100:30:200`、
    `LoginGraceTime 20`、`UseDNS no`、`TCPKeepAlive yes`、`ClientAliveInterval 60`、
    `ClientAliveCountMax 3`——风暴堆积不再锁死新连接，死连接 20s 内自动回收。
  - **SSH 自愈看门狗**：每 30s 向监听地址发起 TCP banner 探测（与真实客户端一致）；
    进程退出立即自动重启、连续 3 次探测无响应（约 90s）判定假死自动重启；重启节流
    ≥90s、连续自动重启 ≤3 次仍失败则停止 sshd 并如实显示已停止；看门狗与手动启停
    不竞态（status 门 + job cancel 协同）。此后 sshd 异常无需再重启整个应用。

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
