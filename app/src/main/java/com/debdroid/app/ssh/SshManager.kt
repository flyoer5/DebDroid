package com.debdroid.app.ssh

import android.content.Context
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.ProotLauncher
import java.io.File
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    /** 看门狗协程（sshd 启动后武装，停止/重启时取消）。 */
    @Volatile
    private var watchdogJob: Job? = null

    /** 上次自动重启时刻（elapsedRealtime），跨看门狗实例共享做节流。 */
    @Volatile
    private var lastAutoRestartAt = 0L

    /** 连续自动重启计数（健康探测归零；手动启停归零），跨看门狗实例累计做上限。 */
    @Volatile
    private var autoRestartCount = 0

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
        // 仅局域网=绑定手机 WiFi IP（文档 architecture.md §3.5）；127.0.0.1 会谁都连不上（真机调试定位）
        val listenAddr = when {
            settings.sshListenAll -> "0.0.0.0"
            else -> localIpAddress() ?: "0.0.0.0"
        }

        val sshCfg = File(rootfs, "etc/ssh/sshd_config")
        sshCfg.parentFile?.mkdirs()
        runCatching { sshCfg.delete() } // 覆盖包管理器留下的文件/符号链接
        // 注：无 UsePAM——rootfs 内置自定义 sshd（无 sandbox 交叉编译）不识别 UsePAM；
        // HostKey 显式指定（编译版默认找 /usr/local/etc，真机调试定位）。
        // 文本由 SshdConfig 纯函数生成（含 v2.1.7 稳定性加固参数，单测覆盖）。
        sshCfg.writeText(SshdConfig.render(settings.sshPort, listenAddr))

        val sshDir = File(rootfs, "root/.ssh")
        sshDir.mkdirs()
        sshDir.setExecutable(true, false)
        val keys = settings.sshAuthorizedKeys.trim()
        val authorizedKeys = File(sshDir, "authorized_keys")
        if (keys.isEmpty()) {
            runCatching { authorizedKeys.delete() }
        } else {
            // v2.1.25：authorized_keys 写入竞态兜底。冷启动首启曾出现一次性 EACCES
            // （真机 19:24:36 崩溃记录：FileOutputStream open EACCES），异常冒泡会
            // 中断 startAsync 的 sshd 启动链（下次 newSession 才补启，期间 SSH 缺位）。
            // 契约：宿主侧直写重试 2 次；仍失败改走 guest 侧（runOnce）落盘，
            // 公钥配置不丢、启动链不中断。
            var written = false
            var lastErr: Throwable? = null
            repeat(2) {
                runCatching {
                    runCatching { authorizedKeys.delete() }
                    authorizedKeys.writeText(keys + "\n")
                    written = true
                }.onFailure { e -> lastErr = e; Thread.sleep(250) }
            }
            if (!written) {
                val payload = Base64.encodeToString((keys + "\n").toByteArray(), Base64.NO_WRAP)
                ProotLauncher(context, settings).runOnce(
                    "echo $payload | base64 -d > /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys",
                    timeoutSeconds = 30,
                )
                android.util.Log.w("DebDroid", "authorized_keys host write failed; guest fallback used", lastErr)
            } else {
                authorizedKeys.setReadable(true, true)
                authorizedKeys.setWritable(false, false)
                authorizedKeys.setExecutable(false, false)
            }
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
     * @param autoRestart true=看门狗自愈触发的重启（不重置连续失败计数）；默认 false=手动/自启启动（计数清零，全新开始）。
     * @return null=成功；否则人类可读失败原因（真实 stderr 尾部）。
     */
    fun startBlocking(settings: AppSettings, autoRestart: Boolean = false): String? {
        stopBlocking()
        if (!isInstalled()) return context.getString(com.debdroid.app.R.string.ssh_not_installed_hint)
        applyConfigBlocking(settings)

        val launcher = ProotLauncher(context, settings)
        val args = launcher.buildArgs()
        val envIndex = args.indexOf("/usr/bin/env")
        val cmdArgs = args.subList(0, envIndex).toMutableList()
        cmdArgs += listOf("/usr/bin/env", "-i")
        cmdArgs += args.subList(envIndex + 2, args.size - 3) // env 赋值段
        // 直接 exec sshd（不用 /bin/sh -c 包装）：holder 即 sshd 进程，stop 时 destroyForcibly 才能杀掉。
        // 此前 sh 包装导致 stop 只杀 wrapper，sshd 子进程残留占端口（真机调试定位）。
        cmdArgs += listOf("/usr/sbin/sshd", "-D", "-f", "/etc/ssh/sshd_config")

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
                if (!autoRestart) autoRestartCount = 0 // 手动/自启启动 = 全新开始
                armWatchdog(settings) // 启动成功才武装看门狗（自愈，FR-H4）
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

    /**
     * SSH 自愈看门狗（FR-H4，v2.1.7）。
     *
     * 背景（真机暴露）：默认 sshd 配置下，客户端重试堆积会占满未认证连接槽，
     * sshd 表现为「端口通但新连接 banner 不来」，持续数分钟，只能重启应用恢复。
     * 加固配置（SshdConfig）已把风暴自清理时间压到 ~20s；本看门狗兜底处理
     * 进程假死/退出——连续探测失败自动重启 sshd，无需用户重启整个应用。
     *
     * - 探测：每 [WATCHDOG_INTERVAL_MS] 向监听地址发起一次 TCP 连接并读 banner
     *   （与真实 ssh 客户端一致——健康 sshd 会立即回 "SSH-2.0-..."）。
     * - 干预：进程退出立即重启；进程存活但连续 [PROBE_MISSES_TO_RESTART] 次
     *   探测无响应视为假死重启。间隔不低于 [AUTO_RESTART_GAP_MS]，
     *   连续失败超过 [MAX_AUTO_RESTARTS] 次停止 sshd 并放弃（避免死循环风暴）。
     * - 不探测场景：仅局域网监听且拿不到本机 IP（WiFi 断开等）——无监听地址可测，
     *   重启也不会改善。
     */
    private fun armWatchdog(settings: AppSettings) {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            var misses = 0
            while (true) {
                delay(WATCHDOG_INTERVAL_MS)
                // 已被用户停止（status 非 Running）→ 退出；stopBlocking 也会 cancel 本协程
                if (_status.value !is SshStatus.Running) break
                // 仅局域网且拿不到本机 IP（WiFi 断开等）→ 无监听地址可测，跳过本轮
                if (!settings.sshListenAll && localIpAddress() == null) continue

                val alive = holder?.isAlive == true
                if (alive && probeSsh(settings)) {
                    misses = 0
                    autoRestartCount = 0 // 健康 → 连续失败计数归零
                    continue
                }
                if (alive) {
                    misses += 1 // 进程假死：连续探测无响应累计
                    if (misses < PROBE_MISSES_TO_RESTART) continue
                } else {
                    misses = PROBE_MISSES_TO_RESTART // 进程退出 = 立即干预一次
                }
                // 竞态保险：判定期间用户可能已手动停止——复查，避免看门狗把刚停的 sshd 又拉起
                if (_status.value !is SshStatus.Running) break
                if (autoRestartCount >= MAX_AUTO_RESTARTS) {
                    Log.e(TAG, "sshd 连续异常 ${autoRestartCount + 1} 次仍无法恢复，停止 sshd（放弃自动重启，请手动重新启用）")
                    stopBlocking()
                    break
                }
                if (SystemClock.elapsedRealtime() - lastAutoRestartAt < AUTO_RESTART_GAP_MS) {
                    Log.w(TAG, "sshd 自动重启过于频繁，本轮跳过")
                    continue
                }
                autoRestartCount += 1
                lastAutoRestartAt = SystemClock.elapsedRealtime()
                Log.w(TAG, "sshd ${if (alive) "无响应" else "进程退出"}，自动重启 #$autoRestartCount")
                restartFromWatchdog(settings) // 内部 stop+start，成功则重新武装新看门狗
                break
            }
        }
    }

    /** 看门狗重启：内部先 stopBlocking（取消旧看门狗、杀进程、proot 兜底清理）再 startBlocking 重新武装。 */
    private fun restartFromWatchdog(settings: AppSettings) {
        runCatching {
            val err = startBlocking(settings, autoRestart = true)
            if (err != null) Log.e(TAG, "看门狗自动重启失败: $err")
        }.onFailure { Log.e(TAG, "看门狗自动重启异常", it) }
    }

    /** TCP banner 探测：健康 sshd 会在新连接建立后立即回 "SSH-2.0-..."。 */
    private fun probeSsh(settings: AppSettings): Boolean {
        val host = when {
            settings.sshListenAll -> "127.0.0.1"
            else -> localIpAddress() ?: return false
        }
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, settings.sshPort), PROBE_TIMEOUT_MS)
                s.soTimeout = PROBE_TIMEOUT_MS
                val buf = ByteArray(8)
                s.getInputStream().read(buf) > 0
            }
        } catch (_: Exception) {
            false
        }
    }

    fun startAsync(settings: AppSettings) {
        scope.launch { startBlocking(settings) }
    }

    /** 停止 sshd 并等待进程真正退出（≤3s），防止残留占端口（FR-H2 端口释放）。 */
    fun stopBlocking() {
        watchdogJob?.cancel() // 取消看门狗，避免与手动停止竞态
        watchdogJob = null
        holder?.let { p ->
            runCatching { p.destroyForcibly() }
            runCatching { p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS) }
        }
        holder = null
        // 兜底：proot 内清理残留 sshd（旧版本 sh 包装启动的 sshd 杀不掉，会残留占端口）
        runCatching {
            ProotLauncher(context, AppSettings()).runOnce("pkill -x sshd 2>/dev/null; pkill -f 'sshd -D' 2>/dev/null", 10)
        }
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

        /** 看门狗探测周期。 */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** TCP banner 探测单次超时（连接 + 读）。 */
        private const val PROBE_TIMEOUT_MS = 4_000

        /** 进程存活但连续多少次探测无响应判定为假死并重启。 */
        private const val PROBE_MISSES_TO_RESTART = 3

        /** 连续自动重启上限（健康或手动启停后归零；超限停止 sshd 并放弃）。 */
        private const val MAX_AUTO_RESTARTS = 3

        /** 两次自动重启的最小间隔，避免死循环风暴。 */
        private const val AUTO_RESTART_GAP_MS = 90_000L

        /**
         * v2.1.11：sshd 运行中需重启才能生效的设置变化判定（纯函数，单测）。
         * sshd 只在启动时读配置/authorized_keys/执行 chpasswd，改端口、监听、
         * 密码、公钥后不重启即静默失效（真机暴露：改密码旧密码仍可登录、改端口
         * sshd 仍在旧端口）。
         */
        fun sshConfigChanged(old: AppSettings, new: AppSettings): Boolean =
            old.sshPort != new.sshPort ||
                old.sshListenAll != new.sshListenAll ||
                old.sshPassword != new.sshPassword ||
                old.sshAuthorizedKeys != new.sshAuthorizedKeys
    }
}
