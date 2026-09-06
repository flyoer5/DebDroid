package com.debdroid.app.ssh

/**
 * sshd_config 纯文本生成（FR-Q1 纯函数层，单测覆盖）。
 *
 * v2.1.7 起加入 proot/Android 环境稳定性加固参数，直接对应真机暴露的问题：
 * 默认配置（MaxStartups=10 个未认证槽、LoginGraceTime=120s 死亡宽限）下，
 * 客户端连接受阻反复重试会把未认证连接槽占满 → sshd 对新连接不再应答，
 * 表现为「端口通但 banner 一直不来」，长时间静默后或需重启应用才能恢复。
 *
 * 各参数作用（全部为 OpenSSH ≥3.8 的通用项，兼容 rootfs 内置交叉编译 sshd）：
 * - MaxStartups 100:30:200：未认证并发槽放宽 20 倍，重试堆积不再锁死新连接
 * - LoginGraceTime 20：死连接 20s 内回收（默认 120s），风暴过后快速自愈
 * - UseDNS no：不做反查 DNS——proot 内 resolv 异常时 accept 处理不被拖住
 * - TCPKeepAlive yes / ClientAlive*：WiFi 休眠/换网后的僵死会话被主动断开回收
 */
internal object SshdConfig {

    fun render(port: Int, listenAddress: String): String = buildString {
        appendLine("Port $port")
        appendLine("ListenAddress $listenAddress")
        appendLine("PermitRootLogin yes")
        appendLine("PasswordAuthentication yes")
        appendLine("PubkeyAuthentication yes")
        appendLine("HostKey /etc/ssh/ssh_host_ed25519_key")
        appendLine("PrintMotd no")
        appendLine("AcceptEnv LANG LC_*")
        appendLine("Subsystem sftp internal-sftp")
        appendLine("# ---- stability hardening for proot/Android (v2.1.7) ----")
        appendLine("MaxStartups 100:30:200")
        appendLine("LoginGraceTime 20")
        appendLine("UseDNS no")
        appendLine("TCPKeepAlive yes")
        appendLine("ClientAliveInterval 60")
        appendLine("ClientAliveCountMax 3")
    }
}
