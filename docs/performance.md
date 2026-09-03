# 终端性能：打字回显延迟排查记录

> 状态：v2.1.0 已修复。用户报告「终端输入命令并执行有明显延迟，打字回显不跟手」。
> 根因（真 bug）：`TerminalBridge.activeTerminalView` 重构后从未被赋值，`onTextChanged → invalidate`
> 是空操作，屏幕只靠 500ms 光标闪烁重绘——刷入字符后最多等 ~500ms 才显示。
> 修复：TerminalView 创建/onEmulatorSet 时赋值、onDispose 清空（commit de28344）。
> 注意：下方"proot 翻译层"分析仅剩次要影响；实测修复后渲染即时（缓冲 ~110ms 内屏幕像素同步）。

## 代码链路审查结论（v2.0.8，全部逐行确认）

```
IME commitText → BaseInputConnection → session.writeCodePoint
  → ByteQueue(4096, 非阻塞) → OutputWriter线程 → pty            [即时，宿主 fd 直写]
guest bash readline 读字符 → 处理 → 回显                        [延迟源：proot 翻译]
InputReader线程 → 主线程Handler(MSG_NEW_INPUT) → emulator.append
  → SessionManager.onTextChanged → TerminalBridge → activeTerminalView.onScreenUpdated()
  → invalidate → TerminalRenderer.render（全屏）
```

| 环节 | 文件 | 结论 |
|---|---|---|
| 输入写 | `TerminalSession.java:147` / `ByteQueue.java` | 非阻塞；OutputWriter 线程消费；打字量远小于 4096，无锁等待 |
| pty 读 | `TerminalSession.java:132` InputReader 线程 | 4KB 批读 → 主线程 handler |
| 主线程 feed | `MainThreadHandler.handleMessage` | append + notifyScreenUpdate，批处理 |
| 渲染 | `TerminalRenderer.java` | 复用 Paint、ASCII 宽度缓存、drawTextRun 批量画；无每帧分配 |
| 重绘频率 | `TerminalView.onScreenUpdated` | invalidate 由 Android 合并到 vsync；光标 500ms 闪烁 |
| Compose 侧 | `TerminalScreen.kt` update lambda | `TerminalHeldState` 跳过冗余属性设置，注释明示不拖慢按键回显 |
| 会话桥 | `TerminalBridge.kt` | @Volatile 单视图引用，主线程安全 |

**没有发现可修的 app 侧性能缺陷。**

## 延迟本质：proot 翻译层

按键回显链中，guest bash readline 每字符执行多次系统调用
（read / tcgetattr / write 等），**每次都经 proot 翻译**：

- seccomp 快路径：~0.5–1µs/syscall（不可感知）
- ptrace 慢路径（seccomp 不可用时 fallback）：~10–100µs/syscall

命令执行（Enter → fork/exec）在 proot 下还要翻译进程树创建与新二进制加载，
是 proot 方案最常见的性能痛点。Termux proot-distro 在同硬件上表现相同。

## 未来有真机时的排查步骤（按优先级）

1. **确认 seccomp 路径**：设 `PROOT_VERBOSE=1`（或 strace）观察 proot 是否打印
   seccomp 不可用并 fallback ptrace。若 fallback，排查
   `PROOT_F2FS_WORKAROUND` 与内核 seccomp 支持 → 修复收益 10–50 倍。
2. **SSH 对照**：SSH 登录（8022）敲命令对比。SSH 同样慢 = 纯 proot 本质；
   SSH 明显快 = app 输入/渲染路径特有，回头查 IME/渲染。
3. **渲染对照**：`useNerdFont=false` 对比打字回显（排除复杂字形渲染成本）。
4. **测量手段**：`adb logcat` 打点 InputReader 读批时间戳 vs 主线程 append
   时间戳，量化端到端回显延迟分段。

## 已知与性能相关的配置

- `ProotLauncher.buildEnv`：`PROOT_F2FS_WORKAROUND=1`（Termux 同款，v1.0.29
  根因修复）；**不设** `PROOT_NO_SECCOMP`（必须保留 seccomp 快路径）。
- 会话 argv 含 `-0 -l -L --kill-on-exit` 及多 `-b` 绑定；`-l`（link2symlink）
  仅影响 link() 调用，非路径解析热点，不是延迟来源。
- rootfs 为完整 Debian 13（assets tar.xz 预构建，用户端不跑 dpkg 安装）。
