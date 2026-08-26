package com.debdroid.app.session

import android.content.Context
import android.os.Environment
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import java.io.File
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream

/**
 * 构造 proot 命令行与环境（architecture.md §3.3 参数表——v1.x 全部实战经验落点）。
 *
 * 关键设计（每条都有历史教训支撑）：
 * - `-l -L --kill-on-exit`：link2symlink + 跟随符号链接 + 退出即杀 guest
 * - `/tmp` 与 `/root/.npm` 绑定宿主 filesDir：npm/cacache rename 避开 link2symlink 的 .l2s
 *   包装（v1.0.26/28 的 ENOENT 根因）；**必须 filesDir 而非 cacheDir**（cacheDir 被系统
 *   清空会形成悬空 bind d??????????，v1.0.27）
 * - `PROOT_F2FS_WORKAROUND=1` 强制：f2fs 探测不触发导致 npm link→.l2s 损坏（v1.0.29）
 * - 不设 `PROOT_NO_SECCOMP`：seccomp 快路径保留，纯 ptrace 输入回显卡顿（v1.0.7）
 * - proot 运行时以纯 tar 存 assets（AAPT2 会静默解压 *.gz 资产并丢后缀）
 */
class ProotLauncher(private val context: Context, private val settings: AppSettings) {

    /** proot 运行时目录（首次从 assets/proot.tar 解包）。 */
    fun prootDir(): File = File(context.filesDir, "opt/proot")

    /** 宿主侧 npm 缓存目录（guest 视角 /root/.npm）。 */
    fun npmCacheDir(): File = File(context.filesDir, "npm").apply { mkdirs() }

    /** 宿主侧 /tmp 绑定目录（guest 视角 /tmp，TMPDIR 指向它）。 */
    fun hostTmpDir(): File = File(context.filesDir, "tmp").apply { mkdirs() }

    /** 自定义 DNS 文件（存在才绑定 /etc/resolv.conf）。 */
    fun resolvFile(): File = File(context.filesDir, "resolv.conf")

    private fun prootTmpDir(): File = File(context.cacheDir, "proot").apply { mkdirs() }

    private fun libDir(): File = File(prootDir(), "lib")

    private fun loaderFile(): File = File(prootDir(), "libexec/proot/loader")

