# 调试 HTTP 接口（Debug API）

协作调试通道：把 DebDroid 各功能暴露为局域网可达的 REST 接口，无需视觉即可定位问题。
v2.0.2+ 内置。**无安全设计**（用户明确要求），**默认关闭**，仅调试场景开启。

## 开启

设置页 →「调试」→「调试接口」开关。开启后监听 `0.0.0.0:8710`，手机 IP 见开关副标题。

## 连通性

```bash
curl http://<手机IP>:8710/api/ping
# {"ok":true,"app":"2.0.0","build":27,"apiPort":8710}
```

网络 adb 时也可走隧道：

```bash
adb forward tcp:8710 tcp:8710
curl http://127.0.0.1:8710/api/ping
```

## 接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/ping` | 连通性 + 版本 |
| GET | `/api/status` | rootfs/会话/SSH/设置总览 |
| GET | `/api/diagnostics` | 一键诊断文本（设备/rootfs/设置/崩溃栈/最近日志） |
| GET | `/api/logs` | 最近 300 条应用日志 |
| GET | `/api/settings` | 全部设置 |
| POST | `/api/settings` | 部分字段更新（只改 JSON 出现的字段） |
| POST | `/api/command` | proot 内一次性执行 `{"cmd","timeout"}`（秒级，非 tmux 会话） |
| POST | `/api/install` | 异步开始安装（先 wipe）；`GET /api/install/progress` 轮询 |
| POST | `/api/reset` | 停 SSH/关会话/wipe rootfs |
| POST | `/api/session/new` | 新建终端会话（前台无会话时用） |
| POST | `/api/session/write` | 向活动会话写输入 `{"text"}` |
| GET | `/api/session/screen` | 活动会话屏幕文本 |
| GET | `/api/files?path=` | 列目录（含权限串/大小） |
| GET | `/api/files/read?path=` | 读文本文件（≤256KB） |
| POST | `/api/files/write` | 写文本文件 `{"path","content"}` |
| POST | `/api/ssh/start` · `stop` | SSH 控制；`GET /api/ssh/status` 查状态 |

## 示例

```bash
# 执行命令
curl -X POST http://<IP>:8710/api/command -H 'Content-Type: application/json' \
  -d '{"cmd": "uname -a && apt update", "timeout": 120}'

# 改设置
curl -X POST http://<IP>:8710/api/settings -H 'Content-Type: application/json' \
  -d '{"sshPort": 8022, "sshEnabled": true}'

# 看崩溃栈
curl http://<IP>:8710/api/diagnostics | jq .text
```

## 已知限制

- **`session/screen` 读不到 tmux 会话内的输出**：tmux 使用 alternate screen buffer，transcript 只含主缓冲。看命令输出请用 `/api/command`（一次性 proot 执行，不经过 tmux）。
- **POST body 由 NanoHTTPD `parseBody` 读取**：只支持 POST（PUT 的 body 读不到，已改为 POST /api/settings）。
- **存储权限**：`/sdcard` 读写需要 READ/WRITE_EXTERNAL_STORAGE 运行时权限（v2.0.3 起首次启动即请求）。未授权时文件 API 返回空/EPERM。
- **SSH 密码认证**：v2.0.5 及更早的 rootfs（apt 版 openssh）在 proot 下密码认证不可用——OpenSSH 的 seccomp sandbox 在 proot 的 ptrace 下使 `crypt()` 失败，且无法通过配置禁用（真机调试定位）。v2.0.6 起 rootfs 内置交叉编译的无 sandbox sshd（`--with-sandbox=none --with-privsep-path=/`，见 `tools/sshd-arm64/`），**密码与公钥认证均正常**。注意客户端无 TTY 时 sshpass 可能传不进密码（用 `SSH_ASKPASS`/`SSH_ASKPASS_REQUIRE=force`）。
