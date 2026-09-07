package com.debdroid.app.rootfs

import android.content.Context
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.prefs.AptMirror
import java.io.File
import java.util.Locale
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

/**
 * rootfs 生命周期管理（FR-W1~W4 / FR-C2）：
 * 解压内置镜像 → 写 apt 源/resolv.conf → 恢复出厂。
 *
 * 全部磁盘操作必须在 IO 线程调用（调用方负责），进度回调节流 ≤100 次/s（v1.0.17 教训）。
 */
class RootfsInstaller(val context: Context) {

    /** rootfs 根目录（宿主视角）。 */
    fun rootfsDir(): File = File(context.filesDir, "rootfs")

    fun isInstalled(): Boolean {
        val f = rootfsDir()
        return f.exists() && File(f, "etc/os-release").exists() && File(f, "bin/bash").exists()
    }

    /** 解压进度（0f–1f）与阶段文案。 */
    data class Progress(val fraction: Float, val stage: String)

    /**
     * 解压 assets/rootfs.tar.xz 到 filesDir/rootfs（FR-W2）。
     * @return 成功 true；失败抛异常（半成品目录由调用方清理）
     */
    fun extract(onProgress: (Progress) -> Unit) {
        val target = rootfsDir()
        val stage = { s: String -> onProgress(Progress(0f, s)) }
        context.assets.open("rootfs.tar.xz").use { raw ->
            XZCompressorInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    val buf = ByteArray(64 * 1024)
                    var entries = 0
                    val total = estimateEntries() // 用于进度估算
                    stage("解压内置镜像 rootfs.tar.xz")
                    var lastReport = 0L
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        if (name.isEmpty()) continue
                        entries++
                        val out = File(target, name)
                        if (entry.isDirectory) {
                            out.mkdirs()
                        } else if (entry.isSymbolicLink) {
                            out.parentFile?.mkdirs()
                            if (out.exists()) out.delete()
                            runCatching { android.system.Os.symlink(entry.linkName, out.path) }
                        } else {
                            out.parentFile?.mkdirs()
                            java.io.FileOutputStream(out).use { fos ->
                                while (true) {
                                    val n = tar.read(buf)
                                    if (n < 0) break
                                    fos.write(buf, 0, n)
                                }
                            }
                            if (entry.mode and 0b001001001 != 0) out.setExecutable(true, false)
                        }
                        // 节流：≥10ms 或每 500 条目报一次
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 10 || entries % 500 == 0) {
                            lastReport = now
                            onProgress(Progress((entries.toFloat() / total).coerceIn(0f, 1f), "解压内置镜像 rootfs.tar.xz"))
                        }
                    }
                }
            }
        }
    }

    /** 粗略估算条目数（解压进度用）；读取失败返回 20000 兜底。 */
    private fun estimateEntries(): Int = runCatching {
        context.assets.open("rootfs.tar.xz").use { raw ->
            XZCompressorInputStream(raw).use { xz ->
                TarArchiveInputStream(xz).use { tar ->
                    var n = 0
                    while (tar.nextTarEntry != null) n++
                    n
                }
            }
        }
    }.getOrDefault(20000)

    /**
     * 写 apt sources.list（FR-C1/FR-W4）与 resolv.conf（FR-C2）。
     * 中文环境 + 未手动选择 → 默认阿里云（tuna 对部分网络 403，真机调试定位）。
     * v2.1.12：拆出可单独调用的幂等写盘，设置变更（镜像/DNS）即时生效。
     */
    fun configure(settings: AppSettings) {
        applyMirror(settings)
        applyDns(settings)
        // v2.1.16：跨 proot tmux 连接授权（server-access ACL，见 applyTmuxAcl）
        applyTmuxAcl()
        // v2.1.9：安装/配置即让 guest /etc/localtime 跟随系统时区（幂等，失败静默——UTC 兜底）
        runCatching { syncGuestTimezone() }
    }

    /**
     * v2.1.16：tmux 3.3+ server-access ACL 跨 proot 授权（幂等，真机多会话死亡定位）。
     *
     * 问题：proot -0 把 getuid 伪造成 root，tmux server 自认 owner=0；而客户端连接时
     * SO_PEERCRED 报真实 uid（app uid，如 10259）。首会话客户端是 server 的创建者
     * （fork 而来，免 ACL 检查）可以连；**任何后续 proot 的客户端（第二终端会话、
     * runOnce 诊断、SSH 登录外的本机调用）都会被 "access not allowed" 拒绝并以
     * exit 0 退出**——表现为第二会话创建后数十秒内"干净消失"（真机+诊断日志定位）。
     *
     * 修复：/etc/passwd 增加 ddapp 条目映射真实 uid，并在 /root/.tmux.conf 写入
     * `server-access -a ddapp`（server 启动时执行），把真实 uid 加进允许名单。
     * 已在真机验证：写入后跨 proot `tmux ls` 立即可用。
     */
    fun applyTmuxAcl() {
        if (!isInstalled()) return
        runCatching {
            val uid = android.os.Process.myUid()
            val passwd = File(rootfsDir(), "etc/passwd")
            // uid 可能因重装变化：先清掉旧 ddapp 行再追加当前 uid
            val lines = if (passwd.exists()) passwd.readLines() else emptyList()
            val kept = lines.filterNot { it.startsWith("ddapp:") }
            if (kept.size != lines.size || kept.none { it.startsWith("ddapp:") }) {
                passwd.writeText((kept + "ddapp:x:$uid:$uid:DebDroid app uid:/:/bin/sh").joinToString("\n", postfix = "\n"))
            }
            val tmuxConf = File(rootfsDir(), "root/.tmux.conf")
            val confLine = "server-access -a ddapp"
            val existing = if (tmuxConf.exists()) tmuxConf.readLines() else emptyList()
            if (existing.none { it.trim() == confLine }) {
                tmuxConf.parentFile?.mkdirs()
                tmuxConf.writeText(((existing.filterNot { it.trim().startsWith("server-access ") }) + confLine).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /**
     * v2.1.12：按当前设置重写 rootfs 内 apt sources.list（rootfs 未装时静默跳过）。
     * 此前仅安装时写一次——用户改镜像后 apt 仍用旧源（真机暴露）。
     * 磁盘操作；调用方保证 IO 线程。
     */
    fun applyMirror(settings: AppSettings) {
        if (!isInstalled()) return
        runCatching {
            val mirror = AptMirror.fromId(settings.aptMirrorId)
            val sources = File(rootfsDir(), "etc/apt/sources.list")
            sources.parentFile?.mkdirs()
            sources.writeText(buildSourcesList(mirror))
        }
    }

    /**
     * v2.1.12：按当前设置重写宿主 resolv.conf（proot 以 -b 绑定进 guest，
     * 绑定的是同一文件，改写后运行中会话立即可见）。
     * 此前仅安装时写一次——用户改 DNS 后 guest 仍用旧解析（真机暴露）。
     * 磁盘操作；调用方保证 IO 线程。
     */
    fun applyDns(settings: AppSettings) {
        val resolv = File(context.filesDir, "resolv.conf")
        resolv.writeText(resolvContent(settings.customDns))
    }

    /**
     * 让 guest /etc/localtime 跟随 Android 系统时区（v2.1.9，幂等）。
     * 真机暴露：rootfs 恒为 Etc/UTC，终端 date/服务日志与本地差 8 小时。
     * zoneinfo 无对应条目（自定义时区）时保持现状不报错。
     * 磁盘操作；调用方保证 IO 线程（DebDroidApp.onCreate / configure）。
     * @return true=本次已改写
     */
    fun syncGuestTimezone(): Boolean {
        val androidId = runCatching { java.util.TimeZone.getDefault().id }.getOrNull() ?: return false
        val target = timezoneTarget(File(rootfsDir(), "usr/share/zoneinfo"), androidId) ?: return false
        val localtime = File(rootfsDir(), "etc/localtime")
        return runCatching {
            if (localtime.exists() || java.nio.file.Files.isSymbolicLink(localtime.toPath())) {
                if (!localtime.delete()) return@runCatching false
            }
            // 链接内容须为 guest 视角：rootfs 被 proot 映射为 /，故写 "/usr/share/zoneinfo/<id>"
            java.nio.file.Files.createSymbolicLink(
                localtime.toPath(), java.nio.file.Paths.get("/usr/share/zoneinfo/$androidId")
            )
            true
        }.getOrDefault(false)
    }

    /** 恢复出厂：删除 rootfs（FR-C2）。调用前需先停 SSH、关全部会话。 */
    fun wipe() {
        rootfsDir().deleteRecursively()
    }

    companion object {
        /** sources.list 内容构造（纯函数，可单测）。 */
        fun buildSourcesList(mirror: AptMirror): String = buildString {
            appendLine("deb ${mirror.url} trixie main contrib non-free non-free-firmware")
            appendLine("deb ${mirror.url} trixie-updates main contrib non-free non-free-firmware")
            appendLine("deb ${mirror.securityUrl} trixie-security main contrib non-free non-free-firmware")
        }

        /** 中文环境首次默认镜像（FR-W4）。tuna 实测对部分网络 403 不可靠（真机调试定位），改阿里云。 */
        fun defaultMirrorForLocale(locale: java.util.Locale = java.util.Locale.getDefault()): AptMirror =
            if (locale.language.startsWith("zh")) AptMirror.ALIYUN else AptMirror.OFFICIAL

        /** resolv.conf 内容（纯函数）：customDns 空 → 默认 8.8.8.8/223.5.5.5（v2.1.12 抽出可单测）。 */
        fun resolvContent(customDns: String): String =
            customDns.trim().ifBlank { "nameserver 8.8.8.8\nnameserver 223.5.5.5\n" }

        /**
         * 校验 Android 时区 id 并返回 zoneinfo 内对应条目（纯函数，可单测）。
         * Android 与 Debian 共用 IANA tz 库，id（Asia/Shanghai 等）直接对应 zoneinfo 路径。
         * @return zoneinfo 内时区文件；id 非法/本地缺条目返回 null（保持 UTC）
         */
        fun timezoneTarget(zoneinfoDir: File, androidZoneId: String): File? {
            val id = androidZoneId.trim().trimStart('/')
            if (id.isEmpty()) return null
            if (id.startsWith("..") || id.contains("../") || id.endsWith("/")) return null
            if (!id.all { it.isLetterOrDigit() || it == '/' || it == '_' || it == '+' || it == '-' }) return null
            val f = File(zoneinfoDir, id)
            if (!f.isFile || java.nio.file.Files.isSymbolicLink(f.toPath())) return null
            return f
        }
    }
}