    /**
     * 确保 proot 运行时已解包。返回 proot 可执行文件，失败返回 null（调用方提示 FR-S5）。
     * 解包：assets/proot.tar → filesDir/opt/proot，保留符号链接与可执行位，写完成标记。
     */
    fun ensureBootstrap(): File? {
        val bin = File(prootDir(), "bin/proot")
        val marker = File(prootDir(), ".debdroid_bootstrap")
        if (marker.exists() && bin.exists()) return bin

        prootDir().deleteRecursively()
        prootDir().mkdirs()
        return try {
            var entryCount = 0
            context.assets.open("proot.tar").use { raw ->
                TarArchiveInputStream(raw).use { tar ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val entry: TarArchiveEntry = tar.nextTarEntry ?: break
                        val name = entry.name.removePrefix("./").removePrefix("/")
                        if (name.isEmpty()) continue
                        entryCount++
                        val out = File(prootDir(), name)
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
                            out.setReadable(true, false)
                        }
                    }
                }
            }
            marker.writeText("ok\n")
            if (bin.exists()) bin else null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "bootstrap extraction failed", e)
            null
        }
    }

    /** rootfs 内是否预装 tmux（镜像构建期安装）。 */
    fun hasTmux(): Boolean = File(RootfsInstaller(context).rootfsDir(), "usr/bin/tmux").exists()

    /**
     * 交互会话 argv（见 architecture.md §3.3 参数表）。
     * 默认启动命令 + 开启 tmux 保持 + rootfs 有 tmux → `tmux new -A -s main`（FR-S3）。
     */
    fun buildArgs(): MutableList<String> {
        val prootBin = ensureBootstrap()
            ?: File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        val args = mutableListOf(
            prootBin.path,
            "-r", RootfsInstaller(context).rootfsDir().path,
            "-0",
            "-l",                       // link2symlink：dpkg linkat 转符号链接
            "-L",                       // 跟随符号链接
            "--kill-on-exit",           // 退出即杀 guest
            "-b", "${hostTmpDir().path}:/tmp",          // 宿主 /tmp（npm rename 关键）
            "-b", "${npmCacheDir().path}:/root/.npm",   // npm 缓存绑定
            "-w", settings.initialDir.ifBlank { "/root" },
            "-b", "/dev",
            "-b", "/dev/urandom:/dev/random",
            "-b", "/dev/pts",
            "-b", "/proc",
            "-b", "/sys",
            "-b", "/proc/self/fd:/dev/fd",
        )
        if (resolvFile().exists()) {
            args += listOf("-b", "${resolvFile().path}:/etc/resolv.conf")
        }
        val defaultStartup = "/bin/bash --login"
        val startup = settings.startupCommand.trim().ifBlank { defaultStartup }.let {
            if (it == defaultStartup && settings.tmuxAttach && hasTmux()) "tmux new -A -s main" else it
        }
        args += listOf(
            "/usr/bin/env", "-i",
            "HOME=/root", "USER=root", "LOGNAME=root", "SHELL=/bin/bash",
            "TERM=xterm-256color",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "LC_CTYPE=C.UTF-8",
        )
        // 经 /bin/sh -c 执行，多词命令正确（直接传整串 argv 会被 env 当成文件名，exit 127）
        args += listOf("/bin/sh", "-c", startup)
        return args
    }

    /** proot 进程自身环境（Android 侧）。 */
    fun buildEnv(): Array<String> {
        val prootBin = File(prootDir(), "bin/proot")
        val env = mutableListOf(
            "PROOT_TMP_DIR=${prootTmpDir().path}",
            // 强制 f2fs workaround（Termux proot-distro 同款），v1.0.29 根因修复
            "PROOT_F2FS_WORKAROUND=1",
            // 不设 PROOT_NO_SECCOMP：seccomp 快路径必须保留
            "PATH=/system/bin:/system/xbin",
            "HOME=${context.filesDir.path}",
            // guest 视角 TMPDIR → 宿主绑定的 /tmp
            "TMPDIR=/tmp",
            "ANDROID_ROOT=/system",
            "ANDROID_DATA=/data",
            "EXTERNAL_STORAGE=${Environment.getExternalStorageDirectory().path}",
        )
        if (prootBin.exists()) {
            env += "LD_LIBRARY_PATH=${libDir().path}"
            env += "PROOT_LOADER=${loaderFile().path}"
        }
        return env.toTypedArray()
    }

    /**
     * 一次性命令执行（SSH 启停、apt、配置写入等），无 pty。
     * 复用 buildArgs 前缀，把尾部 /bin/sh -c <startup> 替换为 /bin/bash -c <command>。
     */
    fun runOnce(command: String, timeoutSeconds: Long = 600): CommandResult {
        ensureBootstrap()
        val args = buildArgs()
        val envIndex = args.indexOf("/usr/bin/env")
        val cmdArgs = args.subList(0, envIndex).toMutableList()
        cmdArgs += listOf("/usr/bin/env", "-i")
        cmdArgs += args.subList(envIndex + 2, args.size - 3) // env 赋值段
        cmdArgs += listOf("/bin/bash", "-c", command)

        val pb = ProcessBuilder(cmdArgs)
        pb.redirectErrorStream(true)
        val env = pb.environment()
        env.clear()
        buildEnv().forEach { pair ->
            val i = pair.indexOf('=')
            if (i > 0) env[pair.substring(0, i)] = pair.substring(i + 1)
        }
        val process = pb.start()
        val output = StringBuilder()
        val reader = process.inputStream.bufferedReader()
        val readerThread = Thread {
            reader.forEachLine { line -> synchronized(output) { output.appendLine(line) } }
        }
        readerThread.isDaemon = true
        readerThread.start()
        val finished = process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return CommandResult(-1, "$output\n[timeout after ${timeoutSeconds}s]")
        }
        readerThread.join(2000)
        return CommandResult(process.exitValue(), output.toString().trim())
    }

    data class CommandResult(val exitCode: Int, val output: String) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    companion object {
        private const val TAG = "ProotLauncher"
    }
}
