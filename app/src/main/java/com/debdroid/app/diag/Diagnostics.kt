package com.debdroid.app.diag

import android.content.Context
import android.os.Build
import com.debdroid.app.BuildConfig
import com.debdroid.app.core.FsOps
import com.debdroid.app.prefs.AppSettings
import com.debdroid.app.rootfs.RootfsInstaller
import com.debdroid.app.session.SessionManager
import com.debdroid.app.ssh.SshStatus
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 诊断信息导出（协作调试通道 A）。
 *
 * 覆盖应用层全状态：设备/版本/安装状态/设置快照（脱敏）/SSH 状态/
 * 最近终端与系统日志/崩溃堆栈。用户在设置页一键导出，粘贴给开发者即可定位
 * 大部分启动、崩溃与配置类问题——不依赖 SSH（那只覆盖 Debian 层）。
 *
 * [collect] 依赖 Android 环境；格式化逻辑保持纯字符串拼接便于阅读与粘贴。
 */
object Diagnostics {

    /** 收集并格式化完整诊断文本。 */
    fun collect(
        context: Context,
        settings: AppSettings,
        rootfsInstaller: RootfsInstaller,
        sshStatus: SshStatus,
        sessionManager: SessionManager,
    ): String = buildString {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        appendLine("===== DebDroid 诊断信息 =====")
        appendLine("时间: $ts")
        appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.BRAND})")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        appendLine("应用: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("数据目录: ${context.filesDir.path}")
        appendLine()

        appendLine("--- 安装状态 ---")
        val rootfs = rootfsInstaller.rootfsDir()
        appendLine("rootfs: ${if (rootfsInstaller.isInstalled()) "已安装" else "未安装"} 目录存在=${rootfs.exists()}")
        if (rootfs.exists()) {
            appendLine("rootfs 占用: ${FsOps.humanSize(rootfs.length())} (${rootfs.length()} bytes)")
        }
        val proot = File(rootfsInstaller.context.filesDir, "proot")
        appendLine("proot 运行时: ${proot.exists()}")
        appendLine()

        appendLine("--- 设置（密码脱敏）---")
        appendLine("主题=${settings.themeMode} 字号=${settings.fontSize} 配色=${settings.colorSchemeId}")
        appendLine("启动命令=${settings.startupCommand} 初始目录=${settings.initialDir}")
        appendLine("apt 镜像=${settings.aptMirrorId} 自定义DNS=${if (settings.customDns.isBlank()) "(默认)" else settings.customDns}")
        appendLine(
            "保活: 前台=${settings.keepForeground} 唤醒锁=${settings.keepWakelock} " +
                "电池白名单=${settings.keepBatteryWhitelist} 开机自启=${settings.keepBoot} 崩溃恢复=${settings.keepRestore}"
        )
        val keyCount = settings.sshAuthorizedKeys.lineSequence().count { it.isNotBlank() }
        appendLine(
            "SSH: 启用=${settings.sshEnabled} 端口=${settings.sshPort} 监听全部=${settings.sshListenAll} " +
                "密码=${if (settings.sshPassword.isBlank()) "(禁用)" else "***"} " +
                "公钥=${if (keyCount == 0) "(无)" else "$keyCount 行"} 自启=${settings.sshAutostart}"
        )
        appendLine("tmux 自动附加=${settings.tmuxAttach}")
        appendLine()

        appendLine("--- SSH 状态 ---")
        appendLine(sshStatus.toString())
        appendLine()

        appendLine("--- 最近日志 (${sessionManager.recentLogCount()} 条) ---")
        sessionManager.recentLogSnapshot().forEach { appendLine(it) }
        appendLine()

        appendLine("--- 崩溃记录 ---")
        val crash = File(context.filesDir, "crash.log")
        appendLine(if (crash.exists()) crash.readText() else "(无)")
    }
}
