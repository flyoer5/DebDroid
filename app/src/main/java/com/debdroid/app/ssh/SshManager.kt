package com.debdroid.app.ssh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.ProotLauncher
import java.io.File
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** SSH 服务器状态（FR-H2：真实状态与失败原因）。 */
sealed class SshStatus {
    data object NotInstalled : SshStatus()
    data object Stopped : SshStatus()
    data class Running(val port: Int, val listenAll: Boolean) : SshStatus()
}

/**
 * 管理 rootfs 内预装的 openssh-server（FR-H1~H3，architecture.md §3.5）。
 *
 * - 预装：镜像构建期安装，启用即开即用（无需联网 apt，v1.0.22 决策）
 * - 失败透传：sshd 秒退时把真实 stderr 尾部带回界面（端口占用/配置错误一目了然）
 * - 端口释放：stop 后 waitFor(3s) 等进程真正退出，否则残留 sshd 占端口、立即重启 bind 失败
 * - 竞态：holder 跨线程可见（@Volatile）；快速连点由 busy 语义在 UI 层禁用
 */
class SshManager(
    private val context: Context,
    private val rootfsInstaller: RootfsInstaller,
) {

    private val _status = MutableStateFlow<SshStatus>(SshStatus.NotInstalled)
    val status: StateFlow<SshStatus> = _status.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO)

    @Volatile
    private var holder: Process? = null

    private fun sshdFile(): File = File(rootfsInstaller.rootfsDir(), "usr/sbin/sshd")

    fun refreshStatus() {
        if (holder?.isAlive == true) return
        _status.value = if (sshdFile().exists()) SshStatus.Stopped else SshStatus.NotInstalled
    }

    fun isRunning(): Boolean = _status.value is SshStatus.Running
    fun isInstalled(): Boolean = sshdFile().exists()

    /** rootfs 预装 openssh-server 时直接成功；旧 rootfs 兜底走 apt（需联网）。 */
    fun installBlocking(settings: AppSettings): ProotLauncher.CommandResult {
        if (isInstalled()) return ProotLauncher.CommandResult(0, "openssh-server already installed")
        if (!isNetworkAvailable()) {
            return ProotLauncher.CommandResult(255, context.getString(com.debdroid.app.R.string.ssh_no_network))
        }
        val result = ProotLauncher(context, settings).runOnce(
            "export DEBIAN_FRONTEND=noninteractive; apt-get update && " +
                "apt-get install -y --no-install-recommends openssh-server",
            timeoutSeconds = 900,
        )
        refreshStatus()
        return result
    }

    /** 按设置写 sshd_config / authorized_keys / 密码 / host keys（FR-H3）。 */
    fun applyConfigBlocking(settings: AppSettings): ProotLauncher.CommandResult {
        val rootfs = rootfsInstaller.rootfsDir()
        val listenAddr = if (settings.sshListenAll) "0.0.0.0" else "127.0.0.1"

        val sshCfg = File(rootfs, "etc/ssh/sshd_config")
        sshCfg.parentFile?.mkdirs()
        runCatching { sshCfg.delete() } // 覆盖包管理器留下的文件/符号链接
        sshCfg.writeText(
            """
            Port ${settings.sshPort}
            ListenAddress $listenAddr
            PermitRootLogin yes
            PasswordAuthentication yes
            PubkeyAuthentication yes
            UsePAM no
            PrintMotd no
            AcceptEnv LANG LC_*
            Subsystem sftp internal-sftp
            """.trimIndent() + "\n"
        )

        val sshDir = File(rootfs, "root/.ssh")
        sshDir.mkdirs()
        sshDir.setExecutable(true, false)
        val keys = settings.sshAuthorizedKeys.trim()
        val authorizedKeys = File(sshDir, "authorized_keys")
        if (keys.isEmpty()) {
            authorizedKeys.delete()
        } else {
            runCatching { authorizedKeys.delete() }
            authorizedKeys.writeText(keys + "\n")
            authorizedKeys.setReadable(true, true)
            authorizedKeys.setWritable(false, false)
            authorizedKeys.setExecutable(false, false)
        }

        File(rootfs, "run/sshd").mkdirs()

        val launcher = ProotLauncher(context, settings)
        val commands = mutableListOf("mkdir -p /run/sshd")
        commands.add("if ! ls /etc/ssh/ssh_host_*_key >/dev/null 2>&1; then ssh-keygen -A; fi")
        if (settings.sshPassword.isNotEmpty()) {
            // base64 传递密码，规避 shell 元字符
            val secret = Base64.encodeToString("root:${settings.sshPassword}".toByteArray(), Base64.NO_WRAP)
            commands.add("echo $secret | base64 -d | chpasswd")
        }
        return launcher.runOnce(commands.joinToString(" ; "), timeoutSeconds = 120)
    }

    /**
     * 启动 sshd（后台常驻 proot 进程）。
     * @return null=成功；否则人类可读失败原因（真实 stderr 尾部）。
     */
    fun startBlocking(settings: AppSettings): String? {
        stopBlocking()
        if (!isInstalled()) return context.getString(com.debdroid.app.R.string.ssh_not_installed_hint)
        applyConfigBlocking(settings)

        val launcher = ProotLauncher(context, settings)
        val args = launcher.buildArgs()
        val envIndex = args.indexOf("/usr/bin/env")
        val cmdArgs = args.subList(0, envIndex).toMutableList()
        cmdArgs += listOf("/usr/bin/env", "-i")
        cmdArgs += args.subList(envIndex + 2, args.size - 3) // env 赋值段
        cmdArgs += listOf("/bin/sh", "-c", "/usr/sbin/sshd -D -f /etc/ssh/sshd_config")

        val pb = ProcessBuilder(cmdArgs)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        launcher.buildEnv().forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) env[pair.substring(0, i)] = pair.substring(i + 1)
        }
        return try {
            val process = pb.start()
            Thread.sleep(1500) // 给 sshd 一点启动时间；秒退说明有问题
            if (process.isAlive) {
                holder = process
                _status.value = SshStatus.Running(settings.sshPort, settings.sshListenAll)
                null
            } else {
                val out = process.inputStream.bufferedReader().readText()
                Log.e(TAG, "sshd exited immediately: $out")
                process.destroyForcibly()
                refreshStatus()
                out.trim().takeLast(300).ifBlank {
                    context.getString(com.debdroid.app.R.string.ssh_start_failed)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start sshd", e)
            refreshStatus()
            "sshd 启动异常：${e.message}"
        }
    }

    fun startAsync(settings: AppSettings) {
        scope.launch { startBlocking(settings) }
    }

    /** 停止 sshd 并等待进程真正退出（≤3s），防止残留占端口（FR-H2 端口释放）。 */
    fun stopBlocking() {
        holder?.let { p ->
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        }
        holder = null
        refreshStatus()
    }

    fun stopAsync() {
        scope.launch { stopBlocking() }
    }

    /** 本机首个非回环 IPv4 地址（状态展示用）。 */
    fun localIpAddress(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
        for (ni in interfaces) {
            if (!ni.isUp || ni.isLoopback) continue
            for (addr in ni.inetAddresses) {
                if (addr is java.net.Inet4Address && !addr.isLoopbackAddress) return addr.hostAddress
            }
        }
        null
    }.getOrNull()

    private fun isNetworkAvailable(): Boolean = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    companion object {
        private const val TAG = "SshManager"
    }
}
