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
     */
    fun configure(settings: AppSettings) {
        val mirror = AptMirror.fromId(settings.aptMirrorId)
        val sources = File(rootfsDir(), "etc/apt/sources.list")
        sources.parentFile?.mkdirs()
        sources.writeText(buildSourcesList(mirror))

        // resolv.conf：应用私有文件，由 ProotLauncher 绑定进 rootfs
        val resolv = File(context.filesDir, "resolv.conf")
        resolv.writeText(settings.customDns.ifBlank { "nameserver 8.8.8.8\nnameserver 223.5.5.5\n" })

        // v2.1.9：安装/配置即让 guest /etc/localtime 跟随系统时区（幂等，失败静默——UTC 兜底）
        runCatching { syncGuestTimezone() }
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
